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

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.context.ExecutionContext;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

class ManagedPaymentRecoveryWorkerTest {
    final ManagedPaymentCorePort core = mock(ManagedPaymentCorePort.class);
    final CommandBus bus = mock(CommandBus.class);
    final UUID id = UUID.randomUUID(),
            action = UUID.randomUUID(),
            a = UUID.randomUUID(),
            b = UUID.randomUUID(),
            effect = UUID.randomUUID();

    ManagedPaymentContext.Call actor(boolean expired) {
        return new ManagedPaymentContext.Call(
                SecurityPrincipal.builder()
                        .issuer("fixture")
                        .subject("actual-worker-client")
                        .tenantId("owned")
                        .scopes(
                                Set.of(
                                        "payments:recover",
                                        "payments:read",
                                        "payments:coordinate",
                                        "funds:read",
                                        "funds:reconcile",
                                        "funds:reserve",
                                        "funds:commit",
                                        "funds:release",
                                        "funds:expire"))
                        .claims(
                                Map.of(
                                        "iat",
                                        Instant.now().minusSeconds(1),
                                        "exp",
                                        Instant.now().plusSeconds(expired ? -1 : 30),
                                        "funds_accounts",
                                        List.of(a.toString(), b.toString())))
                        .build(),
                "private-worker-transient",
                Set.of("domain", "core", "ledger"));
    }

    View view(State state, long version, UUID pending, String reason) {
        return view(id, state, version, pending, reason);
    }

