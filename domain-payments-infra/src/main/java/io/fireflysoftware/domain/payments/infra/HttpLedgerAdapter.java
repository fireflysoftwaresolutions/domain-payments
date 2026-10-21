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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Posts a balanced double entry to core-banking-ledger.
 * {@code POST /api/v1/transactions} then {@code POST /api/v1/transactions/{id}/legs}.
 * Idempotency uses {@code GET /api/v1/transactions/by-reference/{externalReference}}.
 */
public final class HttpLedgerAdapter implements LedgerPort {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpLedgerAdapter(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient(), new ObjectMapper());
    }

    HttpLedgerAdapter(String baseUrl, HttpClient http, ObjectMapper json) {
        this.baseUrl = strip(baseUrl);
        this.http = http;
        this.json = json;
    }

    @Override
    public Optional<LedgerPosting> findByIdempotencyKey(String idempotencyKey) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/transactions/by-reference/" + idempotencyKey)).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() >= 400) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger lookup failed with status " + response.statusCode());
        }
        return Optional.of(readPosting(response.body(), idempotencyKey));
    }

    @Override
    public LedgerPosting post(String idempotencyKey, List<LedgerLeg> legs) {
        Optional<LedgerPosting> existing = findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        ObjectNode transaction = json.createObjectNode();
        transaction.put("externalReference", idempotencyKey);
        transaction.put("requestId", idempotencyKey);
        String now = LocalDateTime.now().withNano(0).toString();
        transaction.put("transactionDate", now);
        transaction.put("valueDate", now);
        transaction.put("bookingDate", now);
        transaction.put("transactionType", "TRANSFER");
        transaction.put("transactionStatus", "POSTED");
        transaction.put("totalAmount", legs.get(0).amount());
        transaction.put("currency", legs.get(0).currency());
        transaction.put("description", "Payment movement");
        transaction.put("accountId", legs.get(0).accountId().toString());
        if (idempotencyKey.endsWith(":reversal")) {
            transaction.put("relationType", "REVERSAL");
        }
        HttpResponse<String> created = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/transactions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(transaction.toString())));
        if (created.statusCode() >= 400) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger transaction was rejected with status " + created.statusCode());
        }
        UUID transactionId = readId(created.body(), "transactionId");
        for (LedgerLeg leg : legs) {
            ObjectNode body = json.createObjectNode();
            body.put("transactionId", transactionId.toString());
            body.put("accountId", leg.accountId().toString());
            if (leg.accountSpaceId() != null) {
                body.put("accountSpaceId", leg.accountSpaceId().toString());
            }
            body.put("legType", leg.side().name());
            body.put("amount", leg.amount());
            body.put("currency", leg.currency());
            body.put("valueDate", now);
            HttpResponse<String> legResponse = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/transactions/" + transactionId + "/legs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
            if (legResponse.statusCode() >= 400) {
                throw new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "Ledger leg was rejected with status " + legResponse.statusCode());
            }
        }
        return new LedgerPosting(transactionId, idempotencyKey, legs.get(0).currency(), List.copyOf(legs));
    }

    @Override
    public LedgerPosting reverse(LedgerPosting original, String reversalKey) {
        Optional<LedgerPosting> existing = findByIdempotencyKey(reversalKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        List<LedgerLeg> flipped = new ArrayList<>();
        for (LedgerLeg leg : original.legs()) {
            flipped.add(new LedgerLeg(leg.accountId(), leg.accountSpaceId(), leg.side().opposite(), leg.amount(), leg.currency()));
        }
        return post(reversalKey, flipped);
    }

    private LedgerPosting readPosting(String body, String key) {
        try {
            JsonNode node = json.readTree(body);
            UUID id = readId(body, "transactionId");
            String currency = node.path("currency").asText("EUR");
            return new LedgerPosting(id, key, currency, List.of());
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger response could not be read.");
        }
    }

    private UUID readId(String body, String field) {
        try {
            JsonNode node = json.readTree(body);
            String value = node.path(field).asText(null);
            if (value == null || value.isBlank()) {
                throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger response did not include " + field + ".");
            }
            return UUID.fromString(value);
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger response could not be read.");
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger call failed.");
        }
    }

    private static String strip(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
