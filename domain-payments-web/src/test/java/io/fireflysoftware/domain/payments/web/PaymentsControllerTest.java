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

import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.MovementResult;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.core.routing.SchemeRouter;
import io.fireflysoftware.domain.payments.core.saga.PaymentSaga;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxAccountPositionAdapter;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentsControllerTest {

    @Test
    void hubServesInternalOutboundAndInbound() {
        PaymentSaga saga = new PaymentSaga(new SandboxLedgerAdapter(), new SandboxAccountPositionAdapter(),
                new SandboxPaymentRecordAdapter(), new SandboxPaymentRailAdapter(), new SandboxPspAdapter(),
                new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter(), new SchemeRouter());
        PaymentsController controller = new PaymentsController(saga);
        UUID party = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID contract = UUID.fromString("10000000-0000-4000-8000-000000000021");
        UUID method = UUID.fromString("10000000-0000-4000-8000-000000000031");
        UUID source = UUID.fromString("10000000-0000-4000-8000-000000000011");
        UUID destination = UUID.fromString("10000000-0000-4000-8000-000000000012");
        BigDecimal amount = new BigDecimal("25.00");

        MovementResult internal = controller.move(new MovementCommand(PaymentScheme.INTERNAL_TRANSFER, "hub-internal",
                amount, "EUR", party, contract, source, destination, null, null, null, method, null, null, null, null));
        MovementResult outbound = controller.move(new MovementCommand(PaymentScheme.DOMESTIC_OUTBOUND, "hub-outbound",
                amount, "EUR", party, contract, source, null, null, null, null, method, "Beneficiary", null, null, null));
        MovementResult inbound = controller.move(new MovementCommand(PaymentScheme.RAIL_INBOUND, null,
                amount, "EUR", party, contract, null, destination, null, null, null, method, null, null, null, "HUB-IN-1"));

        assertEquals(PaymentStatus.COMPLETED, internal.status());
        assertFalse(internal.thirdPartySynced());
        assertEquals(EntrySide.DEBIT, internal.posting().legs().get(0).side());
        assertEquals(source, internal.posting().legs().get(0).accountId());
        assertEquals(destination, internal.posting().legs().get(1).accountId());

        assertTrue(outbound.thirdPartySynced());
        assertEquals(source, outbound.posting().legs().get(0).accountId());

        assertTrue(inbound.thirdPartySynced());
        assertEquals(destination, inbound.posting().legs().get(1).accountId());
        assertEquals(inbound.paymentId(), controller.status(inbound.paymentId()).paymentId());
    }
}
