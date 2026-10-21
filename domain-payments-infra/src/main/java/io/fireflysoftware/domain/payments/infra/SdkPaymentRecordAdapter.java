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

import io.fireflysoftware.core.banking.payments.sdk.api.PaymentInstructionsApi;
import io.fireflysoftware.core.banking.payments.sdk.api.PaymentOrdersApi;
import io.fireflysoftware.core.banking.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.payments.sdk.model.PaymentInstructionDTO;
import io.fireflysoftware.core.banking.payments.sdk.model.PaymentOrderDTO;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.StoredPayment;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the payment order and its instruction through {@code core-banking-payments-sdk}.
 * There is no cancel operation on a payment order: status is INITIATED, COMPLETED, or FAILED.
 * UNMATCHED is stored as FAILED. Lookups stay on the adapter because the domain id is assigned here.
 */
public final class SdkPaymentRecordAdapter implements PaymentRecordPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final PaymentOrdersApi orders;
    private final PaymentInstructionsApi instructions;
    private final ConcurrentHashMap<UUID, StoredPayment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byReference = new ConcurrentHashMap<>();

    public SdkPaymentRecordAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new PaymentOrdersApi(client), new PaymentInstructionsApi(client));
    }

    SdkPaymentRecordAdapter(PaymentOrdersApi orders, PaymentInstructionsApi instructions) {
        this.orders = orders;
        this.instructions = instructions;
    }

    @Override
    public Optional<StoredPayment> findById(UUID paymentId) {
        return Optional.ofNullable(byId.get(paymentId));
    }

    @Override
    public Optional<StoredPayment> findByIdempotencyKey(String idempotencyKey) {
        UUID id = byKey.get(idempotencyKey);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<StoredPayment> findByThirdPartyReference(String thirdPartyReference) {
        UUID id = thirdPartyReference == null ? null : byReference.get(thirdPartyReference);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public StoredPayment save(StoredPayment payment) {
        UUID payer = payment.sourceAccountId() == null ? payment.destinationAccountId() : payment.sourceAccountId();
        PaymentOrderDTO order = new PaymentOrderDTO()
                .payerAccountId(payer)
                .paymentMethodId(payment.paymentMethodId())
                .beneficiaryName("Beneficiary")
                .paymentType(PaymentOrderDTO.PaymentTypeEnum.fromValue(coreType(payment)))
                .orderDate(LocalDateTime.now().withNano(0))
                .status(PaymentOrderDTO.StatusEnum.fromValue(coreStatus(payment.status())))
                .amount(payment.amount())
                .currencyCode(payment.currency())
                .referenceNumber(trim(payment.idempotencyKey(), 35))
                .endToEndId(trim(payment.idempotencyKey(), 35))
                .remittanceInformation(payment.scheme().name());
        PaymentOrderDTO created = call(orders.createPaymentOrder(payment.idempotencyKey(), order));
        if (created == null || created.getPaymentOrderId() == null) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "core-banking-payments rejected the order.");
        }
        PaymentInstructionDTO instruction = new PaymentInstructionDTO()
                .paymentOrderId(created.getPaymentOrderId())
                .instructionId(trim(payment.idempotencyKey(), 50))
                .instructionType(PaymentInstructionDTO.InstructionTypeEnum.IMMEDIATE)
                .instructionDate(LocalDateTime.now().withNano(0))
                .instructionStatus(PaymentInstructionDTO.InstructionStatusEnum.fromValue(coreStatus(payment.status())))
                .externalReference(payment.thirdPartyReference() == null ? payment.idempotencyKey() : payment.thirdPartyReference())
                .instructionNotes(payment.status().name());
        if (payment.failure() != null) {
            instruction.errorCode(trim(payment.failure().name(), 20));
        }
        call(instructions.createPaymentInstruction(created.getPaymentOrderId(), payment.idempotencyKey(), instruction));
        byId.put(payment.paymentId(), payment);
        byKey.put(payment.idempotencyKey(), payment.paymentId());
        if (payment.thirdPartyReference() != null) {
            byReference.put(payment.thirdPartyReference(), payment.paymentId());
        }
        return payment;
    }

    private String coreType(StoredPayment payment) {
        if (payment.route() == MovementRoute.INTERNAL_BOOK) {
            return "INTERNAL";
        }
        if (payment.scheme() == PaymentScheme.INTERNATIONAL_OUTBOUND) {
            return "SWIFT";
        }
        if (payment.scheme() == PaymentScheme.DOMESTIC_OUTBOUND || payment.scheme() == PaymentScheme.RAIL_INBOUND) {
            return "SEPA_SCT";
        }
        return "INTERNAL";
    }

    private String coreStatus(PaymentStatus status) {
        if (status == PaymentStatus.COMPLETED) {
            return "COMPLETED";
        }
        if (status == PaymentStatus.INITIATED) {
            return "INITIATED";
        }
        return "FAILED";
    }

    private static <T> T call(Mono<T> call) {
        try {
            return call.block(TIMEOUT);
        } catch (WebClientResponseException ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED,
                    "core-banking-payments returned HTTP " + ex.getStatusCode().value() + ".");
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "core-banking-payments could not be reached.");
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String strip(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Payments base URL is required.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
