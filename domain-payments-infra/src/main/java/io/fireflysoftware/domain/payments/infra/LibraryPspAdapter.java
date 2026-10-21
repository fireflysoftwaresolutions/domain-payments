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
import io.fireflysoftware.domain.payments.PspInstrument;
import io.fireflysoftware.domain.payments.SyncMessages.CompensationRequest;
import io.fireflysoftware.domain.payments.SyncMessages.InboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.domain.payments.core.ports.PspPort;
import io.fireflysoftware.psps.adapter.ports.PaymentPort;
import io.fireflysoftware.psps.adapter.ports.PayoutPort;
import io.fireflysoftware.psps.adapter.ports.RefundPort;
import io.fireflysoftware.psps.domain.Currency;
import io.fireflysoftware.psps.domain.Money;
import io.fireflysoftware.psps.domain.PaymentMethodType;
import io.fireflysoftware.psps.domain.PaymentStatus;
import io.fireflysoftware.psps.dtos.payments.CreatePaymentRequest;
import io.fireflysoftware.psps.dtos.payments.PaymentResponse;
import io.fireflysoftware.psps.dtos.payouts.CreatePayoutRequest;
import io.fireflysoftware.psps.dtos.payouts.PayoutResponse;
import io.fireflysoftware.psps.dtos.refunds.CreateRefundRequest;
import io.fireflysoftware.psps.dtos.refunds.RefundResponse;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;

/**
 * Live PSP adapter. Outbound uses {@code PayoutPort}. Card collection uses {@code PaymentPort}.
 * A credit transfer is not sent here. Compensation uses {@code cancelPayout} or {@code createRefund}.
 */
public final class LibraryPspAdapter implements PspPort {

    private final PaymentPort payments;
    private final PayoutPort payouts;
    private final RefundPort refunds;

    public LibraryPspAdapter(PaymentPort payments, PayoutPort payouts, RefundPort refunds) {
        this.payments = payments;
        this.payouts = payouts;
        this.refunds = refunds;
    }

    @Override
    public SyncResult sendOutbound(OutboundInstruction instruction) {
        if (instruction.instrument() == PspInstrument.CARD) {
            PaymentResponse response = body(payments.createPayment(CreatePaymentRequest.builder()
                    .amount(new Money(instruction.amount(), currency(instruction.currency())))
                    .paymentMethodType(PaymentMethodType.CARD)
                    .idempotencyKey(instruction.idempotencyKey())
                    .captureImmediately(true)
                    .description(instruction.idempotencyKey())
                    .build()));
            return mapPayment(response);
        }
        if (instruction.instrument() != PspInstrument.PAYOUT) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                    "A credit transfer does not use the PSP. Card collection uses PaymentPort and a payout uses PayoutPort.");
        }
        String destination = instruction.creditorIban() == null
                ? instruction.sourceAccountId().toString() : instruction.creditorIban();
        PayoutResponse response = body(payouts.createPayout(CreatePayoutRequest.builder()
                .amount(new Money(instruction.amount(), currency(instruction.currency())))
                .destination(destination)
                .description(instruction.idempotencyKey())
                .build()));
        return mapText(response.getStatus(), response.getPayoutId());
    }

    @Override
    public SyncResult acceptInbound(InboundInstruction instruction) {
        if (instruction.instrument() == null || instruction.instrument() == PspInstrument.PAYOUT) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                    "An inbound PSP movement uses a collection instrument.");
        }
        PaymentResponse response = body(payments.createPayment(CreatePaymentRequest.builder()
                .amount(new Money(instruction.amount(), currency(instruction.currency())))
                .paymentMethodType(PaymentMethodType.valueOf(instruction.instrument().name()))
                .idempotencyKey(instruction.thirdPartyReference())
                .captureImmediately(true)
                .description(instruction.thirdPartyReference())
                .build()));
        return mapPayment(response);
    }

    @Override
    public boolean supportsReturn() {
        return true;
    }

    @Override
    public SyncResult returnOrReject(CompensationRequest request) {
        if (request.instrument() == PspInstrument.PAYOUT) {
            PayoutResponse response = body(payouts.cancelPayout(request.reference()));
            return mapText(response.getStatus(), response.getPayoutId());
        }
        RefundResponse response = body(refunds.createRefund(CreateRefundRequest.builder()
                .paymentId(request.reference())
                .amount(new Money(request.amount(), currency(request.currency())))
                .reason("payment compensation")
                .build()));
        return SyncResult.accepted(response.getRefundId() == null ? request.reference() : response.getRefundId());
    }

    private SyncResult mapPayment(PaymentResponse response) {
        PaymentStatus status = response.getStatus();
        if (status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED || status == PaymentStatus.REFUNDED
                || status == PaymentStatus.REQUIRES_ACTION) {
            return SyncResult.rejected(status.name());
        }
        return SyncResult.accepted(response.getPaymentId());
    }

    private SyncResult mapText(String status, String reference) {
        if (status == null) {
            return SyncResult.accepted(reference);
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        if (normalized.equals("FAILED") || normalized.equals("REJECTED") || normalized.equals("CANCELLED")
                || normalized.equals("CANCELED") || normalized.equals("RETURNED")) {
            return SyncResult.rejected(status);
        }
        return SyncResult.accepted(reference);
    }

    private Currency currency(String code) {
        try {
            return Currency.valueOf(code);
        } catch (IllegalArgumentException ex) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,
                    "library-psps does not list currency " + code + ".");
        }
    }

    private <T> T body(Mono<ResponseEntity<T>> call) {
        ResponseEntity<T> response = call.block(Duration.ofSeconds(30));
        if (response == null || response.getBody() == null) {
            throw new PaymentFlowException(PaymentFailure.SYNC_REJECTED, "The PSP returned an empty response.");
        }
        return response.getBody();
    }
}
