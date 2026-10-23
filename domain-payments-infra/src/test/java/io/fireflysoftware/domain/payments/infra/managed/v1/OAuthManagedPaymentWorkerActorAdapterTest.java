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

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import org.fireflyframework.security.client.OAuth2ClientAutoConfiguration;
import org.fireflyframework.security.rs.AccessTokenValidators;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

class OAuthManagedPaymentWorkerActorAdapterTest {
    static final String ISSUER = "https://private-worker-issuer.invalid";
    static final String SCOPES =
            "payments:recover payments:read payments:coordinate funds:read funds:reconcile";

    static final class Fixture implements AutoCloseable {
        final RSAKey key =
                new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
                        .keyID("private-worker")
                        .generate();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String secret = UUID.randomUUID().toString();
        final UUID account = UUID.randomUUID();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        String issuer = ISSUER,
                audience = "domain",
                tenant = "owned",
                client = "worker-owned",
                scope = SCOPES;
        int lifetime = 30, responseLifetime = 30;
        boolean expire = false, omitClient = false;

        Fixture() throws Exception {
            server.createContext(
                    "/jwks",
                    x -> {
                        byte[] b =
                                new JWKSet(key.toPublicJWK())
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        x.getResponseHeaders().set("Content-Type", "application/json");
                        x.sendResponseHeaders(200, b.length);
                        x.getResponseBody().write(b);
                        x.close();
                    });
            server.createContext(
                    "/oauth2/token",
                    x -> {
                        try {
                            calls.incrementAndGet();
                            assertThat(x.getRequestMethod()).isEqualTo("POST");
                            assertThat(
                                            new String(
                                                    x.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8))
                                    .contains("grant_type=client_credentials");
                            assertThat(x.getRequestHeaders().getFirst("Authorization"))
                                    .isEqualTo(
                                            "Basic "
                                                    + Base64.getEncoder()
                                                            .encodeToString(
                                                                    ("worker-owned:" + secret)
                                                                            .getBytes(
                                                                                    StandardCharsets
                                                                                            .UTF_8)));
                            var builder =
                                    new JWTClaimsSet.Builder()
                                            .issuer(issuer)
                                            .subject("real-service-subject")
                                            .audience(audience)
                                            .issueTime(new Date())
                                            .expirationTime(
                                                    Date.from(
                                                            Instant.now()
                                                                    .plusSeconds(
                                                                            expire
                                                                                    ? -2
                                                                                    : lifetime)))
                                            .claim("tenant_id", tenant)
                                            .claim("scope", scope)
                                            .claim("funds_accounts", List.of(account.toString()));
                            if (!omitClient) builder.claim("client_id", client);
                            var jwt =
                                    new SignedJWT(
                                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                                    .keyID(key.getKeyID())
                                                    .build(),
                                            builder.build());
                            jwt.sign(new RSASSASigner(key));
                            byte[] b =
                                    ("{\"access_token\":\""
                                                    + jwt.serialize()
                                                    + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                                                    + responseLifetime
                                                    + "}")
                                            .getBytes(StandardCharsets.UTF_8);
                            x.getResponseHeaders().set("Content-Type", "application/json");
                            x.sendResponseHeaders(200, b.length);
                            x.getResponseBody().write(b);
                            x.close();
                        } catch (Exception e) {
                            x.sendResponseHeaders(500, -1);
                            x.close();
                        }
                    });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        ClientRegistration registration(String id, String clientId) {
            return ClientRegistration.withRegistrationId(id)
                    .clientId(clientId)
                    .clientSecret(secret)
                    .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                    .tokenUri(base() + "/oauth2/token")
                    .build();
        }

        OAuthManagedPaymentWorkerActorAdapter adapter() {
            var registrations =
                    new InMemoryReactiveClientRegistrationRepository(
                            registration("owned-worker", "worker-owned"));
            var config = new OAuth2ClientAutoConfiguration();
            var service = config.fireflyAuthorizedClientService(registrations);
            var manager = config.fireflyAuthorizedClientManager(registrations, service);
            var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(base() + "/jwks").build();
            decoder.setJwtValidator(AccessTokenValidators.forIssuerAndAudience(ISSUER, "domain"));
            return new OAuthManagedPaymentWorkerActorAdapter(
                    manager, registrations, decoder, Map.of("owned", "owned-worker"), true);
        }

        public void close() {
            server.stop(0);
        }
    }

