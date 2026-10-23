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

package io.fireflysoftware.domain.payments.core.managed.v1;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.domain.payments.core.managed.v1.ports.*;
import io.fireflysoftware.domain.payments.managed.v1.ManagedPaymentContracts.*;

import org.fireflyframework.cqrs.context.ExecutionContext;
import org.fireflyframework.orchestration.config.OrchestrationAutoConfiguration;
import org.fireflyframework.orchestration.config.SagaAutoConfiguration;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

class ManagedPaymentFlowTest {
    static final Duration WAIT = Duration.ofSeconds(5);

    static final class Fixture {
        final ManagedPaymentCorePort core = mock(ManagedPaymentCorePort.class);
        final ManagedFundsPort funds = mock(ManagedFundsPort.class);
        final UUID payment = UUID.randomUUID(),
                action = UUID.randomUUID(),
                source = UUID.randomUUID(),
                destination = UUID.randomUUID(),
                effect = UUID.randomUUID(),
                reservation = UUID.randomUUID(),
                posting = UUID.randomUUID();
        final long deadline = System.currentTimeMillis() + 30000;
        final ExecutionContext nativeContext =
                ExecutionContext.builder()
                        .withTenantId("owned")
                        .withUserId("owned-actor")
                        .withRequestId(UUID.randomUUID().toString())
                        .build();

        View view(State s, long v, UUID pending) {
            return new View(
                    payment,
                    action,
                    "owned",
                    source,
                    destination,
                    "1.00",
                    "USD",
                    2,
                    "policy-v1",
                    30,
                    null,
                    deadline,
                    "owned-actor",
                    s,
                    v,
                    s == State.RESERVING ? null : reservation,
                    s == State.RESERVING ? null : posting,
                    null,
                    null,
                    s == State.RESERVING ? null : 0L,
                    null,
                    pending,
                    null,
                    s == State.RESERVED ? "HELD" : null,
                    null);
        }

        Create create() {
            return new Create(action, source, destination, new BigDecimal("1.00"), "USD", 30, null);
        }

        ManagedPaymentContext.Call actor() {
            return new ManagedPaymentContext.Call(
                    SecurityPrincipal.builder()
                            .issuer("owned-test")
                            .subject("owned-actor")
                            .tenantId("owned")
                            .scopes(
                                    Set.of(
                                            "payments:create",
                                            "payments:read",
                                            "payments:confirm",
                                            "payments:cancel",
                                            "payments:recover",
                                            "payments:coordinate",
                                            "funds:read",
                                            "funds:reserve",
                                            "funds:commit",
                                            "funds:release",
                                            "funds:reconcile",
                                            "funds:expire"))
                            .claims(
                                    Map.of(
                                            "exp",
                                            Instant.now().plusSeconds(60),
                                            "funds_accounts",
                                            List.of(source.toString(), destination.toString())))
                            .build(),
                    "SECRET-TRANSIENT-BEARER",
                    Set.of("domain", "core", "ledger"));
        }

        <T> T authorized(Mono<T> operation) {
            return operation.contextWrite(c -> ManagedPaymentContext.with(c, actor())).block(WAIT);
        }

        EffectClaim claim(EffectKind kind, View view) {
            return new EffectClaim(
                    payment,
                    effect,
                    kind,
                    kind == EffectKind.RESERVE ? payment : effect,
                    1,
                    Instant.now().plusSeconds(30),
                    view.version(),
                    view);
        }
    }

