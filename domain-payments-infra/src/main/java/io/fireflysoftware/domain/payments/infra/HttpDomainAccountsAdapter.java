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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls domain-banking-accounts. Space create, update, and delete stay in that domain.
 * A movement between existing spaces is confirmed here and posted on the ledger by the saga.
 * This adapter does not open a rail or a PSP.
 */
public final class HttpDomainAccountsAdapter implements AccountPositionPort {

    private final String baseUrl;
    private final HttpClient http;
    private final ConcurrentHashMap<String, PositionMovement> applied = new ConcurrentHashMap<>();

    public HttpDomainAccountsAdapter(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    HttpDomainAccountsAdapter(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
    }

    @Override
    public void apply(PositionMovement movement) {
        if (applied.containsKey(movement.idempotencyKey())) {
            return;
        }
        if (movement.kind() == PositionKind.SPACE_TRANSFER) {
            String body = get("/api/v1/backoffice/accounts/" + movement.fromAccountId() + "/spaces");
            if (!body.contains(movement.fromSpaceId().toString()) || !body.contains(movement.toSpaceId().toString())) {
                throw new PaymentFlowException(PaymentFailure.POSITION_FAILED,
                        "domain-banking-accounts does not list both account spaces.");
            }
        } else if (movement.fromAccountId() != null) {
            get("/api/v1/backoffice/accounts/" + movement.fromAccountId());
        } else if (movement.toAccountId() != null) {
            get("/api/v1/backoffice/accounts/" + movement.toAccountId());
        }
        applied.put(movement.idempotencyKey(), movement);
    }

    @Override
    public void reverse(String originalKey, String reversalKey) {
        applied.putIfAbsent(reversalKey, applied.get(originalKey));
    }

    private String get(String path) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new PaymentFlowException(PaymentFailure.POSITION_FAILED,
                        "domain-banking-accounts returned status " + response.statusCode());
            }
            return response.body() == null ? "" : response.body();
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED, "domain-banking-accounts could not be reached.");
        }
    }
}
