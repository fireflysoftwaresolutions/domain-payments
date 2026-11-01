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

package io.fireflysoftware.domain.payments.infra.managed;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import io.fireflysoftware.domain.payments.core.managed.ports.PaymentDelegationPort;
import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.*;
import java.time.*;
import java.util.*;

class SdkManagedPaymentCoreAdapterTest {
    final UUID id = UUID.randomUUID(),
            action = UUID.randomUUID(),
            a = UUID.randomUUID(),
            b = UUID.randomUUID();
    final View view =
            new View(
                    id,
                    action,
                    "owned",
                    a,
                    b,
                    "1.00",
                    "USD",
                    2,
                    "policy",
                    30,
                    null,
                    System.currentTimeMillis() + 30000,
                    "real-actor",
                    State.RESERVING,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    UUID.randomUUID(),
                    null,
                    null,
                    null);
    final ManagedPaymentDelegationAdapterTest fixture = new ManagedPaymentDelegationAdapterTest();

    @Test
    void genuineSdkPreserves201And200BodiesAndExactPinnedInput() throws Exception {
        var requests = new ArrayList<String>();
        var status = new java.util.concurrent.atomic.AtomicInteger(201);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    requests.add(
                            e.getRequestHeaders().getFirst("Authorization")
                                    + " "
                                    + new String(
                                            e.getRequestBody().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8));
                    var body =
                            new com.fasterxml.jackson.databind.ObjectMapper()
                                    .findAndRegisterModules()
                                    .writeValueAsBytes(view);
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(status.get(), body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.start();
        try {
            PaymentDelegationPort tokens =
                    (aud, t, accounts, scopes) -> Mono.just("synthetic-current-token");
            var adapter =
                    new SdkManagedPaymentCoreAdapterImpl(
                            tokens,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "core",
                            true);
            var input =
                    new Create(
                            action,
                            a,
                            b,
                            new BigDecimal("1.00"),
                            "USD",
                            30,
                            view.notAfterEpochMillis());
            var created = adapter.create("owned", input).block(Duration.ofSeconds(5));
            assertThat(created.replayed()).isFalse();
            assertThat(created.payment()).isEqualTo(view);
            status.set(200);
            assertThat(adapter.create("owned", input).block(Duration.ofSeconds(5)).replayed())
                    .isTrue();
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst())
                    .contains(
                            "Bearer synthetic-current-token",
                            "\"customerNotAfterEpochMillis\":" + view.notAfterEpochMillis(),
                            "\"ttlSeconds\":30");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reverseDecisionDelegatesItsOwnScopeAndDecodesReversingThroughGenuineSdk() throws Exception {
        var requested = new java.util.concurrent.atomic.AtomicReference<Set<String>>();
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(json.readTree(exchange.getRequestBody()));
            var output = json.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(view);
            output.put("state", "REVERSING");
            byte[] bytes = json.writeValueAsBytes(output);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            PaymentDelegationPort tokens = (audience, tenant, accounts, scopes) -> {
                requested.set(scopes);
                return Mono.just("synthetic-current-token");
            };
            var adapter = new SdkManagedPaymentCoreAdapterImpl(tokens,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "core", true);
            var decision = new Decision(action, DecisionKind.REVERSE, 8L);
            var actor = fixture.actor(Set.of("core"), Set.of("payments:reverse"), Instant.now().plusSeconds(60));
            var result = fixture.run(adapter.decide("owned", id, decision), actor);
            assertThat(result.state()).isEqualTo(State.REVERSING);
            assertThat(requested.get()).containsExactly("payments:reverse");
            assertThat(bodies).hasSize(1);
            assertThat(bodies.getFirst().get("kind").asText()).isEqualTo("REVERSE");
            assertThat(bodies.getFirst().get("actionId").asText()).isEqualTo(action.toString());
            assertThat(bodies.getFirst().get("expectedVersion").asLong()).isEqualTo(8);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void peerDeniedBodyAndCredentialCannotEscapeAdapter() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    var bytes = "synthetic-current-token raw confidential peer".getBytes();
                    e.sendResponseHeaders(403, bytes.length);
                    e.getResponseBody().write(bytes);
                    e.close();
                });
        server.start();
        try {
            var adapter =
                    new SdkManagedPaymentCoreAdapterImpl(
                            (aud, t, accounts, scopes) -> Mono.just("synthetic-current-token"),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "core",
                            true);
            assertThatThrownBy(
                            () ->
                                    adapter.create(
                                                    "owned",
                                                    new Create(
                                                            action,
                                                            a,
                                                            b,
                                                            BigDecimal.ONE,
                                                            "USD",
                                                            30,
                                                            null))
                                            .block(Duration.ofSeconds(5)))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> {
                                assertThat(e.getStatusCode().value()).isEqualTo(403);
                                assertThat(e.getCause()).isNull();
                                assertThat(e.toString())
                                        .doesNotContain("confidential", "synthetic-current-token");
                            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noUnconfiguredOrCredentialBearingPeerUrlCanSendToken() {
        for (String url :
                List.of(
                        "",
                        "http://example.com",
                        "https://user:password@example.com",
                        "https://example.com?credential=secret")) {
            var delegation = org.mockito.Mockito.mock(PaymentDelegationPort.class);
            var adapter = new SdkManagedPaymentCoreAdapterImpl(delegation, url, "core", false);
            assertThatThrownBy(
                            () ->
                                    adapter.create(
                                                    "owned",
                                                    new Create(
                                                            action,
                                                            a,
                                                            b,
                                                            BigDecimal.ONE,
                                                            "USD",
                                                            30,
                                                            null))
                                            .block(Duration.ofSeconds(3)))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
            org.mockito.Mockito.verifyNoInteractions(delegation);
        }
    }
}
