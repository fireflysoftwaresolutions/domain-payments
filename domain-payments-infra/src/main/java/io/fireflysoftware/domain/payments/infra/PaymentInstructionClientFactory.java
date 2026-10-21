/*
 * Copyright 2026 Firefly Software Foundation
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

import io.fireflysoftware.core.banking.payments.sdk.api.DirectDebitMandateApi;
import io.fireflysoftware.core.banking.payments.sdk.api.StandingOrderRunApi;
import io.fireflysoftware.core.banking.payments.sdk.invoker.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core payments client for standing orders and direct debits.
 * Constructing the client does not open a socket.
 */
@Configuration
public class PaymentInstructionClientFactory {

    @Bean
    public StandingOrderRunApi standingOrderRunApi(
            @Value("${firefly.payments.payments-core-base-url:http://localhost:8080}") String baseUrl) {
        return new StandingOrderRunApi(client(baseUrl));
    }

    @Bean
    public DirectDebitMandateApi directDebitMandateApi(
            @Value("${firefly.payments.payments-core-base-url:http://localhost:8080}") String baseUrl) {
        return new DirectDebitMandateApi(client(baseUrl));
    }

    private static ApiClient client(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        return client;
    }
}