    View view(UUID payment, State state, long version, UUID pending, String reason) {
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
                System.currentTimeMillis() + 30000,
                "original-customer",
                state,
                version,
                null,
                null,
                null,
                null,
                null,
                null,
                pending,
                reason,
                pending == null ? "HELD" : null,
                null);
    }

    ManagedPaymentRecoveryWorker worker(ManagedPaymentWorkerActorPort actors) {
        return new ManagedPaymentRecoveryWorker(
                core, bus, actors, List.of("owned"), 25, 2, Duration.ofSeconds(5));
    }

    ManagedPaymentWorkerActorPort valid() {
        return t -> Mono.just(actor(false));
    }

    @Test
    void missingRealActorCannotDiscoverOrMutate() {
        var result =
                worker(t -> Mono.error(ManagedPaymentContext.fail(503, "UNCONFIGURED")))
                        .runOnce()
                        .block();
        assertThat(result.scanned()).isZero();
        assertThat(result.deferred()).isEqualTo(1);
        verifyNoInteractions(core, bus);
    }

    @Test
    void queueOnlyExactVersionAndDeterministicNativeRecoveryKeepRealWorkerContext() {
        var before = view(State.RESERVING, 2, effect, null);
        var after = view(State.RESERVED, 5, null, null);
        when(core.queue("owned", null, 25))
                .thenReturn(Mono.just(new RecoveryPage(List.of(before), null)));
        var commands = new ArrayList<RecoverManagedPaymentCommand>();
        when(bus.send(any(RecoverManagedPaymentCommand.class), any(ExecutionContext.class)))
                .thenAnswer(
                        i -> {
                            var command = (RecoverManagedPaymentCommand) i.getArgument(0);
                            var context = (ExecutionContext) i.getArgument(1);
                            commands.add(command);
                            assertThat(context.getUserId()).isEqualTo("actual-worker-client");
                            assertThat(context.getTenantId()).isEqualTo("owned");
                            assertThat(context.getProperties()).isEmpty();
                            assertThat(context.toString())
                                    .doesNotContain("private-worker-transient");
                            return ManagedPaymentContext.current()
                                    .map(
                                            call -> {
                                                assertThat(call.principal().subject())
                                                        .isEqualTo("actual-worker-client");
                                                return after;
                                            });
                        });
        var worker = worker(valid());
        assertThat(worker.runOnce().block().advanced()).isEqualTo(1);
        assertThat(worker.runOnce().block().advanced()).isEqualTo(1);
        assertThat(commands).hasSize(2);
        assertThat(commands.getFirst().paymentId()).isEqualTo(id);
        assertThat(commands.getFirst().input().expectedVersion()).isEqualTo(2);
        assertThat(commands.getFirst().input().actionId())
                .isEqualTo(commands.getLast().input().actionId());
        verify(core, times(2)).queue("owned", null, 25);
        verifyNoMoreInteractions(core);
    }

    @Test
    void failureReturnsToSchedulerWithoutImmediateRecoveryOrClaimLoops() {
        var v = view(State.RESERVING, 2, effect, null);
        when(core.queue("owned", null, 25))
                .thenReturn(Mono.just(new RecoveryPage(List.of(v), null)));
        when(bus.send(any(RecoverManagedPaymentCommand.class), any(ExecutionContext.class)))
                .thenReturn(
                        Mono.just(view(State.RECOVERY_REQUIRED, 5, effect, "LEDGER_UNAVAILABLE")));
        var result = worker(valid()).runOnce().block();
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.deferred()).isEqualTo(1);
        verify(bus, times(1))
                .send(any(RecoverManagedPaymentCommand.class), any(ExecutionContext.class));
        verify(core).queue("owned", null, 25);
        verifyNoMoreInteractions(core, bus);
    }

    @Test
    void staleObservedVersionDefersWithoutFetchingAnotherVersion() {
        when(core.queue("owned", null, 25))
                .thenReturn(
                        Mono.just(
                                new RecoveryPage(
                                        List.of(view(State.RESERVING, 2, effect, null)), null)));
        when(bus.send(any(RecoverManagedPaymentCommand.class), any(ExecutionContext.class)))
                .thenReturn(Mono.error(ManagedPaymentContext.fail(409, "CURRENT_VERSION_CHANGED")));
        var result = worker(valid()).runOnce().block();
        assertThat(result.deferred()).isEqualTo(1);
        verify(core).queue("owned", null, 25);
        verifyNoMoreInteractions(core);
        verify(bus, times(1))
                .send(any(RecoverManagedPaymentCommand.class), any(ExecutionContext.class));
    }

    @Test
    void expiredActorBetweenDiscoveryAndCommandCannotContinue() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(core.queue("owned", null, 25))
                .thenReturn(
                        Mono.just(
                                new RecoveryPage(
                                        List.of(view(State.RESERVING, 2, effect, null)), null)));
        var result = worker(t -> Mono.just(actor(calls.getAndIncrement() > 0))).runOnce().block();
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.deferred()).isEqualTo(1);
        verifyNoInteractions(bus);
    }

    @Test
    void exhaustedOwnerReceiptCannotBecomeAutomaticManualOverride() {
        when(core.queue("owned", null, 25))
                .thenReturn(
                        Mono.just(
                                new RecoveryPage(
                                        List.of(
                                                view(
                                                        State.RECOVERY_REQUIRED,
                                                        15,
                                                        effect,
                                                        "RETRY_EXHAUSTED")),
                                        null)));
        var result = worker(valid()).runOnce().block();
        assertThat(result.exhausted()).isEqualTo(1);
        verifyNoInteractions(bus);
    }

    @Test
    void asynchronousOverlapAndCancellationReleaseRunGuard() {
        when(core.queue("owned", null, 25)).thenReturn(Mono.never());
        var worker = worker(valid());
        var active = worker.runOnce().subscribe();
        assertThat(worker.runOnce().block().scanned()).isZero();
        verify(core, times(1)).queue("owned", null, 25);
        active.dispose();
        when(core.queue("owned", null, 25))
                .thenReturn(Mono.just(new RecoveryPage(List.of(), null)));
        assertThat(worker.runOnce().block().scanned()).isZero();
        verify(core, times(2)).queue("owned", null, 25);
    }

    @Test
    void thirdQueuedItemWaitsForAnActiveNativeCommandToComplete() throws Exception {
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var rows = ids.stream().map(p -> view(p, State.RECEIVED, 0, effect, null)).toList();
        when(core.queue("owned", null, 25)).thenReturn(Mono.just(new RecoveryPage(rows, null)));
        var active =
                new java.util.concurrent.ConcurrentHashMap<
                        UUID, reactor.core.publisher.Sinks.One<View>>();
        when(bus.<View>send(any(RecoverManagedPaymentCommand.class), any()))
                .thenAnswer(
                        invocation -> {
                            var command =
                                    invocation.getArgument(0, RecoverManagedPaymentCommand.class);
                            var sink = reactor.core.publisher.Sinks.<View>one();
                            active.put(command.paymentId(), sink);
                            return sink.asMono();
                        });
        var result = worker(valid()).runOnce().toFuture();
        assertThat(active.keySet()).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
        verify(bus, times(2)).send(any(RecoverManagedPaymentCommand.class), any());
        active.get(ids.get(0)).tryEmitValue(view(ids.get(0), State.RESERVED, 3, null, null));
        assertThat(active.keySet()).containsExactlyInAnyOrderElementsOf(ids);
        verify(bus, times(3)).send(any(RecoverManagedPaymentCommand.class), any());
        for (UUID payment : ids.subList(1, 3))
            active.get(payment).tryEmitValue(view(payment, State.RESERVED, 3, null, null));
        assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(new ManagedPaymentRecoveryWorker.RecoveryRun(3, 3, 0, 0));
        verify(core, times(1)).queue("owned", null, 25);
    }

    @Test
    void invalidBoundsFailBeforeAnyAuthorityOrDiscovery() {
        assertThatThrownBy(
                        () ->
                                new ManagedPaymentRecoveryWorker(
                                        core,
                                        bus,
                                        valid(),
                                        List.of("owned"),
                                        101,
                                        2,
                                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ManagedPaymentRecoveryWorker(
                                        core,
                                        bus,
                                        valid(),
                                        List.of("owned"),
                                        25,
                                        3,
                                        Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(core, bus);
    }
}
