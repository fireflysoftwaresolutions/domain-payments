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

package io.fireflysoftware.domain.payments.infra;

import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.SyncMessages.CompensationRequest;
import io.fireflysoftware.domain.payments.SyncMessages.InboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.rails.domain.BankAccount;
import io.fireflysoftware.rails.domain.Currency;
import io.fireflysoftware.rails.domain.IdempotencyKey;
import io.fireflysoftware.rails.domain.Money;
import io.fireflysoftware.rails.domain.PaymentMode;
import io.fireflysoftware.rails.domain.PaymentStatus;
import io.fireflysoftware.rails.domain.TransactionType;
import io.fireflysoftware.rails.dtos.payments.InitiatePaymentRequest;
import io.fireflysoftware.rails.dtos.payments.PaymentResponse;
import io.fireflysoftware.rails.dtos.payments.ReturnRequest;
import io.fireflysoftware.rails.dtos.payments.ReturnResponse;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Live rail adapter. Outbound calls {@code initiatePayment}. International wires use
 * {@code TransactionType.WIRE_TRANSFER}. Inbound acceptance does not invent a library operation:
 * library-rails has no inbound-credit method. A later return uses {@code requestReturn}.
 */
public final class LibraryPaymentRailAdapter implements io.fireflysoftware.domain.payments.core.ports.PaymentRailPort {

    private final io.fireflysoftware.rails.adapter.ports.PaymentRailPort rails;

    public LibraryPaymentRailAdapter(io.fireflysoftware.rails.adapter.ports.PaymentRailPort rails) {
        this.rails = rails;
    }

    @Override
    public SyncResult sendOutbound(OutboundInstruction instruction) {
        String holder = instruction.creditorName() == null || instruction.creditorName().isBlank()
                ? "Beneficiary" : instruction.creditorName();
        BankAccount creditor = instruction.creditorIban() == null
                ? BankAccount.fromAccountNumber(holder, instruction.sourceAccountId().toString(), "000000000")
                : BankAccount.fromIban(holder, instruction.creditorIban(), instruction.creditorBic());
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .amount(new Money(instruction.amount(), currency(instruction.currency())))
                .debtorAccount(BankAccount.fromAccountNumber("Customer", instruction.sourceAccountId().toString(), "000000000"))
                .creditorAccount(creditor)
                .endToEndReference(instruction.idempotencyKey())
                .remittanceInformation(instruction.railKind() == null ? null : instruction.railKind().name())
                .transactionType(instruction.railKind() != null && instruction.railKind().internationalWire()
                        ? TransactionType.WIRE_TRANSFER : TransactionType.CREDIT_TRANSFER)
                .paymentMode(PaymentMode.LIVE)
                .idempotencyKey(IdempotencyKey.fromString(instruction.idempotencyKey()))
                .metadata(instruction.railKind() == null ? Map.of() : Map.of("railKind", instruction.railKind().name()))
                .build();
        return map(body(rails.initiatePayment(request)));
    }

    @Override
    public SyncResult acceptInbound(InboundInstruction instruction) {
        return SyncResult.accepted(instruction.thirdPartyReference());
    }

    @Override
    public boolean supportsReturn() {
        return true;
    }

    @Override
    public SyncResult returnOrReject(CompensationRequest request) {
        ReturnResponse response = body(rails.requestReturn(ReturnRequest.builder()
                .id(request.reference())
                .status("return")
                .build()));
        return SyncResult.accepted(response.getId() == null ? request.reference() : response.getId());
    }

    private SyncResult map(PaymentResponse body) {
        PaymentStatus status = body.getStatus();
        if (status == PaymentStatus.FAILED || status == PaymentStatus.REJECTED || status == PaymentStatus.RETURNED
                || status == PaymentStatus.CANCELLED || status == PaymentStatus.REVERSED || status == PaymentStatus.PENDING_REVERSAL) {
            return SyncResult.rejected(body.getStatusReason() == null ? status.name() : body.getStatusReason());
        }
        String reference = body.getPaymentId() == null ? body.getEndToEndReference() : body.getPaymentId();
        return SyncResult.accepted(reference);
    }

    private Currency currency(String code) {
        try {
            return Currency.valueOf(code);
        } catch (IllegalArgumentException ex) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,
                    "library-rails does not list currency " + code + ".");
        }
    }

    private <T> T body(Mono<ResponseEntity<T>> call) {
        ResponseEntity<T> response = call.block(Duration.ofSeconds(30));
        if (response == null || response.getBody() == null) {
            throw new PaymentFlowException(PaymentFailure.SYNC_REJECTED, "The rail returned an empty response.");
        }
        return response.getBody();
    }
}
