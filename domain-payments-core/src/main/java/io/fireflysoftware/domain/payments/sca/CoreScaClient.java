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

import java.util.UUID;

/**
 * Create and validate against core-common-sca-mgmt, which stores the challenge.
 * {@link SdkCoreScaClient} is the live client and uses {@code core-common-sca-mgmt-sdk}.
 * {@link SandboxCoreScaClient} replaces it only when no core process is running.
 */
public interface CoreScaClient {

    CreatedChallenge create(UUID paymentId, UUID partyId);

    boolean validate(UUID operationId, UUID challengeId, String response);

    void expire(UUID operationId, UUID challengeId);

    record CreatedChallenge(UUID operationId, UUID challengeId, String challengeCode) {
    }
}
