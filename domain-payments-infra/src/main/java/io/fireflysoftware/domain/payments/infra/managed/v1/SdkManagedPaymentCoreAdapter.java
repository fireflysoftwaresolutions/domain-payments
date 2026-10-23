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

import io.fireflysoftware.core.banking.payments.sdk.api.ManagedPaymentsApi;
import io.fireflysoftware.core.banking.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.domain.payments.core.managed.v1.ports.*;
import io.fireflysoftware.domain.payments.managed.v1.ManagedPaymentContracts.*;

import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.*;

import java.time.*;
import java.util.*;
import java.util.function.*;

public final class SdkManagedPaymentCoreAdapter implements ManagedPaymentCorePort {
    private final PaymentDelegationPort delegation;
    private final String base, audience;
    private final boolean loopback;

    public SdkManagedPaymentCoreAdapter(
            PaymentDelegationPort delegation, String base, String audience, boolean loopback) {
        this.delegation = delegation;
        this.base = base;
        this.audience = audience;
        this.loopback = loopback;
    }

    Mono<ManagedPaymentsApi> client(String tenant, Set<UUID> accounts, Set<String> scopes) {
        return Mono.defer(
                () -> {
                    if (!valid(base, loopback))
                        return Mono.error(fail(503, "CONFIGURED_PAYMENT_CORE_REQUIRED"));
                    return delegation
                            .token(audience, tenant, accounts, scopes)
                            .timeout(Duration.ofSeconds(4))
                            .switchIfEmpty(
                                    Mono.error(fail(503, "CURRENT_PAYMENT_DELEGATION_REQUIRED")))
                            .map(
                                    token -> {
                                        var web =
                                                ApiClient.buildWebClientBuilder()
                                                        .codecs(
                                                                c ->
                                                                        c.defaultCodecs()
                                                                                .maxInMemorySize(
                                                                                        1024
                                                                                                * 1024))
                                                        .build();
                                        var api = new ApiClient(web).setBasePath(base);
                                        api.addDefaultHeader("Authorization", "Bearer " + token);
                                        return new ManagedPaymentsApi(api);
                                    });
                });
    }

    Mono<ManagedPaymentsApi> existing(String tenant, String scope) {
        return current()
                .flatMap(
                        call -> {
                            require(call, tenant, scope);
                            var grants =
                                    ((List<?>) call.principal().claims().get("funds_accounts"))
                                            .stream()
                                                    .map(Object::toString)
                                                    .map(UUID::fromString)
                                                    .collect(java.util.stream.Collectors.toSet());
                            return client(tenant, grants, Set.of(scope));
                        });
    }

    public Mono<Acceptance> create(String t, Create in) {
        return safe(
                () ->
                        client(
                                        t,
                                        Set.of(in.sourceAccountId(), in.destinationAccountId()),
                                        Set.of("payments:create", "funds:read"))
                                .flatMap(
                                        api ->
                                                api.createManagedPaymentWithHttpInfo(
                                                        t,
                                                        new io.fireflysoftware.core.banking.payments
                                                                        .sdk.model.Create()
                                                                .actionId(in.actionId())
                                                                .sourceAccountId(
                                                                        in.sourceAccountId())
                                                                .destinationAccountId(
                                                                        in.destinationAccountId())
                                                                .amount(in.amount())
                                                                .currency(in.currency())
                                                                .ttlSeconds(in.ttlSeconds())
                                                                .customerNotAfterEpochMillis(
                                                                        in
                                                                                .customerNotAfterEpochMillis())))
                                .map(
                                        response -> {
                                            if (response.getStatusCode().value() != 200
                                                    && response.getStatusCode().value() != 201)
                                                throw fail(
                                                        503, "EXACT_PAYMENT_ACCEPTANCE_REQUIRED");
                                            return new Acceptance(
                                                    view(response.getBody()),
                                                    response.getStatusCode().value() == 200);
                                        }));
    }

    public Mono<View> read(String t, UUID id) {
        return safe(
                () ->
                        existing(t, "payments:read")
                                .flatMap(api -> api.getManagedPayment(t, id, null))
                                .map(SdkManagedPaymentCoreAdapter::view));
    }

    public Mono<EffectClaim> claim(String t, UUID id, Claim in) {
        return safe(
                () ->
                        existing(t, "payments:coordinate")
                                .flatMap(
                                        api ->
                                                api.claimManagedPaymentEffect(
                                                        t,
                                                        id,
                                                        new io.fireflysoftware.core.banking.payments
                                                                        .sdk.model.Claim()
                                                                .actionId(in.actionId())
                                                                .expectedVersion(
                                                                        in.expectedVersion())))
                                .map(
                                        c ->
                                                new EffectClaim(
                                                        c.getPaymentId(),
                                                        c.getEffectId(),
                                                        EffectKind.valueOf(c.getKind().getValue()),
                                                        c.getParticipantActionId(),
                                                        c.getFence(),
                                                        instant(c.getLeaseUntil()),
                                                        c.getVersion(),
                                                        view(c.getPayment()))));
    }

    public Mono<View> acknowledge(String t, UUID id, Acknowledge in) {
        return safe(
                () ->
                        existing(t, "payments:coordinate")
                                .flatMap(
                                        api ->
                                                api.acknowledgeManagedPaymentEffect(
                                                        t,
                                                        id,
                                                        new io.fireflysoftware.core.banking.payments
                                                                        .sdk.model.Acknowledge()
                                                                .effectId(in.effectId())
                                                                .fence(in.fence())
                                                                .expectedVersion(
                                                                        in.expectedVersion())))
                                .map(SdkManagedPaymentCoreAdapter::view));
    }