    @Test
    void realNativeManagerAcquiresFiniteSignedBoundWorkerAndRefreshesAtActualSixtySecondSkew()
            throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            var first = adapter.current("owned").block(Duration.ofSeconds(5));
            assertThat(first.principal().subject()).isEqualTo("real-service-subject");
            assertThat(first.principal().tenantId()).isEqualTo("owned");
            assertThat(first.audience("domain")).isTrue();
            assertThat(first.toString()).doesNotContain(first.bearer());
            f.scope = SCOPES.replace("payments:recover", "");
            assertDenied(() -> adapter.current("owned").block(Duration.ofSeconds(5)), 403);
            assertThat(f.calls).hasValue(2);
        }
    }

    @Test
    void signedIssuerAudienceTenantClientAndMissingBindingCannotBecomeWorkerAuthority()
            throws Exception {
        try (var f = new Fixture()) {
            for (String variant : List.of("issuer", "audience", "tenant", "client", "missing")) {
                f.issuer = variant.equals("issuer") ? "https://wrong.invalid" : ISSUER;
                f.audience = variant.equals("audience") ? "wrong" : "domain";
                f.tenant = variant.equals("tenant") ? "other" : "owned";
                f.client = variant.equals("client") ? "other-client" : "worker-owned";
                f.omitClient = variant.equals("missing");
                assertThatThrownBy(() -> f.adapter().current("owned").block(Duration.ofSeconds(5)))
                        .isInstanceOf(ResponseStatusException.class);
            }
        }
    }

    @Test
    void expiredOrOverlongSignedAndOAuthLifetimesFailClosed() throws Exception {
        try (var f = new Fixture()) {
            f.expire = true;
            assertThatThrownBy(() -> f.adapter().current("owned").block(Duration.ofSeconds(5)))
                    .isInstanceOf(ResponseStatusException.class);
            f.expire = false;
            f.lifetime = 61;
            assertThatThrownBy(() -> f.adapter().current("owned").block(Duration.ofSeconds(5)))
                    .isInstanceOf(ResponseStatusException.class);
            f.lifetime = 30;
            f.responseLifetime = 61;
            assertThatThrownBy(() -> f.adapter().current("owned").block(Duration.ofSeconds(5)))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void tenantRegistrationIsolationRejectsReusedRegistrationAndUnregisteredTenantBeforeToken()
            throws Exception {
        try (var f = new Fixture()) {
            var adapter = f.adapter();
            assertDenied(() -> adapter.current("other").block(Duration.ofSeconds(5)), 403);
            assertThat(f.calls).hasValue(0);
            var repository =
                    new InMemoryReactiveClientRegistrationRepository(
                            f.registration("one", "worker-owned"));
            var config = new OAuth2ClientAutoConfiguration();
            var manager =
                    config.fireflyAuthorizedClientManager(
                            repository, config.fireflyAuthorizedClientService(repository));
            assertThatThrownBy(
                            () ->
                                    new OAuthManagedPaymentWorkerActorAdapter(
                                            manager,
                                            repository,
                                            t ->
                                                    reactor.core.publisher.Mono.error(
                                                            new JwtException("unused")),
                                            Map.of("owned", "one", "other", "one"),
                                            true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> {
                            assertThat(e.getStatusCode().value()).isEqualTo(status);
                            assertThat(e.getCause()).isNull();
                        });
    }
}
