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

package io.fireflysoftware.domain.payments.web;

import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.MovementResult;
import io.fireflysoftware.domain.payments.PaymentPhase;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.core.tcc.PaymentTccHub;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxLedgerAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentConfigurationAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentMasterAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRailAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRecordAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPspAdapter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentsControllerTest {

    @Test
    void tryThenConfirmUsesTheGeneratedChallenge() {
        PaymentTccHub hub = PaymentTccHub.sandbox(new SandboxLedgerAdapter(),
                new SandboxPaymentRecordAdapter(), new SandboxPaymentRailAdapter(), new SandboxPspAdapter(),
                new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
        PaymentsController controller = new PaymentsController(hub);
        UUID party = UUID.fromString("10000000-0000-4000-8000-0000000000aa");
        UUID contract = UUID.fromString("10000000-0000-4000-8000-000000000021");
        UUID method = UUID.fromString("10000000-0000-4000-8000-000000000031");
        UUID source = UUID.fromString("10000000-0000-4000-8000-000000000011");
        MovementResult tried = controller.tryPayment(new MovementCommand(PaymentScheme.DOMESTIC_OUTBOUND, "hub-out",
                new BigDecimal("25.00"), "EUR", party, contract, source, null, null, null, null, method,
                "Beneficiary", null, null, null)).block();
        assertNotNull(tried.challengeId());
        assertNotEquals("sandbox", tried.challengeValue());
        MovementResult confirmed = controller.confirm(tried.paymentId(), new PaymentsController.Confirmation(tried.challengeValue())).block();
        assertEquals(PaymentPhase.CONFIRMED, confirmed.phase());
        assertTrue(confirmed.thirdPartySynced());
    }
}
