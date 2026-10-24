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

import io.fireflysoftware.domain.payments.core.managed.ManagedPaymentContext;

import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

class ManagedPaymentDelegationAdapterTest {
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    ManagedPaymentContext.Call actor(Set<String> audiences, Set<String> scopes, Instant exp) {
        return new ManagedPaymentContext.Call(
                SecurityPrincipal.builder()
                        .issuer("fixture")
                        .subject("real-actor")
                        .tenantId("owned")
                        .scopes(scopes)
                        .claims(
                                Map.of(
                                        "exp",
                                        exp,
                                        "funds_accounts",
                                        List.of(a.toString(), b.toString())))
                        .build(),
                "synthetic-transient",
                audiences);
    }

    <T> T run(Mono<T> m, ManagedPaymentContext.Call actor) {
        return m.contextWrite(c -> ManagedPaymentContext.with(c, actor))
                .block(Duration.ofSeconds(3));
    }

    @Test
    void delegationRequiresExplicitConfigurationAndExactCurrentAudienceScopesResources() {
        var actor =
                actor(Set.of("payments"), Set.of("payments:read"), Instant.now().plusSeconds(60));
        assertDenied(
                () ->
                        run(
                                new ManagedPaymentDelegationAdapter("unconfigured")
                                        .token(
                                                "payments",
                                                "owned",
                                                Set.of(a, b),
                                                Set.of("payments:read")),
                                actor),
                503);
        var adapter = new ManagedPaymentDelegationAdapter("validated-multi-audience");
        assertThat(
                        run(
                                adapter.token(
                                        "payments", "owned", Set.of(a, b), Set.of("payments:read")),
                                actor))
                .isEqualTo("synthetic-transient");
        assertDenied(
                () ->
                        run(
                                adapter.token(
                                        "ledger", "owned", Set.of(a, b), Set.of("payments:read")),
                                actor),
                403);
        assertDenied(
                () ->
                        run(
                                adapter.token(
                                        "payments",
                                        "owned",
                                        Set.of(a, UUID.randomUUID()),
                                        Set.of("payments:read")),
                                actor),
                403);
        assertDenied(
                () ->
                        run(
                                adapter.token(
                                        "payments",
                                        "owned",
                                        Set.of(a, b),
                                        Set.of("payments:confirm")),
                                actor),
                403);
        assertDenied(
                () ->
                        run(
                                adapter.token(
                                        "payments",
                                        "different",
                                        Set.of(a, b),
                                        Set.of("payments:read")),
                                actor),
                403);
        assertDenied(
                () ->
                        run(
                                adapter.token(
                                        "payments", "owned", Set.of(a, b), Set.of("payments:read")),
                                actor(
                                        Set.of("payments"),
                                        Set.of("payments:read"),
                                        Instant.now().minusSeconds(1))),
                401);
    }

    void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable c, int status) {
        assertThatThrownBy(c)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }
}
