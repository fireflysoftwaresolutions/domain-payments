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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.domain.payments.core.managed.v1.ManagedPaymentRecoveryWorker;
import io.fireflysoftware.domain.payments.core.managed.v1.ports.ManagedPaymentCorePort;
import io.fireflysoftware.domain.payments.core.managed.v1.ports.ManagedPaymentWorkerActorPort;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.orchestration.core.scheduling.OrchestrationScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class ManagedPaymentWorkerActorConfigurationTest {
    @Test
    void plainProjectAuthorityWinsWhenWorkerWiringIsRegisteredFirst() {
        new ApplicationContextRunner()
                .withUserConfiguration(ManagedPaymentWorkerWiring.class, ProjectAuthority.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ManagedPaymentWorkerActorPort.class);
                            assertThat(context.getBean(ManagedPaymentWorkerActorPort.class))
                                    .isSameAs(ProjectAuthority.ACTOR);
                            assertThat(context).doesNotHaveBean("managedRecoveryActor");
                        });
    }

    @Test
    void plainProjectAuthorityWinsWhenProjectConfigurationIsRegisteredFirst() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProjectAuthority.class, ManagedPaymentWorkerWiring.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ManagedPaymentWorkerActorPort.class);
                            assertThat(context.getBean(ManagedPaymentWorkerActorPort.class))
                                    .isSameAs(ProjectAuthority.ACTOR);
                            assertThat(context).doesNotHaveBean("managedRecoveryActor");
                        });
    }

    @Test
    void bootDeferredDefaultRemainsClosedAndDoesNotScheduleWhenDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationScan.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ManagedPaymentWorkerActorPort.class);
                            assertThat(context).doesNotHaveBean(ManagedPaymentRecoveryWorker.class);
                            assertThat(context)
                                    .doesNotHaveBean(
                                            ManagedPaymentWorkerWiring.RecoverySchedule.class);
                            assertThat(context)
                                    .doesNotHaveBean(ReactiveOAuth2AuthorizedClientManager.class);
                            assertThat(context)
                                    .doesNotHaveBean(ReactiveClientRegistrationRepository.class);
                            assertThatThrownBy(
                                            () ->
                                                    context.getBean(
                                                                    ManagedPaymentWorkerActorPort
                                                                            .class)
                                                            .current("owned")
                                                            .block(Duration.ofSeconds(2)))
                                    .isInstanceOfSatisfying(
                                            ResponseStatusException.class,
                                            e ->
                                                    assertThat(e.getStatusCode().value())
                                                            .isEqualTo(503));
                        });
    }

    @Test
    void plainProjectAuthorityDoesNotRequireNativeOauthRegistrationsWhenRecoveryIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationScan.class, ProjectAuthority.class)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ManagedPaymentWorkerActorPort.class);
                            assertThat(context.getBean(ManagedPaymentWorkerActorPort.class))
                                    .isSameAs(ProjectAuthority.ACTOR);
                            assertThat(context).doesNotHaveBean("managedRecoveryActor");
                            assertThat(context).doesNotHaveBean(ManagedPaymentRecoveryWorker.class);
                            assertThat(context)
                                    .doesNotHaveBean(ReactiveOAuth2AuthorizedClientManager.class);
                            assertThat(context)
                                    .doesNotHaveBean(ReactiveClientRegistrationRepository.class);
                        });
    }

    @Test
    void configuredDefaultWithoutNativeServicesCannotCreateAuthority() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplicationScan.class)
                .withPropertyValues(OAUTH_PROPERTIES)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(ManagedPaymentWorkerActorPort.class);
                            assertThatThrownBy(
                                            () ->
                                                    context.getBean(
                                                                    ManagedPaymentWorkerActorPort
                                                                            .class)
                                                            .current("owned")
                                                            .block(Duration.ofSeconds(2)))
                                    .isInstanceOfSatisfying(
                                            ResponseStatusException.class,
                                            e ->
                                                    assertThat(e.getStatusCode().value())
                                                            .isEqualTo(503));
                            assertThat(context).doesNotHaveBean(ManagedPaymentRecoveryWorker.class);
                        });
    }

    @Test
    void deferredDefaultBacksOffAfterEagerApplicationScanWithConfiguredProperties() {
        for (boolean reverse : new boolean[] {false, true}) {
            var configurations =
                    reverse
                            ? new Class<?>[] {ProjectAuthority.class, ApplicationScan.class}
                            : new Class<?>[] {ApplicationScan.class, ProjectAuthority.class};
            new ApplicationContextRunner()
                    .withUserConfiguration(configurations)
                    .withPropertyValues(OAUTH_PROPERTIES)
                    .run(
                            context -> {
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(ManagedPaymentWorkerActorPort.class);
                                assertThat(context.getBean(ManagedPaymentWorkerActorPort.class))
                                        .isSameAs(ProjectAuthority.ACTOR);
                                assertThat(context).doesNotHaveBean("managedRecoveryActor");
                                assertThat(context)
                                        .doesNotHaveBean(
                                                ReactiveOAuth2AuthorizedClientManager.class);
                                assertThat(context)
                                        .doesNotHaveBean(
                                                ReactiveClientRegistrationRepository.class);
                            });
        }
    }

    @Test
    void actualBootContextInjectsThePlainProjectActorIntoEnabledWorkerInEitherSourceOrder() {
        for (boolean reverse : new boolean[] {false, true}) {
            ProjectAuthority.calls.set(0);
            var sources =
                    reverse
                            ? new Class<?>[] {ProjectAuthority.class, BootHarness.class}
                            : new Class<?>[] {BootHarness.class, ProjectAuthority.class};
            try (var context =
                    new SpringApplicationBuilder(sources)
                            .web(WebApplicationType.NONE)
                            .run(
                                    "--spring.main.web-application-type=none",
                                    "--spring.main.allow-bean-definition-overriding=false",
                                    "--managed-payments.recovery.enabled=true",
                                    "--managed-payments.recovery.interval-seconds=60",
                                    "--managed-payments.recovery.registrations.owned=owned-worker")) {
                assertThat(context.getBeansOfType(ManagedPaymentWorkerActorPort.class)).hasSize(1);
                assertThat(context.getBean(ManagedPaymentWorkerActorPort.class))
                        .isSameAs(ProjectAuthority.ACTOR);
                assertThat(context.containsBean("managedRecoveryActor")).isFalse();
                assertThat(context.getBean(OrchestrationScheduler.class).activeTaskCount())
                        .isEqualTo(1);
                var result =
                        context.getBean(ManagedPaymentRecoveryWorker.class)
                                .runOnce()
                                .block(Duration.ofSeconds(3));
                assertThat(result.deferred()).isEqualTo(1);
                assertThat(result.scanned()).isZero();
                assertThat(ProjectAuthority.calls).hasValue(1);
                verifyNoInteractions(
                        context.getBean(ManagedPaymentCorePort.class),
                        context.getBean(CommandBus.class));
            }
        }
    }

    private static final String[] OAUTH_PROPERTIES = {
        "managed-payments.recovery.actor-mode=oauth2-client-credentials",
        "managed-payments.recovery.registrations.owned=owned-worker",
        "managed-payments.security.issuer=https://issuer.example.invalid",
        "managed-payments.security.audience=domain-payments",
        "managed-payments.security.jwk-set-uri=https://issuer.example.invalid/jwks"
    };

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = ManagedPaymentWorkerWiring.class,
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.ASSIGNABLE_TYPE,
                            classes = {
                                ManagedPaymentWorkerWiring.class,
                                ManagedPaymentWorkerActorAutoConfiguration.class
                            }),
            excludeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.CUSTOM,
                            classes = AutoConfigurationExcludeFilter.class))
    @ImportAutoConfiguration(ManagedPaymentWorkerActorAutoConfiguration.class)
    static class ApplicationScan {}

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(ApplicationScan.class)
    static class BootHarness {
        @Bean
        ManagedPaymentCorePort core() {
            return mock(ManagedPaymentCorePort.class);
        }

        @Bean
        CommandBus commands() {
            return mock(CommandBus.class);
        }

        @Bean(destroyMethod = "shutdown")
        OrchestrationScheduler scheduler() {
            return new OrchestrationScheduler(1);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProjectAuthority {
        static final AtomicInteger calls = new AtomicInteger();
        static final ManagedPaymentWorkerActorPort ACTOR =
                tenant ->
                        Mono.defer(
                                () -> {
                                    calls.incrementAndGet();
                                    return Mono.empty();
                                });

        @Bean
        ManagedPaymentWorkerActorPort projectCurrentWorkerAuthority() {
            return ACTOR;
        }
    }
}
