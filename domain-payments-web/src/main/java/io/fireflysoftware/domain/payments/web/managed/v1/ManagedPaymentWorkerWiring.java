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

package io.fireflysoftware.domain.payments.web.managed.v1;

import io.fireflysoftware.domain.payments.core.managed.v1.*;
import io.fireflysoftware.domain.payments.core.managed.v1.ports.*;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.orchestration.core.scheduling.OrchestrationScheduler;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;

import reactor.core.Disposable;
import reactor.core.Disposables;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ManagedPaymentWorkerWiring.RecoveryProperties.class)
public class ManagedPaymentWorkerWiring {
    @Bean
    @ConditionalOnProperty(name = "managed-payments.recovery.enabled", havingValue = "true")
    ManagedPaymentRecoveryWorker managedRecoveryWorker(
            ManagedPaymentCorePort core,
            CommandBus commands,
            ManagedPaymentWorkerActorPort actors,
            RecoveryProperties p) {
        return new ManagedPaymentRecoveryWorker(
                core,
                commands,
                actors,
                new ArrayList<>(p.getRegistrations().keySet()),
                p.getBatchSize(),
                p.getConcurrency(),
                Duration.ofSeconds(p.getMaxRunSeconds()));
    }

    @Bean
    @ConditionalOnProperty(name = "managed-payments.recovery.enabled", havingValue = "true")
    RecoverySchedule managedRecoverySchedule(
            ManagedPaymentRecoveryWorker worker,
            OrchestrationScheduler scheduler,
            RecoveryProperties p) {
        return new RecoverySchedule(worker, scheduler, p.getIntervalSeconds());
    }

    @ConfigurationProperties("managed-payments.recovery")
    public static class RecoveryProperties {
        private boolean enabled;
        private int intervalSeconds = 5, batchSize = 25, concurrency = 2, maxRunSeconds = 60;
        private String actorMode = "unconfigured";
        private Map<String, String> registrations = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            enabled = v;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int v) {
            intervalSeconds = v;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int v) {
            batchSize = v;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int v) {
            concurrency = v;
        }

        public int getMaxRunSeconds() {
            return maxRunSeconds;
        }

        public void setMaxRunSeconds(int v) {
            maxRunSeconds = v;
        }

        public String getActorMode() {
            return actorMode;
        }

        public void setActorMode(String v) {
            actorMode = v;
        }

        public Map<String, String> getRegistrations() {
            return registrations;
        }

        public void setRegistrations(Map<String, String> v) {
            registrations = new LinkedHashMap<>(v);
        }
    }

    public static final class RecoverySchedule implements SmartLifecycle {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(RecoverySchedule.class);
        private static final String TASK = "managed-payments:recovery";
        private final ManagedPaymentRecoveryWorker worker;
        private final OrchestrationScheduler scheduler;
        private final int interval;
        private final Set<Disposable.Swap> active = ConcurrentHashMap.newKeySet();
        private volatile boolean running;

        public RecoverySchedule(
                ManagedPaymentRecoveryWorker worker,
                OrchestrationScheduler scheduler,
                int interval) {
            this.worker = Objects.requireNonNull(worker);
            this.scheduler = Objects.requireNonNull(scheduler);
            if (interval < 1 || interval > 60)
                throw new IllegalArgumentException("BOUNDED_RECOVERY_INTERVAL_REQUIRED");
            this.interval = interval;
        }

        public synchronized void start() {
            if (running) return;
            running = true;
            scheduler.scheduleWithFixedDelay(TASK, this::tick, interval * 1000L, interval * 1000L);
        }

        private void tick() {
            if (!running) return;
            var slot = Disposables.swap();
            active.add(slot);
            slot.update(
                    worker.runOnce()
                            .doFinally(signal -> active.remove(slot))
                            .subscribe(
                                    result -> {
                                        if (result.deferred() > 0 || result.exhausted() > 0)
                                            LOG.warn(
                                                    "Managed recovery run deferred={} exhausted={}",
                                                    result.deferred(),
                                                    result.exhausted());
                                    },
                                    error ->
                                            LOG.error(
                                                    "Managed recovery run failed; retained work"
                                                            + " requires another authorized"
                                                            + " observation")));
            if (!running) {
                slot.dispose();
                active.remove(slot);
            }
        }

        public synchronized void stop() {
            running = false;
            scheduler.cancel(TASK);
            active.forEach(Disposable::dispose);
            active.clear();
        }

        public boolean isRunning() {
            return running;
        }
    }
}
