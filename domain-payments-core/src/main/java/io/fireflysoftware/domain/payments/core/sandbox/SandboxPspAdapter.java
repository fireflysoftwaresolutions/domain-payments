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
import io.fireflysoftware.domain.payments.PspInstrument;
import io.fireflysoftware.domain.payments.SyncMessages.CompensationRequest;
import io.fireflysoftware.domain.payments.SyncMessages.InboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.domain.payments.core.ports.PspPort;

import java.util.concurrent.atomic.AtomicInteger;

public final class SandboxPspAdapter implements PspPort {

    private final AtomicInteger outboundCalls = new AtomicInteger();
    private final AtomicInteger inboundCalls = new AtomicInteger();
    private boolean supportsReturn = true;

    public int outboundCalls() {
        return outboundCalls.get();
    }

    public int inboundCalls() {
        return inboundCalls.get();
    }

    @Override
    public SyncResult sendOutbound(OutboundInstruction instruction) {
        outboundCalls.incrementAndGet();
        if (instruction.instrument() != PspInstrument.PAYOUT) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                    "An outbound PSP movement uses the payout instrument.");
        }
        return SyncResult.accepted(SandboxPaymentRailAdapter.reference(instruction.idempotencyKey()));
    }

    @Override
    public SyncResult acceptInbound(InboundInstruction instruction) {
        inboundCalls.incrementAndGet();
        if (instruction.instrument() == PspInstrument.PAYOUT) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                    "A card collection is not a payout.");
        }
        return SyncResult.accepted(instruction.thirdPartyReference());
    }

    @Override
    public boolean supportsReturn() {
        return supportsReturn;
    }

    @Override
    public SyncResult returnOrReject(CompensationRequest request) {
        return SyncResult.accepted(request.reference());
    }
}
