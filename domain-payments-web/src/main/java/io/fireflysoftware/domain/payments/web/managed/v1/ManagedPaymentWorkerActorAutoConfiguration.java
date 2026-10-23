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

import io.fireflysoftware.domain.payments.core.managed.v1.ManagedPaymentContext;
import io.fireflysoftware.domain.payments.core.managed.v1.ports.ManagedPaymentWorkerActorPort;
import io.fireflysoftware.domain.payments.infra.managed.v1.OAuthManagedPaymentWorkerActorAdapter;
import io.fireflysoftware.domain.payments.web.managed.v1.ManagedPaymentWorkerWiring.RecoveryProperties;

import org.fireflyframework.security.rs.AccessTokenValidators;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;

import reactor.core.publisher.Mono;

@AutoConfiguration
@EnableConfigurationProperties(RecoveryProperties.class)
public class ManagedPaymentWorkerActorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ManagedPaymentWorkerActorPort.class)
    ManagedPaymentWorkerActorPort managedRecoveryActor(
            RecoveryProperties p,
            ObjectProvider<ReactiveOAuth2AuthorizedClientManager> managers,
            ObjectProvider<ReactiveClientRegistrationRepository> registrations,
            @Value("${managed-payments.security.issuer:}") String issuer,
            @Value("${managed-payments.security.audience:}") String audience,
            @Value("${managed-payments.security.jwk-set-uri:}") String jwks,
            @Value("${managed-payments.allow-loopback-http:false}") boolean loopback) {
        if (!"oauth2-client-credentials".equals(p.getActorMode())
                || issuer.isBlank()
                || audience.isBlank()
                || jwks.isBlank()
                || p.getRegistrations().isEmpty())
            return t ->
                    Mono.error(ManagedPaymentContext.fail(503, "TRUSTED_RECOVERY_ACTOR_REQUIRED"));
        var manager = managers.getIfAvailable();
        var repository = registrations.getIfAvailable();
        if (manager == null || repository == null)
            return t ->
                    Mono.error(
                            ManagedPaymentContext.fail(
                                    503, "NATIVE_WORKER_CLIENT_CREDENTIALS_REQUIRED"));
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(AccessTokenValidators.forIssuerAndAudience(issuer, audience));
        return new OAuthManagedPaymentWorkerActorAdapter(
                manager, repository, decoder, p.getRegistrations(), loopback);
    }
}
