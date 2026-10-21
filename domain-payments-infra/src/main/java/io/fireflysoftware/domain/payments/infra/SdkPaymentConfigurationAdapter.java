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
import io.fireflysoftware.common.config.sdk.api.ChannelConfigParametersApi;
import io.fireflysoftware.common.config.sdk.invoker.ApiClient;
import io.fireflysoftware.common.config.sdk.model.ChannelConfigParameterDTO;
import io.fireflysoftware.common.config.sdk.model.FilterRequestChannelConfigParameterDTO;
import io.fireflysoftware.common.config.sdk.model.PaginationRequest;
import io.fireflysoftware.common.config.sdk.model.PaginationResponse;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the payments catalog from core-common-config-mgmt channel parameter {@code payments.catalog}.
 */
public final class SdkPaymentConfigurationAdapter implements PaymentConfigurationPort {

    static final String PARAMETER_KEY = "payments.catalog";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ChannelConfigParametersApi parameters;
    private final ObjectMapper json = new ObjectMapper();

    public SdkPaymentConfigurationAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new ChannelConfigParametersApi(client));
    }

    SdkPaymentConfigurationAdapter(ChannelConfigParametersApi parameters) {
        this.parameters = parameters;
    }

    @Override
    public PaymentsCatalog catalog() {
        try {
            ChannelConfigParameterDTO filter = new ChannelConfigParameterDTO().parameterKey(PARAMETER_KEY);
            FilterRequestChannelConfigParameterDTO request = new FilterRequestChannelConfigParameterDTO()
                    .filters(filter)
                    .pagination(new PaginationRequest().pageNumber(0).pageSize(200));
            PaginationResponse response = parameters.filter(request, null).block(TIMEOUT);
            if (response == null || response.getContent() == null) {
                throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration catalog is missing.");
            }
            String raw = null;
            for (Object item : response.getContent()) {
                ChannelConfigParameterDTO row = json.convertValue(item, ChannelConfigParameterDTO.class);
                if (row != null && PARAMETER_KEY.equals(row.getParameterKey())) {
                    raw = row.getParameterValue();
                    break;
                }
            }
            if (raw == null || raw.isBlank()) {
                throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                        "Channel parameter payments.catalog was not found.");
            }
            return parse(json.readTree(raw));
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                    "Configuration service rejected the catalog read.");
        } catch (Exception ex) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration catalog could not be read.");
        }
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

    private static String strip(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration base URL is required.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
