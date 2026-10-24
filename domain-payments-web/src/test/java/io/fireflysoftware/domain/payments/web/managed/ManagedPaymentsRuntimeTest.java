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

package io.fireflysoftware.domain.payments.web.managed;

import static org.assertj.core.api.Assertions.assertThat;

import io.fireflysoftware.domain.payments.web.DomainPaymentsApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;

@SpringBootTest(
        classes = DomainPaymentsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "firefly.eda.enabled=false",
            "firefly.stepevents.enabled=false"
        })
class ManagedPaymentsRuntimeTest {
    @LocalServerPort int port;

    @Test
    void managedCreateCannotInheritLegacySandboxOrUsernameAuthority() throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/tenants/owned/internal-payments"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("X-Firefly-Username", "operator")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    }

    @Test
    void unconfiguredManagedReadDeniesForgedBearerBeforeLegacyAdapters() throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/v1/tenants/owned/internal-payments/"
                                                + UUID.randomUUID()))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer forged")
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
    }

    @Test
    void sagaDiagnosticsAreNotAnUnauthenticatedFinancialReadPath() throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/api/orchestration/executions"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    }

    @Test
    void openApiContractCanBeGeneratedWithoutFinancialAuthority() throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("createManagedPaymentJourney", "getManagedPaymentJourney");
    }

    @Test
    void publicContractRuleDoesNotPermitUnknownApplicationRoutes() throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + port + "/owned-unknown-route"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
    }

    @Test
    void nativeProducerYamlContractIsAlsoGetOnlyPublic() throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v3/api-docs.yaml"))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
        var response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body())
                .contains("createManagedPaymentJourney", "getManagedPaymentJourney");
    }
}
