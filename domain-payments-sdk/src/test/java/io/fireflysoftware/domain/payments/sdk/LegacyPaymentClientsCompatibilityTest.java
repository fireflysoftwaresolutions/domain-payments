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

package io.fireflysoftware.domain.payments.sdk;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;

import io.fireflysoftware.domain.payments.sdk.api.*;
import io.fireflysoftware.domain.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.domain.payments.sdk.model.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.*;
import java.time.*;
import java.util.*;

class LegacyPaymentClientsCompatibilityTest {
    static final Duration WAIT = Duration.ofSeconds(5);

    record Request(String method, String path, String auth, String key, JsonNode body) {}

    static final class Fixture implements AutoCloseable {
        final UUID payment = UUID.randomUUID(),
                account = UUID.randomUUID(),
                party = UUID.randomUUID(),
                schedule = UUID.randomUUID();
        final List<Request> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean reject;

        Fixture() throws Exception {
            server.createContext(
                    "/",
                    e -> {
                        var raw = e.getRequestBody().readAllBytes();
                        String path = e.getRequestURI().getPath();
                        requests.add(
                                new Request(
                                        e.getRequestMethod(),
                                        path,
                                        e.getRequestHeaders().getFirst("Authorization"),
                                        e.getRequestHeaders().getFirst("X-Idempotency-Key"),
                                        raw.length == 0 ? null : json.readTree(raw)));
                        Object response =
                                path.startsWith("/api/v1/payments/")
                                        ? Map.of(
                                                "paymentId",
                                                payment,
                                                "status",
                                                "COMPLETED",
                                                "scheme",
                                                "INTERNAL_TRANSFER",
                                                "amount",
                                                new BigDecimal("1.25"),
                                                "currency",
                                                "USD")
                                        : e.getRequestMethod().equals("POST")
                                                ? Map.of("id", payment)
                                                : Map.of("id", payment, "currency", "USD");
                        byte[] bytes =
                                json.writeValueAsBytes(
                                        reject
                                                ? Map.of("error", "OWNED_TRANSPORT_FAILURE")
                                                : response);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(reject ? 503 : 200, bytes.length);
                        e.getResponseBody().write(bytes);
                        e.close();
                    });
            server.start();
        }

        ApiClient client(String bearer) {
            var client =
                    new ApiClient()
                            .setBasePath("http://127.0.0.1:" + server.getAddress().getPort());
            client.addDefaultHeader("Authorization", bearer);
            return client;
        }

        void wire(
                String path,
                String method,
                String token,
                String key,
                List<? extends Mono<?>> responses) {
            int start = requests.size();
            for (var response : responses) assertThat(response.block(WAIT)).isNotNull();
            assertThat(requests).hasSize(start + 3);
            for (var request : requests.subList(start, start + 3)) {
                assertThat(request.path()).isEqualTo(path);
                assertThat(request.method()).isEqualTo(method);
                assertThat(request.auth()).isEqualTo(token);
                assertThat(request.key()).isEqualTo(key);
            }
        }

        public void close() {
            server.stop(0);
        }
    }

    @Test
    void generatedLegacyPaymentNamesPreserveAllThreeGeneratedResponseVariants() throws Exception {
        try (var f = new Fixture()) {
            String token = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
            var client = f.client(token);
            var api = new PaymentsApi(client);
            assertThat(api.getApiClient()).isSameAs(client);
            var input =
                    new MovementCommand()
                            .scheme(MovementCommand.SchemeEnum.INTERNAL_TRANSFER)
                            .amount(new BigDecimal("1.25"))
                            .currency("USD")
                            .sourceAccountId(f.account);
            f.wire(
                    "/api/v1/payments/tries",
                    "POST",
                    token,
                    key,
                    List.of(
                            api.tryPayment(input, key),
                            api.tryPaymentWithHttpInfo(input, key),
                            api.tryPaymentWithResponseSpec(input, key)
                                    .bodyToMono(MovementResult.class)));
            for (var request : f.requests) {
                assertThat(request.body().path("scheme").asText()).isEqualTo("INTERNAL_TRANSFER");
                assertThat(request.body().path("amount").decimalValue())
                        .isEqualByComparingTo("1.25");
            }
            var confirmation = new Confirmation().response("sandbox-retained-reference");
            f.wire(
                    "/api/v1/payments/" + f.payment + "/confirmations",
                    "POST",
                    token,
                    key,
                    List.of(
                            api.confirmPayment(f.payment, confirmation, key),
                            api.confirmPaymentWithHttpInfo(f.payment, confirmation, key),
                            api.confirmPaymentWithResponseSpec(f.payment, confirmation, key)
                                    .bodyToMono(MovementResult.class)));
            assertThat(f.requests.getLast().body().path("response").asText())
                    .isEqualTo("sandbox-retained-reference");
            f.wire(
                    "/api/v1/payments/" + f.payment + "/cancellations",
                    "POST",
                    token,
                    key,
                    List.of(
                            api.cancelPayment(f.payment, key),
                            api.cancelPaymentWithHttpInfo(f.payment, key),
                            api.cancelPaymentWithResponseSpec(f.payment, key)
                                    .bodyToMono(MovementResult.class)));
            f.wire(
                    "/api/v1/payments/" + f.payment,
                    "GET",
                    token,
                    key,
                    List.of(
                            api.readPayment(f.payment, key),
                            api.readPaymentWithHttpInfo(f.payment, key),
                            api.readPaymentWithResponseSpec(f.payment, key)
                                    .bodyToMono(MovementResult.class)));
        }
    }

