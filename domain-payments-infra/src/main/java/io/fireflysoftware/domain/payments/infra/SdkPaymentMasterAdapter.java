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

import io.fireflysoftware.common.reference.master.data.sdk.api.CurrenciesApi;
import io.fireflysoftware.common.reference.master.data.sdk.invoker.ApiClient;
import io.fireflysoftware.common.reference.master.data.sdk.model.CurrencyDTO;
import io.fireflysoftware.common.reference.master.data.sdk.model.FilterRequestCurrencyDTO;
import io.fireflysoftware.common.reference.master.data.sdk.model.PaginationRequest;
import io.fireflysoftware.common.reference.master.data.sdk.model.PaginationResponseCurrencyDTO;
import io.fireflysoftware.domain.payments.MasterData;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.core.ports.PaymentMasterPort;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Reads ISO currency codes from core-common-reference-master-data through its SDK. */
public final class SdkPaymentMasterAdapter implements PaymentMasterPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final CurrenciesApi currencies;

    public SdkPaymentMasterAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new CurrenciesApi(client));
    }

    SdkPaymentMasterAdapter(CurrenciesApi currencies) {
        this.currencies = currencies;
    }

    @Override
    public MasterData current() {
        try {
            FilterRequestCurrencyDTO request = new FilterRequestCurrencyDTO()
                    .pagination(new PaginationRequest().pageNumber(0).pageSize(500));
            PaginationResponseCurrencyDTO response = currencies.filterCurrencies(request, null).block(TIMEOUT);
            if (response == null || response.getContent() == null) {
                throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters returned no currencies.");
            }
            List<String> codes = new ArrayList<>();
            for (CurrencyDTO row : response.getContent()) {
                if (row != null && row.getIsoCode() != null && !row.getIsoCode().isBlank()) {
                    codes.add(row.getIsoCode());
                }
            }
            if (codes.isEmpty()) {
                throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters returned no currencies.");
            }
            return new MasterData(List.copyOf(codes));
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters rejected the currency read.");
        } catch (RuntimeException ex) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters could not be read.");
        }
    }

    private static String strip(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters base URL is required.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
