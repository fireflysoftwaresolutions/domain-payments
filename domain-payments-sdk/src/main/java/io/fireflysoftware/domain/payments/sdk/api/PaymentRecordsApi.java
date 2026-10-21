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

package io.fireflysoftware.domain.payments.sdk.api;

import io.fireflysoftware.domain.payments.sdk.invoker.ApiClient;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Domain client for a standing-order run and a direct-debit mandate.
 * A journey uses this class. It does not call the payments core.
 */
public class PaymentRecordsApi {

    private final WebClient webClient;
    private final String basePath;

    public PaymentRecordsApi(ApiClient apiClient) {
        this.webClient = apiClient.getWebClient();
        this.basePath = apiClient.getBasePath();
    }

    public Mono<UUID> runStandingOrder(UUID paymentScheduleId, BigDecimal amount, String currency,
                                        LocalDate valueDate, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentScheduleId", paymentScheduleId);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("valueDate", valueDate);
        return post("/api/v1/payment-records/standing-orders", body, key);
    }

    public Mono<UUID> createDirectDebit(UUID debtorAccountId, UUID creditorPartyId, BigDecimal amountLimit,
                                         String currency, String reference, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debtorAccountId", debtorAccountId);
        body.put("creditorPartyId", creditorPartyId);
        body.put("amountLimit", amountLimit);
        body.put("currency", currency);
        body.put("reference", reference);
        return post("/api/v1/payment-records/direct-debits", body, key);
    }

    private Mono<UUID> post(String path, Map<String, Object> body, String key) {
        return webClient.post()
                .uri(basePath + path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", key)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(RecordedId.class)
                .map(RecordedId::id);
    }

    public record RecordedId(UUID id) {
    }
}
