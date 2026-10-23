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

import static io.fireflysoftware.domain.payments.core.managed.v1.ManagedPaymentContext.*;

import io.fireflysoftware.domain.payments.core.managed.v1.ports.ManagedPaymentWorkerActorPort;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

/**
 * Uses native client credentials and verifies the returned signed service identity on every use.
 */
public final class OAuthManagedPaymentWorkerActorAdapter implements ManagedPaymentWorkerActorPort {
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final ReactiveClientRegistrationRepository registrations;
    private final ReactiveJwtDecoder decoder;
    private final Map<String, String> tenantRegistrations;
    private final boolean loopback;

    public OAuthManagedPaymentWorkerActorAdapter(
            ReactiveOAuth2AuthorizedClientManager manager,
            ReactiveClientRegistrationRepository registrations,
            ReactiveJwtDecoder decoder,
            Map<String, String> tenantRegistrations,
            boolean loopback) {
        this.manager = Objects.requireNonNull(manager);
        this.registrations = Objects.requireNonNull(registrations);
        this.decoder = Objects.requireNonNull(decoder);
        this.loopback = loopback;
        if (tenantRegistrations == null
                || tenantRegistrations.isEmpty()
                || tenantRegistrations.size() > 25
                || tenantRegistrations.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey() == null
                                                || !e.getKey().matches("[A-Za-z0-9_-]{1,64}")
                                                || e.getValue() == null
                                                || !e.getValue().matches("[A-Za-z0-9_.-]{1,64}"))
                || new HashSet<>(tenantRegistrations.values()).size() != tenantRegistrations.size())
            throw new IllegalArgumentException("DEDICATED_TENANT_WORKER_REGISTRATIONS_REQUIRED");
        this.tenantRegistrations = Map.copyOf(tenantRegistrations);
    }

    public Mono<Call> current(String tenant) {
        return Mono.defer(
                        () -> {
                            String registration = tenantRegistrations.get(tenant);
                            if (registration == null)
                                return Mono.error(fail(403, "WORKER_TENANT_REGISTRATION_DENIED"));
                            return registrations
                                    .findByRegistrationId(registration)
                                    .switchIfEmpty(
                                            Mono.error(
                                                    fail(
                                                            503,
                                                            "CONFIGURED_WORKER_REGISTRATION_REQUIRED")))
                                    .flatMap(
                                            config -> {
                                                if (!AuthorizationGrantType.CLIENT_CREDENTIALS
                                                                .equals(
                                                                        config
                                                                                .getAuthorizationGrantType())
                                                        || config.getClientId() == null
                                                        || config.getClientId().isBlank()
                                                        || !SdkManagedPaymentCoreAdapter.valid(
                                                                config.getProviderDetails()
                                                                        .getTokenUri(),
                                                                loopback))
                                                    return Mono.error(
                                                            fail(
                                                                    503,
                                                                    "TRUSTED_WORKER_CLIENT_CREDENTIALS_REQUIRED"));
                                                return manager.authorize(
                                                                OAuth2AuthorizeRequest
                                                                        .withClientRegistrationId(
                                                                                registration)
                                                                        .principal(
                                                                                "firefly-managed-recovery/"
                                                                                        + tenant)
                                                                        .build())
                                                        .switchIfEmpty(
                                                                Mono.error(
                                                                        fail(
                                                                                503,
                                                                                "CURRENT_WORKER_AUTHORIZED_CLIENT_REQUIRED")))
                                                        .flatMap(
                                                                client -> {
                                                                    if (!registration.equals(
                                                                                    client.getClientRegistration()
                                                                                            .getRegistrationId())
                                                                            || !config.getClientId()
                                                                                    .equals(
                                                                                            client.getClientRegistration()
                                                                                                    .getClientId()))
                                                                        return Mono.error(
                                                                                fail(
                                                                                        403,
                                                                                        "WORKER_CLIENT_BINDING_DENIED"));
                                                                    var token =
                                                                            client.getAccessToken();
                                                                    if (token == null
                                                                            || token.getTokenValue()
                                                                                    == null
                                                                            || token.getTokenValue()
                                                                                    .isBlank()
                                                                            || token.getTokenValue()
                                                                                            .length()
                                                                                    > 16384
                                                                            || !finite(
                                                                                    token
                                                                                            .getIssuedAt(),
                                                                                    token
                                                                                            .getExpiresAt()))
                                                                        return Mono.error(
                                                                                fail(
                                                                                        401,
                                                                                        "FINITE_WORKER_OAUTH_TOKEN_REQUIRED"));
                                                                    return decoder.decode(
                                                                                    token
                                                                                            .getTokenValue())
                                                                            .map(
                                                                                    jwt ->
                                                                                            bound(
                                                                                                    tenant,
                                                                                                    config
                                                                                                            .getClientId(),
                                                                                                    jwt));
                                                                });
                                            });
                        })
                .timeout(Duration.ofSeconds(4))
                .onErrorMap(
                        e -> {
                            int status =
                                    e instanceof ResponseStatusException r
                                            ? r.getStatusCode().value()
                                            : e instanceof JwtException ? 401 : 503;
                            return fail(
                                    Set.of(400, 401, 403, 503).contains(status) ? status : 503,
                                    "TRUSTED_WORKER_AUTHORITY_" + status);
                        });
    }

    private static Call bound(String tenant, String expectedClient, Jwt jwt) {
        if (!finite(jwt.getIssuedAt(), jwt.getExpiresAt()))
            throw fail(401, "FINITE_SIGNED_WORKER_TOKEN_REQUIRED");
        Object client = jwt.getClaims().get("client_id"), party = jwt.getClaims().get("azp");
        if (client == null && party == null
                || client != null && !expectedClient.equals(client)
                || party != null && !expectedClient.equals(party))
            throw fail(403, "SIGNED_WORKER_CLIENT_BINDING_DENIED");
        String scope = jwt.getClaimAsString("scope");
        var principal =
                SecurityPrincipal.builder()
                        .issuer(jwt.getIssuer().toString())
                        .subject(jwt.getSubject())
                        .tenantId(jwt.getClaimAsString("tenant_id"))
                        .scopes(
                                scope == null
                                        ? Set.of()
                                        : new HashSet<>(Arrays.asList(scope.split(" "))))
                        .claims(jwt.getClaims())
                        .build();
        var call = new Call(principal, jwt.getTokenValue(), Set.copyOf(jwt.getAudience()));
        for (String required :
                List.of(
                        "payments:recover",
                        "payments:read",
                        "payments:coordinate",
                        "funds:read",
                        "funds:reconcile")) require(call, tenant, required);
        return call;
    }

    private static boolean finite(Instant issued, Instant expiry) {
        Instant now = Instant.now();
        return issued != null
                && expiry != null
                && !issued.isAfter(now.plusSeconds(1))
                && expiry.isAfter(now)
                && expiry.isAfter(issued)
                && Duration.between(issued, expiry).compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
