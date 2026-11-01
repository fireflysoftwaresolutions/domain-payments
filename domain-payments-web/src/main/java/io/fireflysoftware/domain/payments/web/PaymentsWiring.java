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

import io.fireflysoftware.domain.payments.sca.SandboxChallengeDeliveryAdapterImpl;
import io.fireflysoftware.domain.payments.sca.SandboxCoreScaClientImpl;
import io.fireflysoftware.domain.payments.sca.ChallengeDeliveryPort;
import io.fireflysoftware.domain.payments.sca.ScaChallengeService;
import io.fireflysoftware.domain.payments.sca.impl.ScaChallengeServiceImpl;
import io.fireflysoftware.domain.payments.sca.SdkCoreScaClientImpl;
import io.fireflysoftware.domain.payments.core.ports.AccountPositionPort;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentConfigurationPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentMasterPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRailPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;
import io.fireflysoftware.domain.payments.core.ports.PspPort;
import io.fireflysoftware.domain.payments.core.tcc.PaymentTccService;
import io.fireflysoftware.domain.payments.core.tcc.impl.PaymentTccServiceImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxAccountPositionAdapterImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxLedgerAdapterImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentConfigurationAdapterImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentMasterAdapterImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRailAdapterImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRecordAdapterImpl;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPspAdapterImpl;
import io.fireflysoftware.domain.payments.infra.SdkAccountPositionAdapterImpl;
import io.fireflysoftware.domain.payments.infra.SdkLedgerAdapterImpl;
import io.fireflysoftware.domain.payments.infra.SdkPaymentConfigurationAdapterImpl;
import io.fireflysoftware.domain.payments.infra.SdkPaymentMasterAdapterImpl;
import io.fireflysoftware.domain.payments.infra.SdkPaymentRecordAdapterImpl;
import io.fireflysoftware.domain.payments.infra.LibraryPaymentRailAdapterImpl;
import io.fireflysoftware.domain.payments.infra.LibraryPspAdapterImpl;
import io.fireflysoftware.psps.adapter.ports.PaymentPort;
import io.fireflysoftware.psps.adapter.ports.PayoutPort;
import io.fireflysoftware.psps.adapter.ports.RefundPort;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentsWiring {

    @Bean
    PaymentTccService paymentTccHub(LedgerPort ledger,
                                PaymentRecordPort records,
                                PaymentRailPort rail,
                                PspPort psp,
                                PaymentConfigurationPort configuration,
                                PaymentMasterPort masters,
                                ScaChallengeService sca) {
        return PaymentTccServiceImpl.create(ledger, records, rail, psp, configuration, masters, sca);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    ScaChallengeService sandboxSca() {
        return new ScaChallengeServiceImpl(new SandboxCoreScaClientImpl(), new SandboxChallengeDeliveryAdapterImpl());
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    ScaChallengeService liveSca(@Value("${firefly.payments.sca-base-url}") String baseUrl,
                              ChallengeDeliveryPort delivery) {
        if (SandboxChallengeDeliveryAdapterImpl.class.isAssignableFrom(AopProxyUtils.ultimateTargetClass(delivery))) {
            throw new IllegalStateException("Live payments require a non-sandbox challenge delivery adapter.");
        }
        return new ScaChallengeServiceImpl(SdkCoreScaClientImpl.at(baseUrl), delivery);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    LedgerPort sandboxLedger() {
        return new SandboxLedgerAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    AccountPositionPort sandboxAccounts() {
        return new SandboxAccountPositionAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    PaymentRecordPort sandboxRecords() {
        return new SandboxPaymentRecordAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    PaymentRailPort sandboxRail() {
        return new SandboxPaymentRailAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    PspPort sandboxPsp() {
        return new SandboxPspAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "sandbox", matchIfMissing = true)
    PaymentConfigurationPort sandboxConfiguration() {
        return new SandboxPaymentConfigurationAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "sandbox", matchIfMissing = true)
    PaymentMasterPort sandboxMasters() {
        return new SandboxPaymentMasterAdapterImpl();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    LedgerPort liveLedger(@Value("${firefly.payments.ledger-base-url}") String baseUrl) {
        return new SdkLedgerAdapterImpl(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    AccountPositionPort liveAccounts(@Value("${firefly.payments.accounts-base-url}") String baseUrl) {
        return new SdkAccountPositionAdapterImpl(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    PaymentRecordPort liveRecords(@Value("${firefly.payments.payments-core-base-url}") String baseUrl) {
        return new SdkPaymentRecordAdapterImpl(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    PaymentRailPort liveRail(io.fireflysoftware.rails.adapter.ports.PaymentRailPort libraryRail) {
        return new LibraryPaymentRailAdapterImpl(libraryRail);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    PspPort livePsp(PaymentPort payments, PayoutPort payouts, RefundPort refunds) {
        return new LibraryPspAdapterImpl(payments, payouts, refunds);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "live")
    PaymentConfigurationPort liveConfiguration(@Value("${firefly.payments.config-base-url}") String baseUrl) {
        return new SdkPaymentConfigurationAdapterImpl(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "live")
    PaymentMasterPort liveMasters(@Value("${firefly.payments.masters-base-url}") String baseUrl) {
        return new SdkPaymentMasterAdapterImpl(baseUrl);
    }
}
