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

package io.fireflysoftware.domain.payments.web.managed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import io.fireflysoftware.domain.payments.core.managed.ports.*;
import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;
import io.fireflysoftware.domain.payments.web.DomainPaymentsApplication;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import reactor.core.publisher.Mono;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

@SpringBootTest(
        classes = DomainPaymentsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "firefly.eda.enabled=false",
            "firefly.stepevents.enabled=false",
            "firefly.web.idempotency.enabled=true"
        })
class ManagedPaymentsAuthenticatedRuntimeTest {
    static final RSAKey KEY;
    static final HttpServer JWK;
    static final String ISSUER = "https://owned-domain-issuer.invalid";

    static {
        try {
            KEY =
                    new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
                            .keyID("owned-domain-key")
                            .generate();
            JWK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWK.createContext(
                    "/jwks",
                    e -> {
                        byte[] bytes =
                                new JWKSet(KEY.toPublicJWK())
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                        e.close();
                    });
            JWK.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry r) {
        r.add("managed-payments.security.issuer", () -> ISSUER);
        r.add("managed-payments.security.audience", () -> "domain-payments");
        r.add(
                "managed-payments.security.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWK.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void close() {
        JWK.stop(0);
    }

    @LocalServerPort int port;
    @MockitoBean ManagedPaymentCorePort core;
    @MockitoBean ManagedFundsPort funds;
    @Autowired ExecutionPersistenceProvider persistence;
    final UUID payment = UUID.randomUUID(),
            action = UUID.randomUUID(),
            a = UUID.randomUUID(),
            b = UUID.randomUUID();
    final long deadline = System.currentTimeMillis() + 30000;

    View view() {
        return new View(
                payment,
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
                deadline,
                "real-actor",
                State.RESERVED,
                2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                0L,
                Instant.now(),
                null,
                null,
                "HELD",
                Instant.ofEpochMilli(deadline));
    }

    String token(String scopes, List<String> accounts) throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("real-actor")
                        .audience("domain-payments")
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds(120)))
                        .claim("tenant_id", "owned")
                        .claim("scope", scopes)
                        .claim("funds_accounts", accounts)
                        .build();
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                        claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    String scopes() {
        return "payments:create payments:read payments:coordinate funds:read funds:reserve"
                + " funds:reconcile";
    }

    HttpResponse<String> request(String method, String path, String token, String body)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", action.toString())
                        .header("Content-Type", "application/json");
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void signedNativeCommandReplayRechecksCurrentScopesAndDiagnosticsStayDenied() throws Exception {
        var retained = view();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(core.create(eq("owned"), any()))
                .thenAnswer(i -> Mono.just(new Acceptance(retained, calls.getAndIncrement() > 0)));
        when(core.read("owned", payment)).thenReturn(Mono.just(retained));
        String valid = token(scopes(), List.of(a.toString(), b.toString()));
        String body =
                "{\"actionId\":\""
                        + action
                        + "\",\"sourceAccountId\":\""
                        + a
                        + "\",\"destinationAccountId\":\""
                        + b
                        + "\",\"amount\":1.00,\"currency\":\"USD\",\"ttlSeconds\":30}";
        String path = "/api/v1/tenants/owned/internal-payments";
        var first = request("POST", path, valid, body);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(first.body()).contains(payment.toString(), "RESERVED");
        var replay = request("POST", path, valid, body);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);
        var beforeDenied =
                persistence
                        .findByPattern(ExecutionPattern.SAGA)
                        .filter(state -> state.correlationId().equals(action.toString()))
                        .single()
                        .block(Duration.ofSeconds(3));
        var denied =
                request(
                        "POST",
                        path,
                        token(
                                scopes().replace("payments:create", ""),
                                List.of(a.toString(), b.toString())),
                        body);
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        assertThat(calls.get()).isEqualTo(2);
        var states =
                persistence
                        .findByPattern(ExecutionPattern.SAGA)
                        .collectList()
                        .block(Duration.ofSeconds(3));
        var correlated =
                states.stream()
                        .filter(state -> state.correlationId().equals(action.toString()))
                        .toList();
        assertThat(correlated).containsExactly(beforeDenied);
        assertThat(correlated.getFirst().executionName()).isEqualTo("ManagedInternalPaymentCreate");
        assertThat(correlated.getFirst().stepResults())
                .containsOnlyKeys("accept", "claim", "apply", "acknowledge", "read");
        verify(core, times(2)).create(eq("owned"), any());
        verify(core, times(2)).read("owned", payment);
        assertThat(states.toString()).doesNotContain(valid, "Bearer ", "Authorization");
        assertThat(request("GET", "/api/orchestration/executions", valid, null).statusCode())
                .isEqualTo(403);
        assertThat(
                        request("GET", "/api/orchestration/workflows/instances", valid, null)
                                .statusCode())
                .isEqualTo(403);
        verifyNoInteractions(funds);
    }

    @Test
    void currentResourceGrantIsEnforcedBeforeNativeCommandAndReadCannotUseQueryCache()
            throws Exception {
        var retained = view();
        when(core.read("owned", payment)).thenReturn(Mono.just(retained));
        String valid = token(scopes(), List.of(a.toString(), b.toString()));
        String path = "/api/v1/tenants/owned/internal-payments/" + payment;
        var allowed = request("GET", path, valid, null);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        var denied = request("GET", path, token(scopes(), List.of(a.toString())), null);
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        verify(core, times(2)).read("owned", payment);
        verifyNoInteractions(funds);
    }

    @Test
    void signedHistoryQueryPreservesReferencesAndRechecksCurrentReadAuthority() throws Exception {
        var retained = view();
        var posting = retained.postingId();
        var reversal = UUID.randomUUID();
        var completed =
                new HistoryEvent(
                        5,
                        "LEDGER_COMMITTED",
                        "original-actor",
                        Instant.now(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        State.COMPLETED,
                        posting,
                        null);
        var reversed =
                new HistoryEvent(
                        7,
                        "LEDGER_REVERSED",
                        "recovery-actor",
                        Instant.now(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        State.REVERSED,
                        posting,
                        reversal);
        when(core.read("owned", payment)).thenReturn(Mono.just(retained));
        when(core.history("owned", payment, -1, 25))
                .thenReturn(reactor.core.publisher.Flux.just(completed, reversed));
        var path = "/api/v1/tenants/owned/internal-payments/" + payment + "/history";
        var allowed =
                request("GET", path, token(scopes(), List.of(a.toString(), b.toString())), null);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(200);
        assertThat(allowed.body())
                .contains(
                        "LEDGER_COMMITTED",
                        "LEDGER_REVERSED",
                        posting.toString(),
                        reversal.toString(),
                        "original-actor",
                        "recovery-actor");
        var denied = request("GET", path, token(scopes(), List.of(a.toString())), null);
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        verify(core, times(1)).history("owned", payment, -1, 25);
        var bounded =
                request(
                        "GET",
                        path + "?limit=101",
                        token(scopes(), List.of(a.toString(), b.toString())),
                        null);
        assertThat(bounded.statusCode()).as(bounded.body()).isEqualTo(400);
        verifyNoInteractions(funds);
    }
}
