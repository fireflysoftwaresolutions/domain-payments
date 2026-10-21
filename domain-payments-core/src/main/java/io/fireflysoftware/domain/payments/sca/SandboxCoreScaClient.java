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

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No-socket stand-in for core-common-sca-mgmt. An installer replaces this class with
 * {@link SdkCoreScaClient}. It is not used when the live client is selected.
 */
public final class SandboxCoreScaClient implements CoreScaClient {

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<UUID, String> codes = new ConcurrentHashMap<>();

    @Override
    public CreatedChallenge create(UUID paymentId, UUID partyId) {
        String code = Integer.toString(100000 + random.nextInt(900000));
        UUID operationId = UUID.randomUUID();
        UUID challengeId = UUID.randomUUID();
        codes.put(challengeId, code);
        return new CreatedChallenge(operationId, challengeId, code);
    }

    @Override
    public boolean validate(UUID operationId, UUID challengeId, String response) {
        String code = codes.get(challengeId);
        return code != null && code.equals(response);
    }

    @Override
    public void expire(UUID operationId, UUID challengeId) {
        codes.remove(challengeId);
    }
}
