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

package io.fireflysoftware.domain.payments.core.sandbox;

import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PositionKind;
import io.fireflysoftware.domain.payments.PositionMovement;
import io.fireflysoftware.domain.payments.core.ports.AccountPositionPort;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SandboxAccountPositionAdapter implements AccountPositionPort {

    private final ConcurrentHashMap<String, PositionMovement> applied = new ConcurrentHashMap<>();
    private final AtomicInteger failCredits = new AtomicInteger();

    public void failNextCredit() {
        failCredits.incrementAndGet();
    }

    @Override
    public void apply(PositionMovement movement) {
        if (applied.containsKey(movement.idempotencyKey())) {
            return;
        }
        if (movement.kind() == PositionKind.CUSTOMER_CREDIT && failCredits.get() > 0) {
            failCredits.decrementAndGet();
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED, "The customer credit was rejected.");
        }
        applied.put(movement.idempotencyKey(), movement);
    }

    @Override
    public void reverse(String originalKey, String reversalKey) {
        if (applied.containsKey(reversalKey)) {
            return;
        }
        PositionMovement original = applied.get(originalKey);
        if (original != null) {
            applied.put(reversalKey, original);
        }
    }

    public List<PositionMovement> movements() {
        return List.copyOf(applied.values());
    }
}
