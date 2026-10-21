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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the generated challenge and returns that value to the caller.
 * It does not send SMS, push, or email, and it does not open a socket.
 * An installer replaces this class with a live delivery adapter.
 */
public final class SandboxChallengeDeliveryAdapter implements ChallengeDeliveryPort {

    private final ConcurrentHashMap<UUID, String> inbox = new ConcurrentHashMap<>();

    @Override
    public Delivery deliver(UUID challengeId, String challengeValue) {
        inbox.put(challengeId, challengeValue);
        return new Delivery(challengeId, challengeValue);
    }

    public Optional<String> inbox(UUID challengeId) {
        return Optional.ofNullable(inbox.get(challengeId));
    }
}
