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

import io.fireflysoftware.domain.payments.sca.SandboxChallengeDeliveryAdapter;
import io.fireflysoftware.domain.payments.sca.SandboxCoreScaClient;
import io.fireflysoftware.domain.payments.sca.ChallengeDeliveryPort;
import io.fireflysoftware.domain.payments.sca.ScaChallengeService;
import io.fireflysoftware.domain.payments.sca.SdkCoreScaClient;
import io.fireflysoftware.domain.payments.core.ports.AccountPositionPort;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentConfigurationPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentMasterPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRailPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;
import io.fireflysoftware.domain.payments.core.ports.PspPort;
import io.fireflysoftware.domain.payments.core.tcc.PaymentTccHub;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxAccountPositionAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxLedgerAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentConfigurationAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentMasterAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRailAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRecordAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPspAdapter;
import io.fireflysoftware.domain.payments.infra.SdkAccountPositionAdapter;
import io.fireflysoftware.domain.payments.infra.SdkLedgerAdapter;
import io.fireflysoftware.domain.payments.infra.SdkPaymentConfigurationAdapter;
import io.fireflysoftware.domain.payments.infra.SdkPaymentMasterAdapter;
import io.fireflysoftware.domain.payments.infra.SdkPaymentRecordAdapter;
import io.fireflysoftware.domain.payments.infra.LibraryPaymentRailAdapter;
import io.fireflysoftware.domain.payments.infra.LibraryPspAdapter;
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
    PaymentTccHub paymentTccHub(LedgerPort ledger,
                                PaymentRecordPort records,
                                PaymentRailPort rail,
                                PspPort psp,
                                PaymentConfigurationPort configuration,
                                PaymentMasterPort masters,
                                ScaChallengeService sca) {
        return PaymentTccHub.create(ledger, records, rail, psp, configuration, masters, sca);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    ScaChallengeService sandboxSca() {
        return new ScaChallengeService(new SandboxCoreScaClient(), new SandboxChallengeDeliveryAdapter());
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    ScaChallengeService liveSca(@Value("${firefly.payments.sca-base-url}") String baseUrl,
                              ChallengeDeliveryPort delivery) {
        if (SandboxChallengeDeliveryAdapter.class.isAssignableFrom(AopProxyUtils.ultimateTargetClass(delivery))) {
            throw new IllegalStateException("Live payments require a non-sandbox challenge delivery adapter.");
        }
        return new ScaChallengeService(SdkCoreScaClient.at(baseUrl), delivery);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    LedgerPort sandboxLedger() {
        return new SandboxLedgerAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    AccountPositionPort sandboxAccounts() {
        return new SandboxAccountPositionAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    PaymentRecordPort sandboxRecords() {
        return new SandboxPaymentRecordAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    PaymentRailPort sandboxRail() {
        return new SandboxPaymentRailAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "sandbox", matchIfMissing = true)
    PspPort sandboxPsp() {
        return new SandboxPspAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "sandbox", matchIfMissing = true)
    PaymentConfigurationPort sandboxConfiguration() {
        return new SandboxPaymentConfigurationAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "sandbox", matchIfMissing = true)
    PaymentMasterPort sandboxMasters() {
        return new SandboxPaymentMasterAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    LedgerPort liveLedger(@Value("${firefly.payments.ledger-base-url}") String baseUrl) {
        return new SdkLedgerAdapter(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    AccountPositionPort liveAccounts(@Value("${firefly.payments.accounts-base-url}") String baseUrl) {
        return new SdkAccountPositionAdapter(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    PaymentRecordPort liveRecords(@Value("${firefly.payments.payments-core-base-url}") String baseUrl) {
        return new SdkPaymentRecordAdapter(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    PaymentRailPort liveRail(io.fireflysoftware.rails.adapter.ports.PaymentRailPort libraryRail) {
        return new LibraryPaymentRailAdapter(libraryRail);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.mode", havingValue = "live")
    PspPort livePsp(PaymentPort payments, PayoutPort payouts, RefundPort refunds) {
        return new LibraryPspAdapter(payments, payouts, refunds);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "live")
    PaymentConfigurationPort liveConfiguration(@Value("${firefly.payments.config-base-url}") String baseUrl) {
        return new SdkPaymentConfigurationAdapter(baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "firefly.payments.configuration", havingValue = "live")
    PaymentMasterPort liveMasters(@Value("${firefly.payments.masters-base-url}") String baseUrl) {
        return new SdkPaymentMasterAdapter(baseUrl);
    }
}
