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
import io.fireflysoftware.domain.payments.PositionKind;
import io.fireflysoftware.domain.payments.PositionMovement;
import io.fireflysoftware.domain.payments.core.ports.AccountPositionPort;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Updates the customer position in core-banking-accounts.
 * Space movement calls {@code POST /api/v1/account-spaces/transfer}.
 */
public final class HttpAccountPositionAdapter implements AccountPositionPort {

    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();
    private final java.util.concurrent.ConcurrentHashMap<String, PositionMovement> applied = new java.util.concurrent.ConcurrentHashMap<>();

    public HttpAccountPositionAdapter(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void apply(PositionMovement movement) {
        if (applied.putIfAbsent(movement.idempotencyKey(), movement) != null) {
            return;
        }
        if (movement.kind() == PositionKind.SPACE_TRANSFER) {
            String path = "/api/v1/account-spaces/transfer?fromSpaceId=" + movement.fromSpaceId()
                    + "&toSpaceId=" + movement.toSpaceId()
                    + "&amount=" + movement.amount().toPlainString();
            exchange(HttpRequest.newBuilder(URI.create(baseUrl + path)).POST(HttpRequest.BodyPublishers.noBody()).build());
            return;
        }
        if (movement.kind() == PositionKind.ACCOUNT_TRANSFER) {
            postBalance(movement.fromAccountId(), movement.amount().negate(), movement.idempotencyKey());
            postBalance(movement.toAccountId(), movement.amount(), movement.idempotencyKey());
            return;
        }
        if (movement.kind() == PositionKind.CUSTOMER_DEBIT) {
            postBalance(movement.fromAccountId(), movement.amount().negate(), movement.idempotencyKey());
            return;
        }
        postBalance(movement.toAccountId(), movement.amount(), movement.idempotencyKey());
    }

    @Override
    public void reverse(String originalKey, String reversalKey) {
        if (applied.containsKey(reversalKey)) {
            return;
        }
        PositionMovement original = applied.get(originalKey);
        if (original == null) {
            return;
        }
        BigDecimal opposite = original.amount().negate();
        if (original.kind() == PositionKind.ACCOUNT_TRANSFER) {
            postBalance(original.fromAccountId(), original.amount(), reversalKey);
            postBalance(original.toAccountId(), opposite, reversalKey);
        } else if (original.kind() == PositionKind.CUSTOMER_DEBIT) {
            postBalance(original.fromAccountId(), original.amount(), reversalKey);
        } else if (original.kind() == PositionKind.CUSTOMER_CREDIT) {
            postBalance(original.toAccountId(), opposite, reversalKey);
        } else {
            String path = "/api/v1/account-spaces/transfer?fromSpaceId=" + original.toSpaceId()
                    + "&toSpaceId=" + original.fromSpaceId()
                    + "&amount=" + original.amount().toPlainString();
            exchange(HttpRequest.newBuilder(URI.create(baseUrl + path)).POST(HttpRequest.BodyPublishers.noBody()).build());
        }
        applied.put(reversalKey, original);
    }

    private void postBalance(UUID accountId, BigDecimal amount, String key) {
        String body = "{\"accountId\":\"" + accountId + "\",\"balanceType\":\"AVAILABLE\",\"balanceAmount\":"
                + amount.toPlainString() + ",\"asOfDatetime\":\"" + LocalDateTime.now().withNano(0) + "\"}";
        exchange(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/accounts/" + accountId + "/balances"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private void exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new PaymentFlowException(PaymentFailure.POSITION_FAILED,
                        "Accounts returned HTTP " + response.statusCode() + ".");
            }
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED, "The accounts call failed: " + ex.getMessage());
        }
    }
}
