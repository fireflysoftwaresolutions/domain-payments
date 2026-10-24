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

import static io.fireflysoftware.domain.payments.core.managed.ManagedPaymentContext.*;

import io.fireflysoftware.core.banking.ledger.sdk.api.LedgerFundsApi;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.ledger.sdk.model.*;
import io.fireflysoftware.domain.payments.core.managed.ports.*;
import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class SdkManagedFundsAdapter implements ManagedFundsPort {
    private final PaymentDelegationPort delegation;
    private final String base, audience;
    private final boolean loopback;

    public SdkManagedFundsAdapter(
            PaymentDelegationPort delegation, String base, String audience, boolean loopback) {
        this.delegation = delegation;
        this.base = base;
        this.audience = audience;
        this.loopback = loopback;
    }

    Mono<LedgerFundsApi> client(String tenant, View v, Set<String> scopes) {
        return Mono.defer(
                () -> {
                    if (!SdkManagedPaymentCoreAdapter.valid(base, loopback))
                        return Mono.error(fail(503, "CONFIGURED_LEDGER_PARTICIPANT_REQUIRED"));
                    return delegation
                            .token(
                                    audience,
                                    tenant,
                                    Set.of(v.sourceAccountId(), v.destinationAccountId()),
                                    scopes)
                            .timeout(Duration.ofSeconds(4))
                            .switchIfEmpty(
                                    Mono.error(fail(503, "CURRENT_LEDGER_DELEGATION_REQUIRED")))
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
                                        return new LedgerFundsApi(api);
                                    });
                });
    }

    static Reserve intent(View v) {
        return new Reserve()
                .operationId(v.paymentId())
                .sourceAccountId(v.sourceAccountId())
                .destinationAccountId(v.destinationAccountId())
                .amount(new BigDecimal(v.amount()))
                .currency(v.currency())
                .ttlSeconds(v.ttlSeconds())
                .notAfterEpochMillis(v.notAfterEpochMillis());
    }

    public Mono<ReservationObservation> reserve(String t, EffectClaim c) {
        return SdkManagedPaymentCoreAdapter.safe(
                () -> {
                    if (c.kind() != EffectKind.RESERVE
                            || !Objects.equals(c.participantActionId(), c.paymentId()))
                        return Mono.error(inconsistent());
                    return client(t, c.payment(), Set.of("funds:reserve"))
                            .flatMap(api -> api.reserveFunds(t, intent(c.payment()), null))
                            .map(r -> reservation(c.payment(), r));
                });
    }

    public Mono<ReservationObservation> act(String t, EffectClaim c) {
        return SdkManagedPaymentCoreAdapter.safe(
                () -> {
                    if (c.kind() != EffectKind.COMMIT && c.kind() != EffectKind.RELEASE
                            || c.payment().reservationId() == null
                            || c.participantActionId() == null) return Mono.error(inconsistent());
                    return action(
                            t,
                            c,
                            c.kind() == EffectKind.COMMIT
                                    ? Command.ActionEnum.COMMIT
                                    : Command.ActionEnum.RELEASE);
                });
    }

    Mono<ReservationObservation> action(String t, EffectClaim c, Command.ActionEnum action) {
        String scope =
                switch (action) {
                    case COMMIT -> "funds:commit";
                    case RELEASE -> "funds:release";
                    case EXPIRE -> "funds:expire";
                    default -> throw inconsistent();
                };
        return client(t, c.payment(), Set.of(scope))
                .flatMap(
                        api ->
                                api.actOnFundsReservation(
                                        t,
                                        c.payment().reservationId(),
                                        new Command()
                                                .actionId(c.participantActionId())
                                                .action(action),
                                        null))
                .map(r -> reservation(c.payment(), r));
    }

    public Mono<ReservationObservation> observe(String t, EffectClaim c) {
        return SdkManagedPaymentCoreAdapter.safe(
                () -> {
                    if (c.kind() != EffectKind.OBSERVE) return Mono.error(inconsistent());
                    return reconciliation(t, c.payment())
                            .flatMap(
                                    observation -> {
                                        if (!"HELD".equals(observation.state()))
                                            return Mono.just(observation);
                                        if (c.payment().reservationId() == null
                                                || !Objects.equals(
                                                        c.payment().reservationId(),
                                                        observation.reservationId()))
                                            return Mono.error(inconsistent());
                                        return action(t, c, Command.ActionEnum.EXPIRE)
                                                .onErrorResume(
                                                        error ->
                                                                error
                                                                                        instanceof
                                                                                        org
                                                                                                        .springframework
                                                                                                        .web
                                                                                                        .reactive
                                                                                                        .function
                                                                                                        .client
                                                                                                        .WebClientResponseException
                                                                                                e
                                                                                && e.getStatusCode()
                                                                                                .value()
                                                                                        == 409
                                                                        ? reconciliation(
                                                                                t, c.payment())
                                                                        : Mono.error(error));
                                    });
                });
    }

    Mono<ReservationObservation> reconciliation(String tenant, View v) {
        return client(tenant, v, Set.of("funds:read", "funds:reconcile"))
                .flatMap(api -> api.reconcileFundsReservation(tenant, intent(v), null))
                .map(
                        receipt -> {
                            var echoed = receipt.getIntent();
                            if (receipt.getOutcome() == null
                                    || receipt.getObservedAt() == null
                                    || !Objects.equals(
                                            receipt.getNotAfterEpochMillis(),
                                            v.notAfterEpochMillis())
                                    || echoed == null
                                    || !Objects.equals(echoed.getOperationId(), v.paymentId())
                                    || !Objects.equals(
                                            echoed.getSourceAccountId(), v.sourceAccountId())
                                    || !Objects.equals(
                                            echoed.getDestinationAccountId(),
                                            v.destinationAccountId())
                                    || echoed.getAmount() == null
                                    || echoed.getAmount().compareTo(new BigDecimal(v.amount())) != 0
                                    || !Objects.equals(echoed.getCurrency(), v.currency())
                                    || !Objects.equals(echoed.getTtlSeconds(), v.ttlSeconds())
                                    || !Objects.equals(
                                            echoed.getNotAfterEpochMillis(),
                                            v.notAfterEpochMillis())) throw inconsistent();
                            if ("ABSENT_CLOSED".equals(receipt.getOutcome().getValue())) {
                                if (receipt.getReceiptId() == null
                                        || receipt.getReservation() != null
                                        || v.reservationId() != null
                                        || receipt.getObservedAt()
                                                        .toInstant(ZoneOffset.UTC)
                                                        .toEpochMilli()
                                                < v.notAfterEpochMillis()
                                        || v.absenceReceiptId() != null
                                                && !v.absenceReceiptId()
                                                        .equals(receipt.getReceiptId()))
                                    throw inconsistent();
                                return new ReservationObservation(
                                        v.paymentId(),
                                        null,
                                        "ABSENT_CLOSED",
                                        null,
                                        null,
                                        receipt.getReceiptId());
                            }
                            if (!"FOUND".equals(receipt.getOutcome().getValue())
                                    || receipt.getReceiptId() != null
                                    || v.absenceReceiptId() != null) throw inconsistent();
                            return reservation(v, receipt.getReservation());
                        });
    }

    static ReservationObservation reservation(View v, Reservation r) {
        if (r == null
                || r.getReservationId() == null
                || r.getState() == null
                || r.getPostingId() == null
                || r.getVersion() == null
                || r.getVersion() < 0
                || r.getExpiresAt() == null
                || r.getExpiresAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                        > v.notAfterEpochMillis()
                || !Objects.equals(r.getOperationId(), v.paymentId())
                || !Objects.equals(r.getSourceAccountId(), v.sourceAccountId())
                || !Objects.equals(r.getDestinationAccountId(), v.destinationAccountId())
                || r.getAmount() == null
                || r.getAmount().compareTo(new BigDecimal(v.amount())) != 0
                || !Objects.equals(r.getCurrency(), v.currency())
                || v.reservationId() != null && !v.reservationId().equals(r.getReservationId())
                || v.postingId() != null && !v.postingId().equals(r.getPostingId())
                || v.reversalId() != null && !v.reversalId().equals(r.getReversalId())
                || v.reservationExpiresAt() != null
                        && !v.reservationExpiresAt()
                                .equals(r.getExpiresAt().toInstant(ZoneOffset.UTC)))
            throw inconsistent();
        String state = r.getState().getValue();
        if ("REVERSED".equals(state)
                        && (r.getReversalId() == null || r.getReversalId().equals(r.getPostingId()))
                || !"REVERSED".equals(state) && r.getReversalId() != null
                || v.ledgerVersion() != null
                        && (r.getVersion() < v.ledgerVersion()
                                || r.getVersion().equals(v.ledgerVersion())
                                        && !Objects.equals(v.ledgerState(), state))
                || v.ledgerState() != null
                        && !v.ledgerState().equals("HELD")
                        && !v.ledgerState().equals(state)
                        && !(v.ledgerState().equals("COMMITTED") && state.equals("REVERSED")))
            throw inconsistent();
        return new ReservationObservation(
                r.getOperationId(),
                r.getReservationId(),
                state,
                r.getPostingId(),
                r.getReversalId(),
                null);
    }

    static org.springframework.web.server.ResponseStatusException inconsistent() {
        return fail(409, "EXACT_RETAINED_FUNDS_INTENT_REQUIRED");
    }
}
