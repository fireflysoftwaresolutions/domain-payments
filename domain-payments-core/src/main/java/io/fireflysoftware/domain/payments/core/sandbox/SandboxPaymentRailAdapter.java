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

import io.fireflysoftware.domain.payments.SyncMessages.CompensationRequest;
import io.fireflysoftware.domain.payments.SyncMessages.InboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.domain.payments.core.ports.PaymentRailPort;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SandboxPaymentRailAdapter implements PaymentRailPort {

    private final Set<String> rejectOutbound = ConcurrentHashMap.newKeySet();
    private final AtomicInteger outboundCalls = new AtomicInteger();
    private final AtomicInteger inboundCalls = new AtomicInteger();
    private final AtomicInteger returnCalls = new AtomicInteger();
    private boolean supportsReturn = true;

    public void rejectOutbound(String idempotencyKey) {
        rejectOutbound.add(idempotencyKey);
    }

    public void supportsReturn(boolean supported) {
        this.supportsReturn = supported;
    }

    public int outboundCalls() {
        return outboundCalls.get();
    }

    public int inboundCalls() {
        return inboundCalls.get();
    }

    public int returnCalls() {
        return returnCalls.get();
    }

    @Override
    public SyncResult sendOutbound(OutboundInstruction instruction) {
        outboundCalls.incrementAndGet();
        if (rejectOutbound.contains(instruction.idempotencyKey())) {
            return SyncResult.rejected("The rail rejected the payment.");
        }
        return SyncResult.accepted(reference(instruction.idempotencyKey()));
    }

    @Override
    public SyncResult acceptInbound(InboundInstruction instruction) {
        inboundCalls.incrementAndGet();
        return SyncResult.accepted(instruction.thirdPartyReference());
    }

    @Override
    public boolean supportsReturn() {
        return supportsReturn;
    }

    @Override
    public SyncResult returnOrReject(CompensationRequest request) {
        returnCalls.incrementAndGet();
        return SyncResult.accepted(request.reference());
    }

    static String reference(String key) {
        return "SBX-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
