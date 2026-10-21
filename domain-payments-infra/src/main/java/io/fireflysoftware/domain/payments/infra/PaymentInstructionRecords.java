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

package io.fireflysoftware.domain.payments.infra;

import io.fireflysoftware.core.banking.payments.sdk.api.DirectDebitMandateApi;
import io.fireflysoftware.core.banking.payments.sdk.api.StandingOrderRunApi;
import io.fireflysoftware.core.banking.payments.sdk.model.DirectDebitMandateDTO;
import io.fireflysoftware.core.banking.payments.sdk.model.StandingOrderRunDTO;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Standing-order run and direct-debit mandate. The payments core already stores both.
 * This service calls that core SDK.
 */
@Service
public class PaymentInstructionRecords {

    private final StandingOrderRunApi standingOrders;
    private final DirectDebitMandateApi directDebits;

    public PaymentInstructionRecords(StandingOrderRunApi standingOrders, DirectDebitMandateApi directDebits) {
        this.standingOrders = standingOrders;
        this.directDebits = directDebits;
    }

    public Mono<UUID> runStandingOrder(UUID paymentScheduleId, BigDecimal amount, String currency, LocalDate valueDate) {
        StandingOrderRunDTO body = new StandingOrderRunDTO()
                .paymentScheduleId(paymentScheduleId)
                .amount(amount)
                .currency(currency)
                .valueDate(valueDate);
        return standingOrders.runStandingOrder(body, UUID.randomUUID().toString())
                .map(StandingOrderRunDTO::getStandingOrderRunId);
    }

    public Mono<StandingOrderRunDTO> standingOrder(UUID runId) {
        return standingOrders.getStandingOrderRun(runId, UUID.randomUUID().toString());
    }

    public Mono<UUID> createDirectDebit(UUID debtorAccountId, UUID creditorPartyId, BigDecimal amountLimit,
                                         String currency, String reference) {
        DirectDebitMandateDTO body = new DirectDebitMandateDTO()
                .debtorAccountId(debtorAccountId)
                .creditorPartyId(creditorPartyId)
                .status(DirectDebitMandateDTO.StatusEnum.ACTIVE)
                .amountLimit(amountLimit)
                .currency(currency)
                .reference(reference);
        return directDebits.createDirectDebitMandate(body, UUID.randomUUID().toString())
                .map(DirectDebitMandateDTO::getDirectDebitMandateId);
    }

    public Mono<DirectDebitMandateDTO> directDebit(UUID mandateId) {
        return directDebits.getDirectDebitMandate(mandateId, UUID.randomUUID().toString());
    }
}
