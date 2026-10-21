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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentInstructionRecordsTest {

    @Mock
    private StandingOrderRunApi standingOrders;
    @Mock
    private DirectDebitMandateApi directDebits;

    @Test
    void standingOrderAndDirectDebitGoThroughThePaymentsCoreSdk() {
        PaymentInstructionRecords records = new PaymentInstructionRecords(standingOrders, directDebits);
        UUID scheduleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID debtor = UUID.randomUUID();
        UUID creditor = UUID.randomUUID();
        UUID mandateId = UUID.randomUUID();
        LocalDate valueDate = LocalDate.of(2026, 10, 21);

        when(standingOrders.runStandingOrder(any(), any())).thenReturn(Mono.just(
                new StandingOrderRunDTO().standingOrderRunId(runId).paymentScheduleId(scheduleId)));
        when(standingOrders.getStandingOrderRun(eq(runId), any())).thenReturn(Mono.just(
                new StandingOrderRunDTO().standingOrderRunId(runId).amount(new BigDecimal("25.00"))));
        when(directDebits.createDirectDebitMandate(any(), any())).thenReturn(Mono.just(
                new DirectDebitMandateDTO().directDebitMandateId(mandateId).debtorAccountId(debtor)));
        when(directDebits.getDirectDebitMandate(eq(mandateId), any())).thenReturn(Mono.just(
                new DirectDebitMandateDTO().directDebitMandateId(mandateId).reference("MANDATE-1")));

        UUID ran = records.runStandingOrder(scheduleId, new BigDecimal("25.00"), "GBP", valueDate).block();
        StandingOrderRunDTO readRun = records.standingOrder(ran).block();
        UUID created = records.createDirectDebit(debtor, creditor, new BigDecimal("100.00"), "GBP", "MANDATE-1").block();
        DirectDebitMandateDTO readMandate = records.directDebit(created).block();

        assertThat(readRun.getStandingOrderRunId()).isEqualTo(runId);
        assertThat(readMandate.getReference()).isEqualTo("MANDATE-1");
        verify(standingOrders).runStandingOrder(any(), any());
        verify(standingOrders).getStandingOrderRun(eq(runId), any());
        verify(directDebits).createDirectDebitMandate(any(), any());
        verify(directDebits).getDirectDebitMandate(eq(mandateId), any());
    }
}
