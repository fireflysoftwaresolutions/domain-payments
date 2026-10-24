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
import static org.mockito.Mockito.*;

import io.fireflysoftware.domain.payments.core.managed.*;
import io.fireflysoftware.domain.payments.core.managed.impl.ManagedPaymentFlowServiceImpl;
import io.fireflysoftware.domain.payments.core.managed.ports.*;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.orchestration.core.scheduling.OrchestrationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.*;

class ManagedPaymentWorkerWiringTest {
    ApplicationContextRunner context(
            ManagedPaymentCorePort core, OrchestrationScheduler scheduler) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ManagedPaymentWorkerWiring.class)
                .withConfiguration(
                        AutoConfigurations.of(ManagedPaymentWorkerActorAutoConfiguration.class))
                .withBean(ManagedPaymentCorePort.class, () -> core)
                .withBean(CommandBus.class, () -> mock(CommandBus.class))
                .withBean(OrchestrationScheduler.class, () -> scheduler);
    }

    @Test
    void disabledDefaultRegistersNoRecoveryOrScheduleAndNoSyntheticActor() {
        var core = mock(ManagedPaymentCorePort.class);
        var scheduler = new OrchestrationScheduler(1);
        try {
            context(core, scheduler)
                    .run(
                            c -> {
                                assertThat(c).doesNotHaveBean(ManagedPaymentRecoveryWorker.class);
                                assertThat(scheduler.activeTaskCount()).isZero();
                                assertThatThrownBy(
                                                () ->
                                                        c.getBean(
                                                                        ManagedPaymentWorkerActorPort
                                                                                .class)
                                                                .current("owned")
                                                                .block())
                                        .isInstanceOf(
                                                org.springframework.web.server
                                                        .ResponseStatusException.class);
                                verifyNoInteractions(core);
                            });
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void enabledWithoutRealAuthorityCannotBorrowLegacySandboxIdentity() throws Exception {
        var core = mock(ManagedPaymentCorePort.class);
        var scheduler = new OrchestrationScheduler(1);
        try {
            context(core, scheduler)
                    .withPropertyValues(
                            "managed-payments.recovery.enabled=true",
                            "managed-payments.recovery.interval-seconds=1",
                            "managed-payments.recovery.registrations.owned=owned-worker")
                    .run(
                            c -> {
                                assertThat(c).hasSingleBean(ManagedPaymentRecoveryWorker.class);
                                assertThat(scheduler.activeTaskCount()).isEqualTo(1);
                                try {
                                    Thread.sleep(1200);
                                } catch (InterruptedException e) {
                                    throw new AssertionError(e);
                                }
                                verifyNoInteractions(core);
                            });
            assertThat(scheduler.activeTaskCount()).isZero();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void invalidConfiguredBoundsFailStartupRatherThanSilentlyChangingBatch() {
        var core = mock(ManagedPaymentCorePort.class);
        var scheduler = new OrchestrationScheduler(1);
        try {
            context(core, scheduler)
                    .withPropertyValues(
                            "managed-payments.recovery.enabled=true",
                            "managed-payments.recovery.batch-size=101",
                            "managed-payments.recovery.registrations.owned=owned-worker")
                    .run(c -> assertThat(c).hasFailed());
            verifyNoInteractions(core);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void nativeSchedulerKeepsAsyncOverlapGuardAndStopCancelsOnlyItsOwnTask() throws Exception {
        var core = mock(ManagedPaymentCorePort.class);
        var bus = mock(CommandBus.class);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        when(core.queue("owned", null, 25))
                .thenReturn(
                        Mono
                                .<io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts
                                                .RecoveryPage>
                                        never()
                                .doOnCancel(() -> cancelled.set(true)));
        var account = UUID.randomUUID();
        var p =
                org.fireflyframework.security.api.domain.SecurityPrincipal.builder()
                        .subject("actual-worker")
                        .tenantId("owned")
                        .scopes(
                                Set.of(
                                        "payments:read",
                                        "payments:recover",
                                        "payments:coordinate",
                                        "funds:read",
                                        "funds:reconcile"))
                        .claims(
                                Map.of(
                                        "exp",
                                        Instant.now().plusSeconds(30),
                                        "funds_accounts",
                                        List.of(account.toString())))
                        .build();
        var worker =
                new ManagedPaymentRecoveryWorker(
                        core,
                        bus,
                        t ->
                                Mono.just(
                                        new ManagedPaymentContext.Call(
                                                p, "private-transient", Set.of("domain"))),
                        List.of("owned"),
                        25,
                        2,
                        Duration.ofSeconds(5));
        var scheduler = new OrchestrationScheduler(1);
        scheduler.scheduleWithFixedDelay("unrelated", () -> {}, 50000, 50000);
        try {
            var lifecycle = new ManagedPaymentWorkerWiring.RecoverySchedule(worker, scheduler, 1);
            lifecycle.start();
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < until
                    && org.mockito.Mockito.mockingDetails(core).getInvocations().isEmpty())
                Thread.sleep(20);
            Thread.sleep(1100);
            verify(core, times(1)).queue("owned", null, 25);
            lifecycle.stop();
            assertThat(cancelled).isTrue();
            assertThat(scheduler.activeTaskCount()).isEqualTo(1);
            verifyNoInteractions(bus);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void actualManagedWiringPublishesServiceInterfaceWithStableBeanIdentity() {
        var core = mock(ManagedPaymentCorePort.class);
        var funds = mock(ManagedFundsPort.class);
        new ApplicationContextRunner()
                .withUserConfiguration(ManagedPaymentsWiring.class)
                .withConfiguration(
                        AutoConfigurations.of(
                                org.fireflyframework.orchestration.config
                                        .OrchestrationAutoConfiguration.class,
                                org.fireflyframework.orchestration.config.SagaAutoConfiguration
                                        .class))
                .withBean(ManagedPaymentCorePort.class, () -> core)
                .withBean(ManagedFundsPort.class, () -> funds)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ManagedPaymentFlowService.class);
                            assertThat(context.getBean("managedPaymentFlow"))
                                    .isInstanceOf(ManagedPaymentFlowServiceImpl.class);
                            assertThat(context.getBean(ManagedPaymentFlowService.class))
                                    .isSameAs(context.getBean("managedPaymentFlow"));
                            verifyNoInteractions(core, funds);
                        });
    }

    @Test
    void cqrsRequestsRetainTypedCommandActionAndReadIdentity() throws Exception {
        var principal =
                org.fireflyframework.security.api.domain.SecurityPrincipal.builder()
                        .issuer("owned")
                        .subject("actor")
                        .tenantId("owned")
                        .scopes(Set.of("payments:read"))
                        .claims(
                                Map.of(
                                        "exp",
                                        Instant.now().plusSeconds(30),
                                        "funds_accounts",
                                        List.of(UUID.randomUUID().toString())))
                        .build();
        var call = new ManagedPaymentContext.Call(principal, "private-never-log", Set.of("domain"));
        for (UUID identity : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            java.lang.reflect.Method method;
            org.fireflyframework.cqrs.context.ExecutionContext context;
            try {
                method =
                        ManagedPaymentsController.class.getDeclaredMethod(
                                "context",
                                ManagedPaymentContext.Call.class,
                                String.class,
                                UUID.class);
                method.setAccessible(true);
                context =
                        (org.fireflyframework.cqrs.context.ExecutionContext)
                                method.invoke(null, call, "owned", identity);
            } catch (NoSuchMethodException absent) {
                method =
                        ManagedPaymentsController.class.getDeclaredMethod(
                                "context", ManagedPaymentContext.Call.class, String.class);
                method.setAccessible(true);
                context =
                        (org.fireflyframework.cqrs.context.ExecutionContext)
                                method.invoke(null, call, "owned");
            }
            assertThat(context.getRequestId()).isEqualTo(identity.toString());
            assertThat(context.getTenantId()).isEqualTo("owned");
            assertThat(context.getUserId()).isEqualTo("actor");
            assertThat(context.getProperties()).isEmpty();
        }
    }
}
