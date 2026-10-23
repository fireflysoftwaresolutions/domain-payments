/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fireflysoftware.domain.payments.infra.managed.v1;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;

import io.fireflysoftware.domain.payments.core.managed.v1.ManagedPaymentContext;
import io.fireflysoftware.domain.payments.core.managed.v1.ports.*;
import io.fireflysoftware.domain.payments.managed.v1.ManagedPaymentContracts.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.net.*;
import java.time.*;
import java.util.*;

class SdkManagedFundsAdapterTest {
    static final Duration WAIT = Duration.ofSeconds(5);

    static final class Fixture implements AutoCloseable {
        final UUID id = UUID.randomUUID(),
                a = UUID.randomUUID(),
                b = UUID.randomUUID(),
                reservation = UUID.randomUUID(),
                posting = UUID.randomUUID(),
                effect = UUID.randomUUID();
        final long deadline = System.currentTimeMillis() + 30000;
        final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<JsonNode> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final HttpServer server;
        boolean mismatch = false, earlyExpire = false;

        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    e -> {
                        var body = mapper.readTree(e.getRequestBody());
                        paths.add(e.getRequestURI().getPath());
                        bodies.add(body);
                        Object output;
                        int status = 200;
                        if (e.getRequestURI().getPath().endsWith("/reconciliation")) {
                            var intent = body.deepCopy();
                            if (mismatch)
                                ((com.fasterxml.jackson.databind.node.ObjectNode) intent)
                                        .put("ttlSeconds", 31);
                            output =
                                    Map.of(
                                            "outcome",
                                            "FOUND",
                                            "notAfterEpochMillis",
                                            deadline,
                                            "observedAt",
                                            LocalDateTime.now().toString(),
                                            "intent",
                                            intent,
                                            "reservation",
                                            reservation("HELD"));
                        } else if (body.has("action")) {
                            status = earlyExpire ? 409 : 200;
                            output =
                                    earlyExpire
                                            ? Map.of("error", "NOT_DUE")
                                            : reservation(
                                                    body.get("action").asText().equals("COMMIT")
                                                            ? "COMMITTED"
                                                            : "EXPIRED");
                        } else output = reservation("HELD");
                        byte[] bytes = mapper.writeValueAsBytes(output);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(status, bytes.length);
                        e.getResponseBody().write(bytes);
                        e.close();
                    });
            server.start();
        }

        Map<String, Object> reservation(String state) {
            return Map.ofEntries(
                    Map.entry("operationId", id),
                    Map.entry("reservationId", reservation),
                    Map.entry("sourceAccountId", a),
                    Map.entry("destinationAccountId", b),
                    Map.entry("amount", "1.00"),
                    Map.entry("currency", "USD"),
                    Map.entry("state", state),
                    Map.entry("postingId", posting),
                    Map.entry("version", state.equals("HELD") ? 0 : 1),
                    Map.entry(
                            "expiresAt",
                            LocalDateTime.ofInstant(
                                            Instant.ofEpochMilli(deadline - 1000), ZoneOffset.UTC)
                                    .toString()));
        }

        View view() {
            return new View(
                    id,
                    UUID.randomUUID(),
                    "owned",
                    a,
                    b,
                    "1.00",
                    "USD",
                    2,
                    "policy",
                    30,
                    null,
                    deadline,
                    "actor",
                    State.RESERVED,
                    2,
                    reservation,
                    posting,
                    null,
                    null,
                    0L,
                    null,
                    effect,
                    null,
                    "HELD",
                    null);
        }

        EffectClaim claim(EffectKind k) {
            return new EffectClaim(
                    id,
                    effect,
                    k,
                    k == EffectKind.RESERVE ? id : effect,
                    1,
                    Instant.now().plusSeconds(30),
                    2,
                    view());
        }

        SdkManagedFundsAdapter adapter(PaymentDelegationPort d) {
            return new SdkManagedFundsAdapter(
                    d, "http://127.0.0.1:" + server.getAddress().getPort(), "ledger", true);
        }

        PaymentDelegationPort allowed() {
            return (aud, t, pair, scopes) -> Mono.just("synthetic-current");
        }

        public void close() {
            server.stop(0);
        }
    }

    @Test
    void reserveUsesStableOperationOriginalTtlAndPinnedDeadline() throws Exception {
        try (var f = new Fixture()) {
            var result =
                    f.adapter(f.allowed())
                            .reserve("owned", f.claim(EffectKind.RESERVE))
                            .block(WAIT);
            assertThat(result.operationId()).isEqualTo(f.id);
            assertThat(f.bodies.getFirst().get("operationId").asText()).isEqualTo(f.id.toString());
            assertThat(f.bodies.getFirst().get("ttlSeconds").intValue()).isEqualTo(30);
            assertThat(f.bodies.getFirst().get("notAfterEpochMillis").longValue())
                    .isEqualTo(f.deadline);
        }
    }

    @Test
    void commitUsesRetainedReservationAndParticipantAction() throws Exception {
        try (var f = new Fixture()) {
            var result =
                    f.adapter(f.allowed()).act("owned", f.claim(EffectKind.COMMIT)).block(WAIT);
            assertThat(result.state()).isEqualTo("COMMITTED");
            assertThat(f.paths.getFirst()).contains(f.reservation.toString());
            assertThat(f.bodies.getFirst().get("actionId").asText()).isEqualTo(f.effect.toString());
            assertThat(f.bodies.getFirst().get("action").asText()).isEqualTo("COMMIT");
        }
    }

    @Test
    void observerUsesExactReconciliationThenLedgerExpiryAndReobservesEarlyConflict()
            throws Exception {
        try (var f = new Fixture()) {
            f.earlyExpire = true;
            var result =
                    f.adapter(f.allowed())
                            .observe("owned", f.claim(EffectKind.OBSERVE))
                            .block(WAIT);
            assertThat(result.state()).isEqualTo("HELD");
            assertThat(f.paths).hasSize(3);
            assertThat(f.paths.get(0)).endsWith("/reconciliation");
            assertThat(f.paths.get(2)).endsWith("/reconciliation");
            assertThat(f.bodies.get(1).get("action").asText()).isEqualTo("EXPIRE");
            assertThat(f.bodies.get(1).get("actionId").asText()).isEqualTo(f.effect.toString());
        }
    }

    @Test
    void changedEchoTtlFailsClosedBeforeExpiry() throws Exception {
        try (var f = new Fixture()) {
            f.mismatch = true;
            assertThatThrownBy(
                            () ->
                                    f.adapter(f.allowed())
                                            .observe("owned", f.claim(EffectKind.OBSERVE))
                                            .block(WAIT))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
            assertThat(f.paths).hasSize(1);
        }
    }

    @Test
    void deniedExpiryNeverBecomesLocalExpiry() throws Exception {
        try (var f = new Fixture()) {
            PaymentDelegationPort d =
                    (aud, t, pair, scopes) ->
                            scopes.contains("funds:expire")
                                    ? Mono.error(ManagedPaymentContext.fail(403, "REVOKED"))
                                    : Mono.just("synthetic-current");
            assertThatThrownBy(
                            () ->
                                    f.adapter(d)
                                            .observe("owned", f.claim(EffectKind.OBSERVE))
                                            .block(WAIT))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
            assertThat(f.paths).hasSize(1);
        }
    }
}
