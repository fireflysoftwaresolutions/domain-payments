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

package io.fireflysoftware.domain.payments.web;

import io.fireflysoftware.domain.payments.infra.PaymentInstructionRecords;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment-records")
public class PaymentRecordsController {

    private final PaymentInstructionRecords records;

    public PaymentRecordsController(PaymentInstructionRecords records) {
        this.records = records;
    }

    @PostMapping("/standing-orders")
    public Mono<RecordedId> standingOrder(@RequestBody StandingOrderBody body) {
        return records.runStandingOrder(body.paymentScheduleId(), body.amount(), body.currency(), body.valueDate())
                .map(RecordedId::new);
    }

    @PostMapping("/direct-debits")
    public Mono<RecordedId> directDebit(@RequestBody DirectDebitBody body) {
        return records.createDirectDebit(body.debtorAccountId(), body.creditorPartyId(), body.amountLimit(),
                        body.currency(), body.reference())
                .map(RecordedId::new);
    }

    public record StandingOrderBody(UUID paymentScheduleId, BigDecimal amount, String currency, LocalDate valueDate) {
    }

    public record DirectDebitBody(UUID debtorAccountId, UUID creditorPartyId, BigDecimal amountLimit,
                                   String currency, String reference) {
    }

    public record RecordedId(UUID id) {
    }
}