    void runtime(
            java.util.function.BiConsumer<Fixture, org.springframework.context.ApplicationContext>
                    test) {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                OrchestrationAutoConfiguration.class, SagaAutoConfiguration.class))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            test.accept(new Fixture(), c);
                        });
    }

    ManagedPaymentFlow flow(Fixture f, org.springframework.context.ApplicationContext c) {
        return new ManagedPaymentFlow(c.getBean(SagaEngine.class), f.core, f.funds);
    }

    @Test
    void createUsesActualInjectedSagaAndCoreReceiptWithoutSessionMap() {
        runtime(
                (f, c) -> {
                    var accepted = f.view(State.RESERVING, 0, f.effect);
                    var claimed = f.view(State.RESERVING, 1, f.effect);
                    var held = f.view(State.RESERVED, 2, null);
                    var claim = f.claim(EffectKind.RESERVE, claimed);
                    when(f.core.create(eq("owned"), any()))
                            .thenReturn(Mono.just(new Acceptance(accepted, false)));
                    when(f.core.claim(eq("owned"), eq(f.payment), any()))
                            .thenReturn(Mono.just(claim));
                    when(f.funds.reserve(eq("owned"), eq(claim)))
                            .thenReturn(
                                    Mono.just(
                                            new ManagedFundsPort.ReservationObservation(
                                                    f.payment,
                                                    f.reservation,
                                                    "HELD",
                                                    f.posting,
                                                    null,
                                                    null)));
                    when(f.core.acknowledge(eq("owned"), eq(f.payment), any()))
                            .thenReturn(Mono.just(held));
                    when(f.core.read("owned", f.payment)).thenReturn(Mono.just(held));
                    var result =
                            f.authorized(flow(f, c).create("owned", f.create(), f.nativeContext));
                    assertThat(result.replayed()).isFalse();
                    assertThat(result.payment().state()).isEqualTo(State.RESERVED);
                    verify(f.funds).reserve("owned", claim);
                    assertThat(claim.payment().notAfterEpochMillis()).isEqualTo(f.deadline);
                    var states =
                            c.getBean(ExecutionPersistenceProvider.class)
                                    .findByPattern(ExecutionPattern.SAGA)
                                    .collectList()
                                    .block(WAIT);
                    assertThat(states).hasSize(1);
                    assertThat(states.getFirst().stepResults())
                            .containsKeys("accept", "claim", "apply", "acknowledge", "read");
                    assertThat(states.toString())
                            .doesNotContain(
                                    "SECRET-TRANSIENT-BEARER", "Authorization", "challengeValue");
                });
    }

    @Test
    void noTransientActorCannotBorrowNativeContext() {
        runtime(
                (f, c) -> {
                    assertThatThrownBy(
                                    () ->
                                            flow(f, c)
                                                    .create("owned", f.create(), f.nativeContext)
                                                    .block(WAIT))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
                    verifyNoInteractions(f.core, f.funds);
                });
    }

    @Test
    void missingNativeContextIsDeniedBeforePorts() {
        runtime(
                (f, c) -> {
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c).create("owned", f.create(), null)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
                    verifyNoInteractions(f.core, f.funds);
                });
    }

    @Test
    void mismatchedNativeActorCannotBorrowTrustedBearer() {
        runtime(
                (f, c) -> {
                    var forged =
                            ExecutionContext.builder()
                                    .withTenantId("owned")
                                    .withUserId("other")
                                    .withRequestId(UUID.randomUUID().toString())
                                    .build();
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c).create("owned", f.create(), forged)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
                    verifyNoInteractions(f.core, f.funds);
                });
    }

    @Test
    void failedCommitNeverAutomaticallyReleasesAndKeepsStableParticipant() {
        runtime(
                (f, c) -> {
                    var committing = f.view(State.COMMITTING, 4, f.effect);
                    var claimed = f.view(State.COMMITTING, 5, f.effect);
                    var uncertain = f.view(State.RECOVERY_REQUIRED, 6, f.effect);
                    var claim = f.claim(EffectKind.COMMIT, claimed);
                    when(f.core.decide(eq("owned"), eq(f.payment), any()))
                            .thenReturn(Mono.just(committing));
                    when(f.core.claim(eq("owned"), eq(f.payment), any()))
                            .thenReturn(Mono.just(claim));
                    when(f.funds.act(eq("owned"), eq(claim)))
                            .thenReturn(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "SECRET-TRANSIENT-BEARER provider payload")));
                    when(f.core.acknowledge(eq("owned"), eq(f.payment), any()))
                            .thenReturn(
                                    Mono.error(
                                            new IllegalStateException(
                                                    "SECRET-TRANSIENT-BEARER observation lost")));
                    when(f.core.failure(eq("owned"), eq(f.payment), any()))
                            .thenReturn(Mono.just(uncertain));
                    when(f.core.read("owned", f.payment)).thenReturn(Mono.just(uncertain));
                    var result =
                            f.authorized(
                                    flow(f, c)
                                            .decide(
                                                    "owned",
                                                    f.payment,
                                                    new Decision(
                                                            UUID.randomUUID(),
                                                            DecisionKind.CONFIRM,
                                                            3L),
                                                    f.nativeContext));
                    assertThat(result.state()).isEqualTo(State.RECOVERY_REQUIRED);
                    verify(f.funds).act("owned", claim);
                    verify(f.funds, never()).reserve(anyString(), any());
                    verify(f.funds, never()).observe(anyString(), any());
                    var states =
                            c.getBean(ExecutionPersistenceProvider.class)
                                    .findByPattern(ExecutionPattern.SAGA)
                                    .collectList()
                                    .block(WAIT);
                    assertThat(states.toString())
                            .doesNotContain(
                                    "SECRET-TRANSIENT-BEARER",
                                    "provider payload",
                                    "observation lost");
                });
    }

    @Test
    void currentDecisionConflictIsNotReplacedByAReadSuccess() {
        runtime(
                (f, c) -> {
                    when(f.core.read("owned", f.payment))
                            .thenReturn(Mono.just(f.view(State.RESERVED, 3, null)));
                    when(f.core.decide(eq("owned"), eq(f.payment), any()))
                            .thenReturn(
                                    Mono.error(
                                            ManagedPaymentContext.fail(
                                                    409, "ORIGINAL_DECISION_CONFLICT")));
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c)
                                                            .decide(
                                                                    "owned",
                                                                    f.payment,
                                                                    new Decision(
                                                                            UUID.randomUUID(),
                                                                            DecisionKind.CANCEL,
                                                                            3L),
                                                                    f.nativeContext)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
                    verify(f.core, never()).claim(anyString(), any(), any());
                    verifyNoInteractions(f.funds);
                });
    }

    @Test
    void mismatchedCoreCreateReceiptCannotDispatchDifferentImmutableIntent() {
        runtime(
                (f, c) -> {
                    var original = f.view(State.RESERVING, 0, f.effect);
                    var wrong =
                            new View(
                                    original.paymentId(),
                                    original.createActionId(),
                                    original.tenant(),
                                    original.sourceAccountId(),
                                    original.destinationAccountId(),
                                    "2.00",
                                    original.currency(),
                                    original.currencyScale(),
                                    original.currencyPolicy(),
                                    original.ttlSeconds(),
                                    original.customerNotAfterEpochMillis(),
                                    original.notAfterEpochMillis(),
                                    original.initiator(),
                                    original.state(),
                                    original.version(),
                                    original.reservationId(),
                                    original.postingId(),
                                    original.reversalId(),
                                    original.absenceReceiptId(),
                                    original.ledgerVersion(),
                                    original.observedAt(),
                                    original.pendingEffectId(),
                                    original.recoveryCode(),
                                    original.ledgerState(),
                                    original.reservationExpiresAt());
                    when(f.core.create(eq("owned"), any()))
                            .thenReturn(Mono.just(new Acceptance(wrong, false)));
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c)
                                                            .create(
                                                                    "owned",
                                                                    f.create(),
                                                                    f.nativeContext)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
                    verify(f.core, never()).claim(anyString(), any(), any());
                    verifyNoInteractions(f.funds);
                });
    }

    @Test
    void mismatchedClaimReceiptCannotDispatchDifferentOriginalTtl() {
        runtime(
                (f, c) -> {
                    var accepted = f.view(State.RESERVING, 0, f.effect);
                    var original = f.view(State.RESERVING, 1, f.effect);
                    var wrong =
                            new View(
                                    original.paymentId(),
                                    original.createActionId(),
                                    original.tenant(),
                                    original.sourceAccountId(),
                                    original.destinationAccountId(),
                                    original.amount(),
                                    original.currency(),
                                    original.currencyScale(),
                                    original.currencyPolicy(),
                                    31,
                                    original.customerNotAfterEpochMillis(),
                                    original.notAfterEpochMillis(),
                                    original.initiator(),
                                    original.state(),
                                    original.version(),
                                    original.reservationId(),
                                    original.postingId(),
                                    original.reversalId(),
                                    original.absenceReceiptId(),
                                    original.ledgerVersion(),
                                    original.observedAt(),
                                    original.pendingEffectId(),
                                    original.recoveryCode(),
                                    original.ledgerState(),
                                    original.reservationExpiresAt());
                    when(f.core.create(eq("owned"), any()))
                            .thenReturn(Mono.just(new Acceptance(accepted, false)));
                    when(f.core.claim(eq("owned"), eq(f.payment), any()))
                            .thenReturn(Mono.just(f.claim(EffectKind.RESERVE, wrong)));
                    when(f.core.read("owned", f.payment)).thenReturn(Mono.just(accepted));
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c)
                                                            .create(
                                                                    "owned",
                                                                    f.create(),
                                                                    f.nativeContext)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
                    verifyNoInteractions(f.funds);
                });
    }

    @Test
    void historyRetainsOriginalCompletionAndDistinctReversalWithCurrentPairAuthority() {
        runtime(
                (f, c) -> {
                    var v = f.view(State.REVERSED, 7, null);
                    var reversal = UUID.randomUUID();
                    var completed =
                            new HistoryEvent(
                                    5,
                                    "LEDGER_COMMITTED",
                                    "original-actor",
                                    Instant.now(),
                                    f.action,
                                    f.effect,
                                    State.COMPLETED,
                                    f.posting,
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
                                    f.posting,
                                    reversal);
                    when(f.core.read("owned", f.payment)).thenReturn(Mono.just(v));
                    when(f.core.history("owned", f.payment, -1, 25))
                            .thenReturn(reactor.core.publisher.Flux.just(completed, reversed));
                    var page =
                            f.authorized(
                                    flow(f, c)
                                            .history("owned", f.payment, -1, 25, f.nativeContext));
                    assertThat(page.items()).containsExactly(completed, reversed);
                    verifyNoInteractions(f.funds);
                });
    }

    @Test
    void historyRequiresCurrentExactPairBeforeFetchingEvents() {
        runtime(
                (f, c) -> {
                    when(f.core.read("owned", f.payment))
                            .thenReturn(Mono.just(f.view(State.RESERVED, 2, null)));
                    var principal =
                            SecurityPrincipal.builder()
                                    .issuer("owned-test")
                                    .subject("owned-actor")
                                    .tenantId("owned")
                                    .scopes(Set.of("payments:read"))
                                    .claims(
                                            Map.of(
                                                    "exp",
                                                    Instant.now().plusSeconds(60),
                                                    "funds_accounts",
                                                    List.of(f.source.toString())))
                                    .build();
                    var call =
                            new ManagedPaymentContext.Call(
                                    principal, "synthetic-current", Set.of("core"));
                    assertThatThrownBy(
                                    () ->
                                            flow(f, c)
                                                    .history(
                                                            "owned",
                                                            f.payment,
                                                            -1,
                                                            25,
                                                            f.nativeContext)
                                                    .contextWrite(
                                                            ctx ->
                                                                    ManagedPaymentContext.with(
                                                                            ctx, call))
                                                    .block(WAIT))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
                    verify(f.core, never()).history(anyString(), any(), anyLong(), anyInt());
                    verifyNoInteractions(f.funds);
                });
    }

    @Test
    void historyBoundsRejectBeforeAnyCoreRead() {
        runtime(
                (f, c) -> {
                    for (var limits :
                            List.of(new long[] {-2, 25}, new long[] {-1, 0}, new long[] {-1, 101}))
                        assertThatThrownBy(
                                        () ->
                                                f.authorized(
                                                        flow(f, c)
                                                                .history(
                                                                        "owned",
                                                                        f.payment,
                                                                        limits[0],
                                                                        (int) limits[1],
                                                                        f.nativeContext)))
                                .isInstanceOfSatisfying(
                                        ResponseStatusException.class,
                                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
                    verifyNoInteractions(f.core, f.funds);
                });
    }

    @Test
    void oversizedOrDisorderedOwnerHistoryCannotBecomeAnUnboundedOrReorderedTimeline() {
        runtime(
                (f, c) -> {
                    when(f.core.read("owned", f.payment))
                            .thenReturn(Mono.just(f.view(State.RESERVED, 2, null)));
                    var seen = new java.util.concurrent.atomic.AtomicInteger();
                    when(f.core.history("owned", f.payment, -1, 1))
                            .thenReturn(
                                    reactor.core.publisher.Flux.range(0, 1000)
                                            .map(
                                                    v -> {
                                                        seen.incrementAndGet();
                                                        return new HistoryEvent(
                                                                v,
                                                                "OWNER_EVENT",
                                                                "owner",
                                                                Instant.now(),
                                                                null,
                                                                null,
                                                                State.RESERVED,
                                                                null,
                                                                null);
                                                    }));
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c)
                                                            .history(
                                                                    "owned",
                                                                    f.payment,
                                                                    -1,
                                                                    1,
                                                                    f.nativeContext)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
                    assertThat(seen.get()).isEqualTo(2);
                    when(f.core.history("owned", f.payment, -1, 25))
                            .thenReturn(
                                    reactor.core.publisher.Flux.just(
                                            new HistoryEvent(
                                                    2,
                                                    "OWNER_EVENT",
                                                    "owner",
                                                    Instant.now(),
                                                    null,
                                                    null,
                                                    State.RESERVED,
                                                    null,
                                                    null),
                                            new HistoryEvent(
                                                    1,
                                                    "OWNER_EVENT",
                                                    "owner",
                                                    Instant.now(),
                                                    null,
                                                    null,
                                                    State.RESERVED,
                                                    null,
                                                    null)));
                    assertThatThrownBy(
                                    () ->
                                            f.authorized(
                                                    flow(f, c)
                                                            .history(
                                                                    "owned",
                                                                    f.payment,
                                                                    -1,
                                                                    25,
                                                                    f.nativeContext)))
                            .isInstanceOfSatisfying(
                                    ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
                    verifyNoInteractions(f.funds);
                });
    }
}
