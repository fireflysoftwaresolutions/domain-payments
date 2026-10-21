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

package io.fireflysoftware.domain.payments;

import java.math.BigDecimal;
import java.util.UUID;

public final class SyncMessages {

    private SyncMessages() {
    }

    public record SyncResult(boolean accepted, String reference, String reason) {

        public static SyncResult accepted(String reference) {
            return new SyncResult(true, reference, null);
        }

        public static SyncResult rejected(String reason) {
            return new SyncResult(false, null, reason);
        }
    }

    public record OutboundInstruction(
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            UUID sourceAccountId,
            String debtorName,
            String creditorName,
            String creditorIban,
            String creditorBic,
            RailKind railKind,
            PspInstrument instrument,
            UUID nostroAccountId
    ) {
    }

    public record InboundInstruction(
            String thirdPartyReference,
            BigDecimal amount,
            String currency,
            UUID destinationAccountId,
            RailKind railKind,
            PspInstrument instrument
    ) {
    }

    public record CompensationRequest(
            String reference,
            BigDecimal amount,
            String currency,
            RailKind railKind,
            PspInstrument instrument
    ) {
    }
}