    public Mono<View> failure(String t, UUID id, Failure in) {
        return safe(
                () ->
                        existing(t, "payments:coordinate")
                                .flatMap(
                                        api ->
                                                api.recordManagedPaymentFailure(
                                                        t,
                                                        id,
                                                        new io.fireflysoftware.core.banking.payments
                                                                        .sdk.model.Failure()
                                                                .effectId(in.effectId())
                                                                .fence(in.fence())
                                                                .expectedVersion(
                                                                        in.expectedVersion())
                                                                .code(
                                                                        io.fireflysoftware.core
                                                                                .banking.payments
                                                                                .sdk.model.Failure
                                                                                .CodeEnum.fromValue(
                                                                                in.code().name()))))
                                .map(SdkManagedPaymentCoreAdapter::view));
    }

    public Mono<View> decide(String t, UUID id, Decision in) {
        String scope = in.kind() == DecisionKind.CONFIRM ? "payments:confirm" : "payments:cancel";
        return safe(
                () ->
                        existing(t, scope)
                                .flatMap(
                                        api ->
                                                api.decideManagedPayment(
                                                        t,
                                                        id,
                                                        new io.fireflysoftware.core.banking.payments
                                                                        .sdk.model.Decision()
                                                                .actionId(in.actionId())
                                                                .kind(
                                                                        io.fireflysoftware.core
                                                                                .banking.payments
                                                                                .sdk.model.Decision
                                                                                .KindEnum.fromValue(
                                                                                in.kind().name()))
                                                                .expectedVersion(
                                                                        in.expectedVersion())))
                                .map(SdkManagedPaymentCoreAdapter::view));
    }

    public Mono<View> recover(String t, UUID id, Recovery in) {
        return safe(
                () ->
                        existing(t, "payments:recover")
                                .flatMap(
                                        api ->
                                                api.recoverManagedPayment(
                                                        t,
                                                        id,
                                                        new io.fireflysoftware.core.banking.payments
                                                                        .sdk.model.Recovery()
                                                                .actionId(in.actionId())
                                                                .expectedVersion(
                                                                        in.expectedVersion())))
                                .map(SdkManagedPaymentCoreAdapter::view));
    }

    public Mono<RecoveryPage> queue(String t, UUID after, int limit) {
        return safe(
                () ->
                        existing(t, "payments:recover")
                                .flatMap(
                                        api ->
                                                api.managedPaymentRecoveryQueue(
                                                        t, after, limit, null))
                                .map(
                                        p ->
                                                new RecoveryPage(
                                                        p.getItems().stream()
                                                                .map(
                                                                        SdkManagedPaymentCoreAdapter
                                                                                ::view)
                                                                .toList(),
                                                        p.getNextId())));
    }

    public Flux<HistoryEvent> history(String t, UUID id, long after, int limit) {
        return existing(t, "payments:read")
                .flatMapMany(api -> api.managedPaymentHistory(t, id, after, limit, null))
                .map(
                        e ->
                                new HistoryEvent(
                                        e.getVersion(),
                                        e.getType(),
                                        e.getActor(),
                                        instant(e.getRecordedAt()),
                                        e.getActionId(),
                                        e.getEffectId(),
                                        State.valueOf(e.getState().getValue()),
                                        e.getPostingId(),
                                        e.getReversalId()))
                .timeout(Duration.ofSeconds(10))
                .onErrorMap(SdkManagedPaymentCoreAdapter::sanitize);
    }

    static View view(io.fireflysoftware.core.banking.payments.sdk.model.View v) {
        return new View(
                v.getPaymentId(),
                v.getCreateActionId(),
                v.getTenant(),
                v.getSourceAccountId(),
                v.getDestinationAccountId(),
                v.getAmount(),
                v.getCurrency(),
                v.getCurrencyScale(),
                v.getCurrencyPolicy(),
                v.getTtlSeconds(),
                v.getCustomerNotAfterEpochMillis(),
                v.getNotAfterEpochMillis(),
                v.getInitiator(),
                State.valueOf(v.getState().getValue()),
                v.getVersion(),
                v.getReservationId(),
                v.getPostingId(),
                v.getReversalId(),
                v.getAbsenceReceiptId(),
                v.getLedgerVersion(),
                instant(v.getObservedAt()),
                v.getPendingEffectId(),
                v.getRecoveryCode(),
                v.getLedgerState(),
                instant(v.getReservationExpiresAt()));
    }

    static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static <T> Mono<T> safe(Supplier<Mono<T>> call) {
        return Mono.defer(call)
                .switchIfEmpty(Mono.error(fail(503, "EMPTY_PAYMENT_CORE_RECEIPT")))
                .timeout(Duration.ofSeconds(15))
                .onErrorMap(SdkManagedPaymentCoreAdapter::sanitize);
    }

    static ResponseStatusException sanitize(Throwable e) {
        int code =
                e instanceof ResponseStatusException r
                        ? r.getStatusCode().value()
                        : e instanceof WebClientResponseException r
                                ? r.getStatusCode().value()
                                : 503;
        return fail(
                Set.of(400, 401, 403, 404, 409).contains(code) ? code : 503,
                "MANAGED_PAYMENT_PEER_" + code);
    }

    static boolean valid(String value, boolean loopback) {
        try {
            var u = java.net.URI.create(value);
            return u.getHost() != null
                    && u.getRawUserInfo() == null
                    && u.getRawQuery() == null
                    && u.getFragment() == null
                    && ("https".equals(u.getScheme())
                            || loopback
                                    && "http".equals(u.getScheme())
                                    && Set.of("127.0.0.1", "localhost", "::1", "[::1]")
                                            .contains(u.getHost()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
