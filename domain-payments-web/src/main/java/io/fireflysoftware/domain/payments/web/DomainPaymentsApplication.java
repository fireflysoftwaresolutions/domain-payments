/*
 * Copyright 2025 Firefly Software Foundation
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

package io.fireflysoftware.domain.payments.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.fireflysoftware.domain.payments",
        excludeName = {
                "io.fireflysoftware.rails.config.RailsAutoConfiguration",
                "io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration",
                "io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadMetricsAutoConfiguration",
                "io.github.resilience4j.springboot3.bulkhead.autoconfigure.ThreadPoolBulkheadMetricsAutoConfiguration",
                "io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration",
                "io.github.resilience4j.springboot3.retry.autoconfigure.RetryMetricsAutoConfiguration",
                "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration",
                "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerStreamEventsAutoConfiguration",
                "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerMetricsAutoConfiguration",
                "io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakersHealthIndicatorAutoConfiguration",
                "io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterAutoConfiguration",
                "io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimiterMetricsAutoConfiguration",
                "io.github.resilience4j.springboot3.ratelimiter.autoconfigure.RateLimitersHealthIndicatorAutoConfiguration",
                "io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterAutoConfiguration",
                "io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterMetricsAutoConfiguration",
                "io.github.resilience4j.springboot3.micrometer.autoconfigure.TimerAutoConfiguration",
                "io.github.resilience4j.springboot3.scheduled.threadpool.autoconfigure.ContextAwareScheduledThreadPoolAutoConfiguration",
                "org.fireflyframework.data.config.EdaAutoConfiguration",
                "org.fireflyframework.data.config.CqrsAutoConfiguration",
                "org.fireflyframework.data.config.JobOrchestrationAutoConfiguration",
                "org.fireflyframework.data.config.OrchestrationAutoConfiguration",
                "org.fireflyframework.data.config.ObservabilityAutoConfiguration",
                "org.fireflyframework.data.resiliency.ResiliencyAutoConfiguration",
                "org.fireflyframework.data.config.PersistenceAutoConfiguration",
                "org.fireflyframework.data.config.DataEnrichmentAutoConfiguration",
                "org.fireflyframework.data.config.DataQualityAutoConfiguration",
                "org.fireflyframework.data.config.DataLineageAutoConfiguration",
                "org.fireflyframework.orchestration.config.OrchestrationPersistenceAutoConfiguration"
        })
public class DomainPaymentsApplication {

    public static void main(String[] args) {
        if (System.getenv("SERVER_PORT") == null && System.getProperty("server.port") == null) {
            System.setProperty("server.port", "8092");
        }
        SpringApplication.run(DomainPaymentsApplication.class, args);
    }
}
