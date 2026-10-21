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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.RailKind;
import io.fireflysoftware.domain.payments.StoredPayment;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the payment instruction to core-banking-payments. There is no cancel operation on a payment
 * order: {@code PaymentStatusEnum} is INITIATED, COMPLETED, or FAILED. UNMATCHED is stored as FAILED
 * with error code UNMATCHED. Schedule status CANCELLED is a schedule row, not a payment recall.
 */
public final class HttpPaymentRecordAdapter implements PaymentRecordPort {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<UUID, StoredPayment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byReference = new ConcurrentHashMap<>();

    public HttpPaymentRecordAdapter(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    HttpPaymentRecordAdapter(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
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
        ObjectNode order = json.createObjectNode();
        order.put("payerAccountId", String.valueOf(payment.sourceAccountId() == null ? payment.destinationAccountId() : payment.sourceAccountId()));
        order.put("paymentMethodId", String.valueOf(payment.paymentMethodId()));
        order.put("beneficiaryName", "Beneficiary");
        order.put("paymentType", coreType(payment));
        order.put("orderDate", LocalDateTime.now().withNano(0).toString());
        order.put("status", coreStatus(payment.status()));
        order.put("amount", payment.amount());
        order.put("currencyCode", payment.currency());
        order.put("referenceNumber", trim(payment.idempotencyKey(), 35));
        order.put("endToEndId", trim(payment.idempotencyKey(), 35));
        order.put("remittanceInformation", payment.scheme().name());
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/payment-orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(order.toString())));
        if (created.statusCode() >= 400) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "core-banking-payments rejected the order.");
        }
        ObjectNode instruction = json.createObjectNode();
        instruction.put("paymentOrderId", payment.paymentId().toString());
        instruction.put("instructionId", trim(payment.idempotencyKey(), 50));
        instruction.put("instructionType", "IMMEDIATE");
        instruction.put("instructionDate", LocalDateTime.now().withNano(0).toString());
        instruction.put("instructionStatus", coreStatus(payment.status()));
        instruction.put("externalReference", payment.thirdPartyReference() == null ? payment.idempotencyKey() : payment.thirdPartyReference());
        instruction.put("instructionNotes", payment.status().name());
        if (payment.failure() != null) {
            instruction.put("errorCode", trim(payment.failure().name(), 20));
        }
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/payment-orders/" + payment.paymentId() + "/instructions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(instruction.toString())));
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

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "core-banking-payments could not be reached.");
        }
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
