"""Private loopback network faults for actual-ledger qualification; no financial substitutes."""
import http.server
import json
import threading
import time
import urllib.error
import urllib.request


class LedgerFaultGateway:
    def __init__(self, ledger_url):
        self.ledger_url = ledger_url
        self.lock = threading.Lock()
        self.rule = None
        self.unavailable = False
        self.hit = threading.Event()
        self.resume = threading.Event()
        self.requests = []
        self.hit_request = None
        gateway = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.forward()

            def do_POST(self):
                self.forward()

            def reply(self, status, body):
                try:
                    self.send_response(status)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def forward(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
                payload = json.loads(raw) if raw else {}
                action = payload.get('action')
                if self.path.endswith('/reservations') and payload.get('operationId'):
                    action = 'RESERVE'
                with gateway.lock:
                    rule = gateway.rule
                    if rule and rule[1] == action:
                        gateway.rule = None
                    else:
                        rule = None
                    unavailable = gateway.unavailable
                entry = {'time': time.time(), 'path': self.path, 'action': action,
                         'operation_id': payload.get('operationId'), 'status': None}
                gateway.requests.append(entry)
                if rule:
                    gateway.hit_request = entry
                if unavailable:
                    entry['status'] = 503
                    return self.reply(503, b'{"error":"OWNED_NETWORK_FAULT"}')
                if rule and rule[0] == 'before':
                    gateway.hit.set()
                    if not gateway.resume.wait(60):
                        return self.reply(503, b'{"error":"OWNED_BARRIER_TIMEOUT"}')
                request = urllib.request.Request(gateway.ledger_url + self.path,
                    data=raw if raw else None, method=self.command,
                    headers={k: v for k, v in self.headers.items()
                             if k.lower() not in ['host', 'content-length', 'connection']})
                try:
                    with urllib.request.urlopen(request, timeout=30) as response:
                        status, body = response.status, response.read()
                except urllib.error.HTTPError as error:
                    status, body = error.code, error.read()
                except OSError:
                    status, body = 503, b'{"error":"OWNED_UPSTREAM_UNAVAILABLE"}'
                entry['status'] = status
                if rule and rule[0] == 'after':
                    gateway.hit.set()
                    if not gateway.resume.wait(60):
                        return self.reply(503, b'{"error":"OWNED_BARRIER_TIMEOUT"}')
                self.reply(status, body)

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.url = 'http://127.0.0.1:' + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def arm(self, phase, action):
        if phase not in ['before', 'after'] or action not in ['RESERVE', 'COMMIT', 'RELEASE']:
            raise ValueError('Bounded owned ledger barrier required')
        with self.lock:
            if self.rule:
                raise RuntimeError('Previous owned barrier still armed')
            self.hit_request = None
            self.hit.clear()
            self.resume.clear()
            self.rule = (phase, action)

    def close(self):
        self.resume.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
