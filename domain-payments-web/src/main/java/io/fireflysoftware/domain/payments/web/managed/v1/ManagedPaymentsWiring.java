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
import io.fireflysoftware.domain.payments.infra.managed.v1.*;

import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ManagedPaymentsWiring {
    @Bean
    @ConditionalOnMissingBean(PaymentDelegationPort.class)
    PaymentDelegationPort managedPaymentDelegation(
            @Value("${managed-payments.delegation.mode:unconfigured}") String mode) {
        return new ManagedPaymentDelegationAdapter(mode);
    }

    @Bean
    @ConditionalOnMissingBean(ManagedPaymentCorePort.class)
    ManagedPaymentCorePort managedPaymentCore(
            PaymentDelegationPort delegation,
            @Value("${managed-payments.peers.payment-core.base-url:}") String url,
            @Value("${managed-payments.peers.payment-core.audience:}") String audience,
            @Value("${managed-payments.allow-loopback-http:false}") boolean loopback) {
        return new SdkManagedPaymentCoreAdapter(delegation, url, audience, loopback);
    }

    @Bean
    @ConditionalOnMissingBean(ManagedFundsPort.class)
    ManagedFundsPort managedPaymentFunds(
            PaymentDelegationPort delegation,
            @Value("${managed-payments.peers.ledger.base-url:}") String url,
            @Value("${managed-payments.peers.ledger.audience:}") String audience,
            @Value("${managed-payments.allow-loopback-http:false}") boolean loopback) {
        return new SdkManagedFundsAdapter(delegation, url, audience, loopback);
    }

    @Bean
    ManagedPaymentFlow managedPaymentFlow(
            SagaEngine engine, ManagedPaymentCorePort core, ManagedFundsPort funds) {
        return new ManagedPaymentFlow(engine, core, funds);
    }
}
