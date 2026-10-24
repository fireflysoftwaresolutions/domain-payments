#!/usr/bin/env python3
"""Check the packaged application on a private loopback port without provider calls."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


def verify_sandbox_payment(base_url):
    def request(route, body=None, expected_status=200):
        payload = None if body is None else json.dumps(body).encode()
        call = urllib.request.Request(base_url + route, data=payload,
                                      headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(call, timeout=15) as response:
            if response.status != expected_status:
                raise AssertionError(f"Expected HTTP {expected_status}, got {response.status}")
            return json.load(response)

    command = {
        "scheme": "DOMESTIC_OUTBOUND", "idempotencyKey": str(uuid.uuid4()),
        "amount": "25.00", "currency": "EUR",
        "partyId": "10000000-0000-4000-8000-0000000000aa",
        "contractId": "10000000-0000-4000-8000-000000000021",
        "sourceAccountId": "10000000-0000-4000-8000-000000000011",
        "paymentMethodId": "10000000-0000-4000-8000-000000000031",
        "externalAccountName": "Synthetic sandbox beneficiary",
    }
    tried = request("/api/v1/payments/tries", command, 201)
    if tried.get("phase") != "TRYING" or not re.fullmatch(r"[0-9]{6}", tried.get("challengeValue", "")):
        raise AssertionError("Sandbox TRY did not issue its own six-digit challenge")
    replay = request("/api/v1/payments/tries", command, 201)
    if replay.get("paymentId") != tried.get("paymentId"):
        raise AssertionError("Exact sandbox TRY replay changed the payment identity")
    route = f"/api/v1/payments/{tried['paymentId']}"
    confirmed = request(route + "/confirmations", {"response": tried["challengeValue"]})
    if confirmed.get("phase") != "CONFIRMED" or confirmed.get("thirdPartySynced") is not True:
        raise AssertionError("Sandbox confirmation did not complete its synthetic rail action")
    if request(route).get("phase") != "CONFIRMED":
        raise AssertionError("Sandbox status did not retain confirmation")
    command["idempotencyKey"] = str(uuid.uuid4())
    cancellable = request("/api/v1/payments/tries", command, 201)
    cancelled = request(f"/api/v1/payments/{cancellable['paymentId']}/cancellations", {})
    if cancelled.get("phase") != "CANCELLED":
        raise AssertionError("Sandbox cancellation did not complete")
    print("PASS: packaged sandbox HTTP TRY, exact replay, challenge confirmation, status, and cancellation")
    print("Scope: synthetic process-local adapters only; no durable funds, provider, or settlement acceptance.")


def verify(jar, java, log_file, sandbox_payment=False):
    with tempfile.TemporaryDirectory(prefix="payments-runtime-") as directory:
        log = Path(directory) / "application.log"
        with log.open("w") as output:
            process = subprocess.Popen([
                java, "-jar", str(jar.resolve()), "--server.address=127.0.0.1", "--server.port=0",
                "--firefly.payments.mode=sandbox", "--firefly.payments.configuration=sandbox",
            ], stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    content = log.read_text()
                    if process.poll() is not None:
                        raise AssertionError(f"Packaged application exited {process.returncode}:\n{content}")
                    port = re.search(r"Netty started on port (\d+)", content)
                    if port and "Started DomainPaymentsApplication" in content:
                        break
                    time.sleep(0.2)
                else:
                    raise AssertionError(f"Packaged application did not start:\n{log.read_text()}")
                base_url = f"http://127.0.0.1:{port.group(1)}"
                with urllib.request.urlopen(f"{base_url}/actuator/health", timeout=10) as response:
                    body = json.load(response)
                    if response.status != 200 or body.get("status") != "UP":
                        raise AssertionError(f"Expected health HTTP 200/UP, got {response.status}: {body}")
                url = f"{base_url}/api/v1/payments/not-a-uuid"
                try:
                    urllib.request.urlopen(url, timeout=10)
                except urllib.error.HTTPError as response:
                    if response.code != 400:
                        raise AssertionError(f"Expected controller validation HTTP 400, got {response.code}")
                    body = json.load(response)
                    if body.get("status") != 400 or body.get("path") != "/api/v1/payments/not-a-uuid":
                        raise AssertionError(f"Unexpected controller validation response: {body}")
                else:
                    raise AssertionError("Malformed payment ID should be rejected with HTTP 400")
                print(f"PASS: executable JAR started on loopback:{port.group(1)}; health HTTP 200/UP; payment status route rejected malformed UUID with HTTP 400")
                print("Scope: startup and HTTP binding only; no payment/provider/persistence acceptance.")
                if sandbox_payment:
                    verify_sandbox_payment(base_url)
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                if log_file:
                    log_file.write_text(log.read_text())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java")
    parser.add_argument("--log-file", type=Path, help="Preserve the application startup log")
    parser.add_argument("--exercise-sandbox", action="store_true",
                        help="Also exercise the owned application's synthetic payment lifecycle")
    args = parser.parse_args()
    verify(args.jar, args.java, args.log_file, args.exercise_sandbox)