    @Test
    void sourceOwnedRecordNamesPreserveSingleUuidReadsAndExactBodies() throws Exception {
        try (var f = new Fixture()) {
            String token = UUID.randomUUID().toString(), key = UUID.randomUUID().toString();
            var client = f.client(token);
            var api = new PaymentRecordsApi();
            api.setApiClient(client);
            assertThat(api.getApiClient()).isSameAs(client);
            var debit =
                    new DirectDebitBody()
                            .debtorAccountId(f.account)
                            .creditorPartyId(f.party)
                            .amountLimit(new BigDecimal("12.34"))
                            .currency("USD")
                            .reference("Sandbox <reference> & exact");
            f.wire(
                    "/api/v1/payment-records/direct-debits",
                    "POST",
                    token,
                    key,
                    List.of(
                            api.createDirectDebit(debit, key),
                            api.createDirectDebitWithHttpInfo(debit, key),
                            api.createDirectDebitWithResponseSpec(debit, key)
                                    .bodyToMono(RecordedId.class)));
            assertThat(f.requests.getLast().body().path("amountLimit").decimalValue())
                    .isEqualByComparingTo("12.34");
            assertThat(f.requests.getLast().body().path("reference").asText())
                    .isEqualTo(debit.getReference());
            var standing =
                    new StandingOrderBody()
                            .paymentScheduleId(f.schedule)
                            .amount(new BigDecimal("2.50"))
                            .currency("USD")
                            .valueDate(LocalDate.of(2026, 10, 22));
            f.wire(
                    "/api/v1/payment-records/standing-orders",
                    "POST",
                    token,
                    key,
                    List.of(
                            api.runStandingOrder(standing, key),
                            api.runStandingOrderWithHttpInfo(standing, key),
                            api.runStandingOrderWithResponseSpec(standing, key)
                                    .bodyToMono(RecordedId.class)));
            assertThat(f.requests.getLast().body().path("paymentScheduleId").asText())
                    .isEqualTo(f.schedule.toString());
            assertThat(f.requests.getLast().body().path("valueDate").asText())
                    .isEqualTo("2026-10-22");
            f.wire(
                    "/api/v1/payment-records/direct-debits/" + f.payment,
                    "GET",
                    token,
                    null,
                    List.of(
                            api.readDirectDebit(f.payment),
                            api.readDirectDebitWithHttpInfo(f.payment),
                            api.readDirectDebitWithResponseSpec(f.payment)
                                    .bodyToMono(DirectDebitView.class)));
            f.wire(
                    "/api/v1/payment-records/standing-orders/" + f.payment,
                    "GET",
                    token,
                    null,
                    List.of(
                            api.readStandingOrder(f.payment),
                            api.readStandingOrderWithHttpInfo(f.payment),
                            api.readStandingOrderWithResponseSpec(f.payment)
                                    .bodyToMono(StandingOrderView.class)));
        }
    }

    @Test
    void independentClientConfigurationAndNativeErrorsArePreserved() throws Exception {
        try (var f = new Fixture()) {
            String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
            var a = new PaymentsApi(f.client(first));
            var b = new PaymentsApi();
            b.setApiClient(f.client(second));
            Mono.zip(a.readPayment(f.payment, null), b.readPayment(f.payment, null)).block(WAIT);
            assertThat(f.requests.stream().map(Request::auth))
                    .containsExactlyInAnyOrder(first, second);
            f.reject = true;
            assertThatThrownBy(() -> a.readPayment(f.payment, null).block(WAIT))
                    .isInstanceOfSatisfying(
                            WebClientResponseException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        }
    }
}
