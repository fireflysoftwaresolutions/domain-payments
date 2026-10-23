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

import org.fireflyframework.security.rs.AccessTokenValidators;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
public class ManagedPaymentsSecurityConfiguration {
    @Bean
    @Order(-98)
    SecurityWebFilterChain managedPaymentPublicContract(ServerHttpSecurity http) {
        return http.securityMatcher(
                        ServerWebExchangeMatchers.pathMatchers(
                                org.springframework.http.HttpMethod.GET,
                                "/v3/api-docs",
                                "/v3/api-docs.yaml",
                                "/v3/api-docs/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(e -> e.anyExchange().permitAll())
                .build();
    }

    @Bean
    @Order(-99)
    SecurityWebFilterChain managedPaymentDiagnosticsDenied(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/orchestration/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(e -> e.anyExchange().denyAll())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new org.springframework.security.web.server.authentication
                                                .HttpStatusServerEntryPoint(
                                                org.springframework.http.HttpStatus.FORBIDDEN)))
                .build();
    }

    @Bean
    @Order(-100)
    SecurityWebFilterChain managedPaymentsSecurity(
            ServerHttpSecurity http,
            @Value("${managed-payments.security.issuer:}") String issuer,
            @Value("${managed-payments.security.audience:}") String audience,
            @Value("${managed-payments.security.jwk-set-uri:}") String jwks) {
        ReactiveJwtDecoder decoder;
        if (issuer.isBlank() || audience.isBlank() || jwks.isBlank()) {
            decoder =
                    token ->
                            Mono.error(new JwtException("Managed payment trust is not configured"));
        } else {
            var configured = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
            configured.setJwtValidator(
                    AccessTokenValidators.forIssuerAndAudience(issuer, audience));
            decoder = configured;
        }
        return http.securityMatcher(
                        ServerWebExchangeMatchers.pathMatchers(
                                "/api/v1/tenants/*/internal-payments/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(e -> e.anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder)))
                .build();
    }
}
