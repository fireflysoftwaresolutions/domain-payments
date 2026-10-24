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

package io.fireflysoftware.domain.payments.core.managed;

import static io.fireflysoftware.domain.payments.core.managed.ManagedPaymentContext.*;

import io.fireflysoftware.domain.payments.core.managed.ports.*;
import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.context.ExecutionContext;

import reactor.core.publisher.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Bounded queue consumer. Core owns discovery eligibility, attempts, backoff, leases and effects.
 */
public final class ManagedPaymentRecoveryWorker {
    public record RecoveryRun(int scanned, int advanced, int deferred, int exhausted) {}

    private record Work(String tenant, View payment) {}

    private enum Outcome {
        ADVANCED,
        DEFERRED,
        EXHAUSTED
    }

    private final ManagedPaymentCorePort core;
    private final CommandBus commands;
    private final ManagedPaymentWorkerActorPort actors;
    private final List<String> tenants;
    private final int batchSize, concurrency;
    private final Duration maxRun;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger tenantStart = new AtomicInteger();

    public ManagedPaymentRecoveryWorker(
            ManagedPaymentCorePort core,
            CommandBus commands,
            ManagedPaymentWorkerActorPort actors,
            List<String> tenants,
            int batchSize,
            int concurrency,
            Duration maxRun) {
        this.core = Objects.requireNonNull(core);
        this.commands = Objects.requireNonNull(commands);
        this.actors = Objects.requireNonNull(actors);
        if (tenants == null
                || tenants.isEmpty()
                || tenants.size() > 25
                || new HashSet<>(tenants).size() != tenants.size()
                || tenants.stream().anyMatch(t -> t == null || !t.matches("[A-Za-z0-9_-]{1,64}"))
                || batchSize < 1
                || batchSize > 100
                || concurrency < 1
                || concurrency > 2
                || maxRun == null
                || maxRun.compareTo(Duration.ofSeconds(1)) < 0
                || maxRun.compareTo(Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("BOUNDED_MANAGED_RECOVERY_CONFIGURATION_REQUIRED");
        this.tenants = List.copyOf(tenants);
        this.batchSize = batchSize;
        this.concurrency = concurrency;
        this.maxRun = maxRun;
    }

    public Mono<RecoveryRun> runOnce() {
        return Mono.defer(
                () -> {
                    if (!running.compareAndSet(false, true))
                        return Mono.just(new RecoveryRun(0, 0, 0, 0));
                    var scanned = new AtomicInteger();
                    var advanced = new AtomicInteger();
                    var deferred = new AtomicInteger();
                    var exhausted = new AtomicInteger();
                    int first = Math.floorMod(tenantStart.getAndIncrement(), tenants.size());
                    var order = new ArrayList<String>();
                    for (int n = 0; n < tenants.size(); n++)
                        order.add(tenants.get((first + n) % tenants.size()));
                    return Flux.fromIterable(order)
                            .concatMap(
                                    t ->
                                            discover(t)
                                                    .onErrorResume(
                                                            e -> {
                                                                deferred.incrementAndGet();
                                                                return Flux.empty();
                                                            }),
                                    1)
                            .take(batchSize)
                            .flatMap(
                                    work -> {
                                        scanned.incrementAndGet();
                                        return recover(work)
                                                .onErrorReturn(Outcome.DEFERRED)
                                                .doOnNext(
                                                        o -> {
                                                            switch (o) {
                                                                case ADVANCED ->
                                                                        advanced.incrementAndGet();
                                                                case DEFERRED ->
                                                                        deferred.incrementAndGet();
                                                                case EXHAUSTED ->
                                                                        exhausted.incrementAndGet();
                                                            }
                                                        });
                                    },
                                    concurrency,
                                    1)
                            .then(
                                    Mono.fromSupplier(
                                            () ->
                                                    new RecoveryRun(
                                                            scanned.get(),
                                                            advanced.get(),
                                                            deferred.get(),
                                                            exhausted.get())))
                            .timeout(maxRun)
                            .onErrorResume(
                                    e -> {
                                        deferred.incrementAndGet();
                                        return Mono.just(
                                                new RecoveryRun(
                                                        scanned.get(),
                                                        advanced.get(),
                                                        deferred.get(),
                                                        exhausted.get()));
                                    })
                            .doFinally(signal -> running.set(false));
                });
    }

    private Flux<Work> discover(String tenant) {
        return actor(tenant)
                .flatMap(
                        call ->
                                core.queue(tenant, null, batchSize)
                                        .switchIfEmpty(
                                                Mono.error(
                                                        fail(
                                                                503,
                                                                "CURRENT_RECOVERY_QUEUE_REQUIRED")))
                                        .contextWrite(c -> with(c, call)))
                .flatMapMany(
                        page -> {
                            if (page.items() == null || page.items().size() > batchSize)
                                return Flux.error(fail(503, "BOUNDED_RECOVERY_QUEUE_REQUIRED"));
                            return Flux.fromIterable(page.items())
                                    .map(
                                            v -> {
                                                validate(tenant, v);
                                                return new Work(tenant, v);
                                            });
                        });
    }

    private Mono<Call> actor(String tenant) {
        return Mono.defer(() -> actors.current(tenant))
                .timeout(Duration.ofSeconds(4))
                .switchIfEmpty(Mono.error(fail(503, "TRUSTED_RECOVERY_ACTOR_REQUIRED")))
                .map(
                        call -> {
                            for (String scope :
                                    List.of(
                                            "payments:recover",
                                            "payments:read",
                                            "payments:coordinate",
                                            "funds:read",
                                            "funds:reconcile")) require(call, tenant, scope);
                            return call;
                        });
    }

    private Mono<Outcome> recover(Work work) {
        return Mono.defer(
                () -> {
                    View v = work.payment();
                    if ("RETRY_EXHAUSTED".equals(v.recoveryCode()))
                        return Mono.just(Outcome.EXHAUSTED);
                    if (v.pendingEffectId() == null && v.state() != State.RESERVED)
                        return Mono.just(Outcome.DEFERRED);
                    return actor(work.tenant())
                            .flatMap(
                                    call -> {
                                        for (String scope :
                                                List.of(
                                                        "payments:recover",
                                                        "payments:read",
                                                        "payments:coordinate",
                                                        "funds:read",
                                                        "funds:reconcile"))
                                            require(
                                                    call,
                                                    work.tenant(),
                                                    scope,
                                                    v.sourceAccountId(),
                                                    v.destinationAccountId());
                                        UUID action =
                                                UUID.nameUUIDFromBytes(
                                                        ("firefly-managed-recovery-v1|"
                                                                        + work.tenant()
                                                                        + "|"
                                                                        + v.paymentId()
                                                                        + "|"
                                                                        + v.version()
                                                                        + "|"
                                                                        + v.pendingEffectId())
                                                                .getBytes(StandardCharsets.UTF_8));
                                        var context =
                                                ExecutionContext.builder()
                                                        .withUserId(call.principal().subject())
                                                        .withTenantId(work.tenant())
                                                        .withRequestId(action.toString())
                                                        .build();
                                        return commands.send(
                                                        new RecoverManagedPaymentCommand(
                                                                work.tenant(),
                                                                v.paymentId(),
                                                                new Recovery(action, v.version())),
                                                        context)
                                                .switchIfEmpty(
                                                        Mono.error(
                                                                fail(
                                                                        503,
                                                                        "CURRENT_RECOVERY_RECEIPT_REQUIRED")))
                                                .map(
                                                        after -> {
                                                            validate(work.tenant(), after);
                                                            if (!Objects.equals(
                                                                            v.paymentId(),
                                                                            after.paymentId())
                                                                    || !Objects.equals(
                                                                            v.sourceAccountId(),
                                                                            after.sourceAccountId())
                                                                    || !Objects.equals(
                                                                            v
                                                                                    .destinationAccountId(),
                                                                            after
                                                                                    .destinationAccountId())
                                                                    || after.version()
                                                                            < v.version())
                                                                throw fail(
                                                                        409,
                                                                        "EXACT_RECOVERY_RECEIPT_REQUIRED");
                                                            if ("RETRY_EXHAUSTED"
                                                                    .equals(after.recoveryCode()))
                                                                return Outcome.EXHAUSTED;
                                                            return after.pendingEffectId() != null
                                                                            || after.state()
                                                                                    == State
                                                                                            .RECOVERY_REQUIRED
                                                                    ? Outcome.DEFERRED
                                                                    : Outcome.ADVANCED;
                                                        })
                                                .contextWrite(c -> with(c, call));
                                    });
                });
    }

    private static void validate(String tenant, View v) {
        if (v == null
                || !tenant.equals(v.tenant())
                || v.paymentId() == null
                || v.sourceAccountId() == null
                || v.destinationAccountId() == null
                || v.sourceAccountId().equals(v.destinationAccountId())
                || v.state() == null
                || v.version() < 0) throw fail(503, "EXACT_RECOVERY_OWNER_ROW_REQUIRED");
    }
}
