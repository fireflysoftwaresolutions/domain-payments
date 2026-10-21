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
import io.fireflysoftware.domain.payments.MasterData;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.core.ports.PaymentMasterPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Reads ISO currency codes from core-common-reference-master-data {@code GET /api/v1/currencies}. */
public final class HttpPaymentMasterAdapter implements PaymentMasterPort {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public HttpPaymentMasterAdapter(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    HttpPaymentMasterAdapter(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
    }

    @Override
    public MasterData current() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/currencies")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters rejected the currency read.");
            }
            List<String> codes = new ArrayList<>();
            collect(json.readTree(response.body()), codes);
            if (codes.isEmpty()) {
                throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters returned no currencies.");
            }
            return new MasterData(List.copyOf(codes));
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters could not be read.");
        }
    }

    private void collect(JsonNode node, List<String> codes) {
        if (node == null) {
            return;
        }
        if (node.isObject() && node.hasNonNull("isoCode")) {
            codes.add(node.get("isoCode").asText());
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                collect(child, codes);
            }
        }
    }
}
