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

package io.fireflysoftware.domain.payments.core.managed.impl;

import static io.fireflysoftware.domain.payments.core.managed.ManagedPaymentContext.*;

import io.fireflysoftware.domain.payments.core.managed.*;
import io.fireflysoftware.domain.payments.core.managed.ports.*;
import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;

import org.fireflyframework.cqrs.context.ExecutionContext;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public class ManagedPaymentFlowServiceImpl implements ManagedPaymentFlowService {
    private final SagaEngine engine;
    private final ManagedPaymentCorePort core;
    private final ManagedFundsPort funds;

    private record Work(View payment, EffectClaim claim, FailureCode failure) {}

    public ManagedPaymentFlowServiceImpl(
            SagaEngine engine, ManagedPaymentCorePort core, ManagedFundsPort funds) {
        this.engine = Objects.requireNonNull(engine);
        this.core = Objects.requireNonNull(core);
        this.funds = Objects.requireNonNull(funds);
    }

    static Mono<Call> bound(
            String tenant, ExecutionContext context, String scope, UUID... accounts) {
        return current()
                .map(
                        call -> {
                            if (context == null)
                                throw fail(401, "NATIVE_PAYMENT_EXECUTION_CONTEXT_REQUIRED");
                            require(call, tenant, scope, accounts);
                            if (!Objects.equals(context.getTenantId(), tenant)
                                    || !Objects.equals(
                                            context.getUserId(), call.principal().subject()))
                                throw fail(403, "NATIVE_PAYMENT_ACTOR_MISMATCH");
                            if (!context.getProperties().isEmpty()
                                    || context.getRequestId() == null)
                                throw fail(400, "BOUNDED_NATIVE_PAYMENT_CONTEXT_REQUIRED");
                            try {
                                UUID.fromString(context.getRequestId());
                            } catch (RuntimeException error) {
                                throw fail(400, "NATIVE_PAYMENT_REQUEST_ID_REQUIRED");
                            }
                            return call;
                        });
    }

    static void coordinator(Call call, String tenant, View payment) {
        for (String scope :
                new String[] {
                    "payments:coordinate", "payments:read", "funds:read", "funds:reconcile"
                })
            require(call, tenant, scope, payment.sourceAccountId(), payment.destinationAccountId());
    }

    static void effect(Call call, String tenant, EffectClaim claim) {
        View v = claim.payment();
        String original =
                switch (claim.kind()) {
                    case RESERVE -> "payments:create";
                    case COMMIT -> "payments:confirm";
                    case RELEASE -> "payments:cancel";
                    case OBSERVE -> "payments:recover";
                };
        require(
                call,
                tenant,
                call.principal().hasScope(original) ? original : "payments:recover",
                v.sourceAccountId(),
                v.destinationAccountId());
        require(
                call,
                tenant,
                switch (claim.kind()) {
                    case RESERVE -> "funds:reserve";
                    case COMMIT -> "funds:commit";
                    case RELEASE -> "funds:release";
                    case OBSERVE -> "funds:read";
                },
                v.sourceAccountId(),
                v.destinationAccountId());
    }

    public Mono<Acceptance> create(String tenant, Create input, ExecutionContext context) {
        return Mono.defer(
                () -> {
                    if (input == null
                            || input.actionId() == null
                            || input.sourceAccountId() == null
                            || input.destinationAccountId() == null)
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_CREATE_REQUIRED"));
                    return bound(
                                    tenant,
                                    context,
                                    "payments:create",
                                    input.sourceAccountId(),
                                    input.destinationAccountId())
                            .flatMap(
                                    call -> {
                                        for (String scope :
                                                new String[] {
                                                    "payments:read",
                                                    "payments:coordinate",
                                                    "funds:read",
                                                    "funds:reserve",
                                                    "funds:reconcile"
                                                })
                                            require(
                                                    call,
                                                    tenant,
                                                    scope,
                                                    input.sourceAccountId(),
                                                    input.destinationAccountId());
                                        return execute(
                                                "ManagedInternalPaymentCreate",
                                                tenant,
                                                context,
                                                input,
                                                () ->
                                                        core.create(tenant, input)
                                                                .map(
                                                                        a -> {
                                                                            exactCreate(
                                                                                    tenant,
                                                                                    input,
                                                                                    a.payment());
                                                                            return a;
                                                                        }));
                                    });
                });
    }

    public Mono<View> decide(
            String tenant, UUID payment, Decision input, ExecutionContext context) {
        return Mono.defer(
                () -> {
                    if (payment == null
                            || input == null
                            || input.kind() == null
                            || input.actionId() == null)
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_DECISION_REQUIRED"));
                    String scope =
                            input.kind() == DecisionKind.CONFIRM
                                    ? "payments:confirm"
                                    : "payments:cancel";
                    return bound(tenant, context, scope)
                            .flatMap(
                                    call ->
                                            read(tenant, payment, context)
                                                    .flatMap(
                                                            v -> {
                                                                coordinator(call, tenant, v);
                                                                require(
                                                                        call,
                                                                        tenant,
                                                                        scope,
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId());
                                                                require(
                                                                        call,
                                                                        tenant,
                                                                        input.kind()
                                                                                        == DecisionKind
                                                                                                .CONFIRM
                                                                                ? "funds:commit"
                                                                                : "funds:release",
                                                                        v.sourceAccountId(),
                                                                        v.destinationAccountId());
                                                                return execute(
                                                                                "ManagedInternalPaymentDecision",
                                                                                tenant,
                                                                                context,
                                                                                input,
                                                                                () ->
                                                                                        core.decide(
                                                                                                        tenant,
                                                                                                        payment,
                                                                                                        input)
                                                                                                .map(
                                                                                                        current -> {
                                                                                                            sameIntent(
                                                                                                                    v,
                                                                                                                    current);
                                                                                                            return new Acceptance(
                                                                                                                    current,
                                                                                                                    true);
                                                                                                        }))
                                                                        .map(Acceptance::payment);
                                                            }));
                });
    }

    public Mono<View> recover(
            String tenant, UUID payment, Recovery input, ExecutionContext context) {
        return Mono.defer(
                () -> {
                    if (payment == null || input == null || input.actionId() == null)
                        return Mono.error(fail(400, "EXACT_MANAGED_PAYMENT_RECOVERY_REQUIRED"));
                    return bound(tenant, context, "payments:recover")
                            .flatMap(
                                    call ->
                                            read(tenant, payment, context)
                                                    .flatMap(
                                                            v -> {
                                                                coordinator(call, tenant, v);
                                                                if (v.pendingEffectId() == null
                                                                        && "HELD"
                                                                                .equals(
                                                                                        v
                                                                                                .ledgerState()))
                                                                    require(
                                                                            call,
                                                                            tenant,
                                                                            "funds:expire",
                                                                            v.sourceAccountId(),
                                                                            v
                                                                                    .destinationAccountId());
                                                                return execute(
                                                                                "ManagedInternalPaymentRecovery",
                                                                                tenant,
                                                                                context,
                                                                                input,
                                                                                () ->
                                                                                        core.recover(
                                                                                                        tenant,
                                                                                                        payment,
                                                                                                        input)
                                                                                                .map(
                                                                                                        current -> {
                                                                                                            sameIntent(
                                                                                                                    v,
                                                                                                                    current);
                                                                                                            return new Acceptance(
                                                                                                                    current,
                                                                                                                    true);
                                                                                                        }))
                                                                        .map(Acceptance::payment);
                                                            }));
                });
    }

    public Mono<View> read(String tenant, UUID payment, ExecutionContext context) {
        return bound(tenant, context, "payments:read")
                .flatMap(
                        call ->
                                safe(() -> core.read(tenant, payment))
                                        .map(
                                                v -> {
                                                    if (!Objects.equals(v.paymentId(), payment)
                                                            || !Objects.equals(v.tenant(), tenant))
                                                        throw inconsistent();
                                                    require(
                                                            call,
                                                            tenant,
                                                            "payments:read",
                                                            v.sourceAccountId(),
                                                            v.destinationAccountId());
                                                    return v;
                                                }));
    }

    public Mono<HistoryPage> history(
            String tenant, UUID payment, long afterVersion, int limit, ExecutionContext context) {
        return Mono.defer(
                        () -> {
                            if (payment == null || afterVersion < -1 || limit < 1 || limit > 100)
                                return Mono.error(fail(400, "BOUNDED_PAYMENT_HISTORY_REQUIRED"));
                            return read(tenant, payment, context)
                                    .flatMap(
                                            v ->
                                                    bound(
                                                                    tenant,
                                                                    context,
                                                                    "payments:read",
                                                                    v.sourceAccountId(),
                                                                    v.destinationAccountId())
                                                            .flatMap(
                                                                    call ->
                                                                            core.history(
                                                                                            tenant,
                                                                                            payment,
                                                                                            afterVersion,
                                                                                            limit)
                                                                                    .take(
                                                                                            limit
                                                                                                    + 1L)
                                                                                    .collectList()
                                                                                    .map(
                                                                                            events -> {
                                                                                                if (events
                                                                                                                .size()
                                                                                                        > limit)
                                                                                                    throw inconsistent();
                                                                                                long
                                                                                                        previous =
                                                                                                                afterVersion;
                                                                                                for (HistoryEvent
                                                                                                        event :
                                                                                                                events) {
                                                                                                    if (event
                                                                                                                            .version()
                                                                                                                    <= previous
                                                                                                            || event
                                                                                                                            .version()
                                                                                                                    < 0
                                                                                                            || event
                                                                                                                            .type()
                                                                                                                    == null
                                                                                                            || event.type()
                                                                                                                    .isBlank()
                                                                                                            || event
                                                                                                                            .actor()
                                                                                                                    == null
                                                                                                            || event.actor()
                                                                                                                    .isBlank()
                                                                                                            || event
                                                                                                                            .recordedAt()
                                                                                                                    == null
                                                                                                            || event
                                                                                                                            .state()
                                                                                                                    == null)
                                                                                                        throw inconsistent();
                                                                                                    previous =
                                                                                                            event
                                                                                                                    .version();
                                                                                                }
                                                                                                return new HistoryPage(
                                                                                                        events);
                                                                                            })));
                        })
                .onErrorMap(ManagedPaymentFlowServiceImpl::sanitized);
    }

    private Mono<Acceptance> execute(
            String name,
            String tenant,
            ExecutionContext nativeContext,
            Object input,
            Supplier<Mono<Acceptance>> initial) {
        var definition =
                SagaBuilder.saga(name, 1)
                        .step("accept")
                        .handler(
                                (Object retainedInput,
                                        org.fireflyframework.orchestration.core.context
                                                        .ExecutionContext
                                                ctx) -> safe(initial))
                        .retry(0)
                        .add()
                        .step("claim")
                        .dependsOn("accept")
                        .handlerCtx(
                                ctx -> {
                                    View v = ctx.getResult("accept", Acceptance.class).payment();
                                    return bound(
                                                    tenant,
                                                    nativeContext,
                                                    "payments:coordinate",
                                                    v.sourceAccountId(),
                                                    v.destinationAccountId())
                                            .flatMap(
                                                    call -> {
                                                        coordinator(call, tenant, v);
                                                        if (v.pendingEffectId() == null)
                                                            return Mono.just(
                                                                    new Work(v, null, null));
                                                        return safe(() ->
                                                                        core.claim(
                                                                                tenant,
                                                                                v.paymentId(),
                                                                                new Claim(
                                                                                        UUID
                                                                                                .randomUUID(),
                                                                                        v
                                                                                                .version())))
                                                                .onErrorResume(
                                                                        error ->
                                                                                status(error) == 409
                                                                                                || status(
                                                                                                                error)
                                                                                                        == 503
                                                                                        ? Mono
                                                                                                .empty()
                                                                                        : Mono
                                                                                                .error(
                                                                                                        error))
                                                                .map(
                                                                        claim -> {
                                                                            sameIntent(
                                                                                    v,
                                                                                    claim
                                                                                            .payment());
                                                                            if (!Objects.equals(
                                                                                            claim
                                                                                                    .paymentId(),
                                                                                            v
                                                                                                    .paymentId())
                                                                                    || !Objects
                                                                                            .equals(
                                                                                                    claim
                                                                                                            .effectId(),
                                                                                                    v
                                                                                                            .pendingEffectId())
                                                                                    || !Objects
                                                                                            .equals(
                                                                                                    claim.payment()
                                                                                                            .pendingEffectId(),
                                                                                                    claim
                                                                                                            .effectId())
                                                                                    || claim
                                                                                                    .participantActionId()
                                                                                            == null
                                                                                    || claim.fence()
                                                                                            < 1
                                                                                    || claim
                                                                                                    .version()
                                                                                            != claim.payment()
                                                                                                    .version()
                                                                                    || claim
                                                                                                    .version()
                                                                                            < v
                                                                                                    .version())
                                                                                throw inconsistent();
                                                                            effect(
                                                                                    call, tenant,
                                                                                    claim);
                                                                            return new Work(
                                                                                    v, claim, null);
                                                                        })
                                                                .defaultIfEmpty(
                                                                        new Work(
                                                                                v,
                                                                                null,
                                                                                FailureCode
                                                                                        .TRANSPORT_UNAVAILABLE));
                                                    });
                                })
                        .retry(0)
                        .add()
                        .step("apply")
                        .dependsOn("claim")
                        .handlerCtx(
                                ctx ->
                                        apply(
                                                tenant,
                                                nativeContext,
                                                ctx.getResult("claim", Work.class)))
                        .retry(0)
                        .add()
                        .step("acknowledge")
                        .dependsOn("apply")
                        .handlerCtx(
                                ctx ->
                                        acknowledge(
                                                tenant,
                                                nativeContext,
                                                ctx.getResult("apply", Work.class)))
                        .retry(0)
                        .add()
                        .step("read")
                        .dependsOn("acknowledge")
                        .handlerCtx(
                                ctx ->
                                        read(
                                                        tenant,
                                                        ctx.getResult("accept", Acceptance.class)
                                                                .payment()
                                                                .paymentId(),
                                                        nativeContext)
                                                .map(
                                                        v -> {
                                                            sameIntent(
                                                                    ctx.getResult(
                                                                                    "accept",
                                                                                    Acceptance
                                                                                            .class)
                                                                            .payment(),
                                                                    v);
                                                            return v;
                                                        }))
                        .retry(0)
                        .add()
                        .build();
        var context =
                org.fireflyframework.orchestration.core.context.ExecutionContext.forSaga(
                        retainedAction(input).toString(), name);
        return engine.execute(definition, StepInputs.of("accept", input), context)
                .flatMap(
                        result -> {
                            if (!result.isSuccess())
                                return Mono.error(sanitized(result.error().orElse(null)));
                            var accepted = result.resultOf("accept", Acceptance.class).orElse(null);
                            var finalView = result.resultOf("read", View.class).orElse(null);
                            if (accepted == null || finalView == null)
                                return Mono.error(fail(503, "INCOMPLETE_MANAGED_PAYMENT_FLOW"));
                            return Mono.just(new Acceptance(finalView, accepted.replayed()));
                        })
                .onErrorMap(ManagedPaymentFlowServiceImpl::sanitized);
    }

    private static UUID retainedAction(Object input) {
        UUID action =
                switch (input) {
                    case Create value -> value.actionId();
                    case Decision value -> value.actionId();
                    case Recovery value -> value.actionId();
                    case null -> null;
                    default -> null;
                };
        if (action == null) throw fail(400, "EXACT_MANAGED_PAYMENT_ACTION_REQUIRED");
        return action;
    }

    private Mono<Work> apply(String tenant, ExecutionContext context, Work work) {
        if (work.claim() == null) return Mono.just(work);
        EffectClaim claim = work.claim();
        return bound(
                        tenant,
                        context,
                        "payments:coordinate",
                        work.payment().sourceAccountId(),
                        work.payment().destinationAccountId())
                .flatMap(
                        call -> {
                            coordinator(call, tenant, claim.payment());
                            effect(call, tenant, claim);
                            return safe(() ->
                                            switch (claim.kind()) {
                                                case RESERVE -> funds.reserve(tenant, claim);
                                                case COMMIT, RELEASE -> funds.act(tenant, claim);
                                                case OBSERVE -> funds.observe(tenant, claim);
                                            })
                                    .thenReturn(work)
                                    .onErrorResume(
                                            error -> {
                                                if (status(error) == 401 || status(error) == 403)
                                                    return recordFailure(
                                                                    tenant,
                                                                    claim,
                                                                    FailureCode.PEER_REJECTED)
                                                            .onErrorResume(ignored -> Mono.empty())
                                                            .then(Mono.error(error));
                                                return Mono.just(
                                                        new Work(
                                                                work.payment(),
                                                                claim,
                                                                status(error) == 400
                                                                                || status(error)
                                                                                        == 409
                                                                        ? FailureCode.PEER_REJECTED
                                                                        : FailureCode
                                                                                .TRANSPORT_UNAVAILABLE));
                                            });
                        });
    }

    private Mono<View> acknowledge(String tenant, ExecutionContext context, Work work) {
        if (work.claim() == null) return Mono.just(work.payment());
        EffectClaim claim = work.claim();
        return bound(
                        tenant,
                        context,
                        "payments:coordinate",
                        claim.payment().sourceAccountId(),
                        claim.payment().destinationAccountId())
                .flatMap(
                        call -> {
                            coordinator(call, tenant, claim.payment());
                            effect(call, tenant, claim);
                            return safe(() ->
                                            core.acknowledge(
                                                    tenant,
                                                    claim.paymentId(),
                                                    new Acknowledge(
                                                            claim.effectId(),
                                                            claim.fence(),
                                                            claim.version())))
                                    .map(
                                            v -> {
                                                sameIntent(claim.payment(), v);
                                                return v;
                                            })
                                    .onErrorResume(
                                            error -> {
                                                if (status(error) == 401 || status(error) == 403)
                                                    return Mono.error(error);
                                                return recordFailure(
                                                                tenant,
                                                                claim,
                                                                work.failure() == null
                                                                        ? FailureCode
                                                                                .OBSERVATION_UNAVAILABLE
                                                                        : work.failure())
                                                        .onErrorResume(
                                                                ignored ->
                                                                        safe(
                                                                                () ->
                                                                                        core.read(
                                                                                                tenant,
                                                                                                claim
                                                                                                        .paymentId())));
                                            });
                        });
    }

    private static void exactCreate(String tenant, Create in, View v) {
        if (v == null
                || v.paymentId() == null
                || !Objects.equals(tenant, v.tenant())
                || !Objects.equals(in.actionId(), v.createActionId())
                || !Objects.equals(in.sourceAccountId(), v.sourceAccountId())
                || !Objects.equals(in.destinationAccountId(), v.destinationAccountId())
                || in.amount() == null
                || in.currency() == null
                || new java.math.BigDecimal(v.amount()).compareTo(in.amount()) != 0
                || !in.currency().toUpperCase(java.util.Locale.ROOT).equals(v.currency())
                || in.ttlSeconds() != v.ttlSeconds()
                || !Objects.equals(
                        in.customerNotAfterEpochMillis(), v.customerNotAfterEpochMillis())
                || v.customerNotAfterEpochMillis() != null
                        && v.notAfterEpochMillis() > v.customerNotAfterEpochMillis())
            throw inconsistent();
    }

    private static void sameIntent(View expected, View actual) {
        if (expected == null
                || actual == null
                || !Objects.equals(expected.paymentId(), actual.paymentId())
                || !Objects.equals(expected.createActionId(), actual.createActionId())
                || !Objects.equals(expected.tenant(), actual.tenant())
                || !Objects.equals(expected.sourceAccountId(), actual.sourceAccountId())
                || !Objects.equals(expected.destinationAccountId(), actual.destinationAccountId())
                || !Objects.equals(expected.amount(), actual.amount())
                || !Objects.equals(expected.currency(), actual.currency())
                || expected.currencyScale() != actual.currencyScale()
                || !Objects.equals(expected.currencyPolicy(), actual.currencyPolicy())
                || expected.ttlSeconds() != actual.ttlSeconds()
                || !Objects.equals(
                        expected.customerNotAfterEpochMillis(),
                        actual.customerNotAfterEpochMillis())
                || expected.notAfterEpochMillis() != actual.notAfterEpochMillis()
                || !Objects.equals(expected.initiator(), actual.initiator())) throw inconsistent();
    }

    private static ResponseStatusException inconsistent() {
        return fail(409, "EXACT_PAYMENT_CORE_INTENT_REQUIRED");
    }

    private Mono<View> recordFailure(String tenant, EffectClaim claim, FailureCode code) {
        return safe(
                () ->
                        core.failure(
                                tenant,
                                claim.paymentId(),
                                new Failure(
                                        claim.effectId(), claim.fence(), claim.version(), code)));
    }

    private static <T> Mono<T> safe(Supplier<Mono<T>> call) {
        return Mono.defer(call)
                .switchIfEmpty(Mono.error(fail(503, "EMPTY_MANAGED_PAYMENT_PEER_RECEIPT")))
                .onErrorMap(ManagedPaymentFlowServiceImpl::sanitized);
    }

    private static int status(Throwable error) {
        return error instanceof ResponseStatusException e ? e.getStatusCode().value() : 503;
    }

    static ResponseStatusException sanitized(Throwable error) {
        int status = status(error);
        return fail(
                status == 400 || status == 401 || status == 403 || status == 404 || status == 409
                        ? status
                        : 503,
                "MANAGED_PAYMENT_OPERATION_" + status);
    }
}
