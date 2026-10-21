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
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.ClearingAccounts;
import io.fireflysoftware.domain.payments.PaymentsCatalog.CurrencyPair;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;
import io.fireflysoftware.domain.payments.PspInstrument;
import io.fireflysoftware.domain.payments.RailKind;
import io.fireflysoftware.domain.payments.core.ports.PaymentConfigurationPort;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the payments catalog from core-common-config-mgmt channel parameter {@code payments.catalog}.
 */
public final class HttpPaymentConfigurationAdapter implements PaymentConfigurationPort {

    static final String PARAMETER_KEY = "payments.catalog";

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public HttpPaymentConfigurationAdapter(String baseUrl) {
        this(baseUrl, HttpClient.newHttpClient());
    }

    HttpPaymentConfigurationAdapter(String baseUrl, HttpClient http) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
    }

    @Override
    public PaymentsCatalog catalog() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/channel-config-parameters/filter"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"page\":0,\"size\":200}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration service rejected the catalog read.");
            }
            JsonNode root = json.readTree(response.body());
            String raw = findParameter(root);
            return parse(json.readTree(raw));
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration catalog could not be read.");
        }
    }

    private String findParameter(JsonNode node) {
        if (node == null) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration catalog is missing.");
        }
        if (node.isObject() && PARAMETER_KEY.equals(node.path("parameterKey").asText())) {
            return node.path("parameterValue").asText();
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                try {
                    return findParameter(child);
                } catch (PaymentFlowException ignored) {
                    // keep searching the document
                }
            }
        }
        throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Channel parameter payments.catalog was not found.");
    }

    private PaymentsCatalog parse(JsonNode catalog) {
        List<SchemeRouting> routes = new ArrayList<>();
        for (JsonNode route : catalog.path("routes")) {
            RailKind rail = route.path("railKind").isMissingNode() || route.path("railKind").isNull()
                    ? null : RailKind.valueOf(route.path("railKind").asText());
            PspInstrument instrument = route.path("pspInstrument").isMissingNode() || route.path("pspInstrument").isNull()
                    ? null : PspInstrument.valueOf(route.path("pspInstrument").asText());
            routes.add(new SchemeRouting(
                    PaymentScheme.valueOf(route.path("scheme").asText()),
                    MovementRoute.valueOf(route.path("route").asText()),
                    rail,
                    instrument,
                    route.path("spaceMovement").asBoolean(false),
                    new BigDecimal(route.path("maxAmount").asText())));
        }
        List<CurrencyPair> pairs = new ArrayList<>();
        for (JsonNode pair : catalog.path("currencyPairs")) {
            pairs.add(new CurrencyPair(pair.path("base").asText(), pair.path("quote").asText()));
        }
        List<String> spaceTypes = new ArrayList<>();
        for (JsonNode type : catalog.path("spaceTypes")) {
            spaceTypes.add(type.asText());
        }
        JsonNode clearing = catalog.path("clearingAccounts");
        return new PaymentsCatalog(routes, pairs, spaceTypes, new ClearingAccounts(
                UUID.fromString(clearing.path("outboundClearingAccountId").asText()),
                UUID.fromString(clearing.path("inboundClearingAccountId").asText()),
                UUID.fromString(clearing.path("suspenseAccountId").asText()),
                UUID.fromString(clearing.path("nostroAccountId").asText())));
    }
}
