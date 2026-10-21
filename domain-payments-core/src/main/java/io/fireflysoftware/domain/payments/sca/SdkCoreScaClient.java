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

package io.fireflysoftware.domain.payments.sca;

import io.fireflysoftware.common.sca.sdk.api.ScaChallengeControllerApi;
import io.fireflysoftware.common.sca.sdk.api.ScaOperationControllerApi;
import io.fireflysoftware.common.sca.sdk.invoker.ApiClient;
import io.fireflysoftware.common.sca.sdk.model.SCAChallengeDTO;
import io.fireflysoftware.common.sca.sdk.model.SCAOperationDTO;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Live SCA client. Creates the operation and challenge, and validates the response,
 * through {@code core-common-sca-mgmt-sdk}. This class does not keep a challenge store.
 */
public final class SdkCoreScaClient implements CoreScaClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ScaOperationControllerApi operations;
    private final ScaChallengeControllerApi challenges;
    private final SecureRandom random = new SecureRandom();

    public SdkCoreScaClient(ScaOperationControllerApi operations, ScaChallengeControllerApi challenges) {
        this.operations = operations;
        this.challenges = challenges;
    }

    public static SdkCoreScaClient at(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        return new SdkCoreScaClient(new ScaOperationControllerApi(client), new ScaChallengeControllerApi(client));
    }

    @Override
    public CreatedChallenge create(UUID paymentId, UUID partyId) {
        String offered = Integer.toString(100000 + random.nextInt(900000));
        SCAOperationDTO operation = body(operations.createOperation(new SCAOperationDTO()
                .referenceId(paymentId.toString())
                .operationType(SCAOperationDTO.OperationTypeEnum.TRANSFER)
                .partyId(partyId.toString())
                .status(SCAOperationDTO.StatusEnum.PENDING), paymentId.toString()));
        if (operation.getId() == null) {
            throw new IllegalStateException("core-common-sca-mgmt did not return an operation id.");
        }
        SCAChallengeDTO challenge = body(challenges.createChallenge(operation.getId(), new SCAChallengeDTO()
                .scaOperationId(operation.getId())
                .challengeCode(offered)
                .expiresAt(LocalDateTime.now().plusMinutes(5).withNano(0))
                .used(false), paymentId + ":challenge"));
        String code = challenge.getChallengeCode();
        if (challenge.getId() == null || code == null || code.isBlank() || "sandbox".equals(code)) {
            throw new IllegalStateException("core-common-sca-mgmt did not return a challenge code.");
        }
        return new CreatedChallenge(operation.getId(), challenge.getId(), code);
    }

    @Override
    public boolean validate(UUID operationId, UUID challengeId, String response) {
        try {
            String body = challenges.validateChallenge(operationId, challengeId, response, operationId + ":validate")
                    .block(TIMEOUT);
            return body != null;
        } catch (RuntimeException ex) {
            if (http(ex) != null) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public void expire(UUID operationId, UUID challengeId) {
        try {
            challenges.deleteChallenge(operationId, challengeId, operationId + ":expire").block(TIMEOUT);
        } catch (RuntimeException ex) {
            WebClientResponseException http = http(ex);
            if (http != null && http.getStatusCode().value() == 404) {
                return;
            }
            throw ex;
        }
    }

    private static <T> T body(Mono<T> call) {
        T value = call.block(TIMEOUT);
        if (value == null) {
            throw new IllegalStateException("core-common-sca-mgmt returned an empty response.");
        }
        return value;
    }

    private static WebClientResponseException http(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof WebClientResponseException http) {
                return http;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String strip(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
