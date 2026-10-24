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

package io.fireflysoftware.domain.payments.core.tcc;

import io.fireflysoftware.domain.payments.sca.impl.ScaChallengeServiceImpl;
import io.fireflysoftware.domain.payments.core.tcc.impl.PaymentTccServiceImpl;

import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.MovementResult;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentPhase;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.core.routing.SchemeRouter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxCatalog;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxLedgerAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentConfigurationAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentMasterAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRailAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRecordAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPspAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentTccHubTest {

    private static final UUID PARTY = UUID.fromString("10000000-0000-4000-8000-0000000000aa");
    private static final UUID CONTRACT = UUID.fromString("10000000-0000-4000-8000-000000000021");
    private static final UUID METHOD = UUID.fromString("10000000-0000-4000-8000-000000000031");
    private static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID DESTINATION = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final BigDecimal AMOUNT = new BigDecimal("25.00");

    private SandboxLedgerAdapter ledger;
    private SandboxPaymentRailAdapter rail;
    private PaymentTccService hub;

    @BeforeEach
    void setUp() {
        ledger = new SandboxLedgerAdapter();
        rail = new SandboxPaymentRailAdapter();
        hub = PaymentTccServiceImpl.sandbox(ledger, new SandboxPaymentRecordAdapter(),
                rail, new SandboxPspAdapter(), new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
    }

    @Test
    void outboundTryConfirmAndCancelUseTheSandboxChallenge() {
        MovementResult tried = hub.tryPayment(outbound("out-1"));

        assertEquals(PaymentPhase.TRYING, tried.phase());
        assertNotNull(tried.challengeId());
        assertNotNull(tried.challengeValue());
        assertNotEquals("sandbox", tried.challengeValue());
        assertFalse(tried.thirdPartySynced());
        assertEquals(0, ledger.postings().size());
        assertTrue(ledger.holding("out-1"));
        assertEquals(0, rail.outboundCalls());

        MovementResult rejected = hub.confirm(tried.paymentId(), "sandbox");
        assertEquals(PaymentPhase.CANCELLED, rejected.phase());
        assertEquals(0, ledger.postings().size());
        assertFalse(ledger.holding("out-1"));
        assertEquals(0, rail.outboundCalls());

        MovementResult again = hub.tryPayment(outbound("out-2"));
        MovementResult confirmed = hub.confirm(again.paymentId(), again.challengeValue());
        assertEquals(PaymentPhase.CONFIRMED, confirmed.phase());
        assertTrue(confirmed.thirdPartySynced());
        assertEquals(MovementRoute.OUTBOUND_RAIL, confirmed.route());
        assertEquals(1, ledger.postings().size());
        assertEquals(EntrySide.DEBIT, confirmed.posting().legs().get(0).side());
        assertEquals(SOURCE, confirmed.posting().legs().get(0).accountId());
        assertEquals(SandboxCatalog.clearing().outboundClearingAccountId(), confirmed.posting().legs().get(1).accountId());
        assertEquals(1, rail.outboundCalls());

        MovementResult replay = hub.confirm(again.paymentId(), again.challengeValue());
        assertEquals(confirmed.paymentId(), replay.paymentId());
        assertEquals(1, ledger.postings().size());
        assertEquals(1, rail.outboundCalls());
    }

    @Test
    void internalTryHasNoChallengeAndConfirmPostsWithoutARail() {
        MovementResult tried = hub.tryPayment(internal("in-1"));
        assertNull(tried.challengeId());
        assertNull(tried.challengeValue());
        assertEquals(0, ledger.postings().size());

        MovementResult confirmed = hub.confirm(tried.paymentId(), null);
        assertEquals(PaymentPhase.CONFIRMED, confirmed.phase());
        assertFalse(confirmed.thirdPartySynced());
        assertEquals(SOURCE, confirmed.posting().legs().get(0).accountId());
        assertEquals(DESTINATION, confirmed.posting().legs().get(1).accountId());
        assertEquals(0, rail.outboundCalls());
    }

    @Test
    void cancelReleasesTheHoldAndASecondCancelIsHarmless() {
        MovementResult tried = hub.tryPayment(outbound("cancel-1"));
        MovementResult cancelled = hub.cancel(tried.paymentId());
        MovementResult again = hub.cancel(tried.paymentId());

        assertEquals(PaymentPhase.CANCELLED, cancelled.phase());
        assertEquals(cancelled.phase(), again.phase());
        assertFalse(ledger.holding("cancel-1"));
        assertEquals(0, ledger.postings().size());
        assertEquals(0, rail.outboundCalls());
    }

    @Test
    void internationalOutboundConfirmsOnTheRailNotThePsp() {
        SandboxPspAdapter psp = new SandboxPspAdapter();
        hub = PaymentTccServiceImpl.sandbox(ledger, new SandboxPaymentRecordAdapter(),
                rail, psp, new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
        MovementResult tried = hub.tryPayment(command(PaymentScheme.INTERNATIONAL_OUTBOUND, "swift-1", SOURCE, null));
        MovementResult confirmed = hub.confirm(tried.paymentId(), tried.challengeValue());
        assertEquals(MovementRoute.OUTBOUND_RAIL, confirmed.route());
        assertEquals(1, rail.outboundCalls());
        assertEquals(0, psp.outboundCalls());
        assertEquals(new SchemeRouter().routing(PaymentScheme.INTERNATIONAL_OUTBOUND, SandboxCatalog.standard()).railKind().name(), "SWIFT");
    }

    @Test
    void inboundCreditPostsOnceOnTheLedgerAndDoesNotUseThePsp() {
        MovementResult tried = hub.tryPayment(inbound(PaymentScheme.RAIL_INBOUND, "rail-ref-1"));
        assertNull(tried.challengeId());
        assertEquals(0, ledger.postings().size());
        assertTrue(ledger.holding("rail-ref-1"));
        assertEquals(1, rail.inboundCalls());
        assertEquals(0, rail.outboundCalls());

        MovementResult replay = hub.tryPayment(inbound(PaymentScheme.RAIL_INBOUND, "rail-ref-1"));
        assertEquals(tried.paymentId(), replay.paymentId());
        assertEquals(1, rail.inboundCalls());

        MovementResult confirmed = hub.confirm(tried.paymentId(), null);
        assertEquals(PaymentPhase.CONFIRMED, confirmed.phase());
        assertEquals(1, ledger.postings().size());
        assertEquals(SandboxCatalog.clearing().inboundClearingAccountId(), confirmed.posting().legs().get(0).accountId());
        assertEquals(UUID.fromString("44444444-4444-4444-8444-444444444442"), confirmed.posting().legs().get(0).accountId());
        assertEquals(DESTINATION, confirmed.posting().legs().get(1).accountId());
        assertEquals(0, rail.outboundCalls());

        hub.confirm(tried.paymentId(), null);
        hub.tryPayment(inbound(PaymentScheme.RAIL_INBOUND, "rail-ref-1"));
        assertEquals(1, ledger.postings().size());
        assertEquals(1, rail.inboundCalls());
    }

    @Test
    void cardCollectionUsesThePspAndACreditTransferDoesNot() {
        SandboxPspAdapter psp = new SandboxPspAdapter();
        hub = PaymentTccServiceImpl.sandbox(ledger, new SandboxPaymentRecordAdapter(),
                rail, psp, new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
        MovementResult card = hub.tryPayment(inbound(PaymentScheme.PSP_INBOUND, "card-ref-1"));
        hub.confirm(card.paymentId(), null);
        assertEquals(1, psp.inboundCalls());
        assertEquals(0, psp.outboundCalls());
        assertEquals(0, rail.inboundCalls());
        assertEquals(0, rail.outboundCalls());

        MovementResult transfer = hub.tryPayment(outbound("sepa-1"));
        hub.confirm(transfer.paymentId(), transfer.challengeValue());
        assertEquals(1, rail.outboundCalls());
        assertEquals(0, psp.outboundCalls());
    }

    @Test
    void spaceMovementIsLedgerOnly() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        MovementCommand command = new MovementCommand(PaymentScheme.SPACE_TRANSFER, "space-1", AMOUNT, "EUR",
                PARTY, CONTRACT, SOURCE, SOURCE, from, to, "SAVINGS", METHOD, null, null, null, null);
        MovementResult tried = hub.tryPayment(command);
        assertNull(tried.challengeId());
        MovementResult confirmed = hub.confirm(tried.paymentId(), null);
        assertEquals(PaymentPhase.CONFIRMED, confirmed.phase());
        assertFalse(confirmed.thirdPartySynced());
        assertEquals(SOURCE, confirmed.posting().legs().get(0).accountId());
        assertEquals(from, confirmed.posting().legs().get(0).accountSpaceId());
        assertEquals(SOURCE, confirmed.posting().legs().get(1).accountId());
        assertEquals(to, confirmed.posting().legs().get(1).accountSpaceId());
        assertEquals(0, rail.outboundCalls());
        assertEquals(0, rail.inboundCalls());
    }

    private MovementCommand outbound(String key) {
        return command(PaymentScheme.DOMESTIC_OUTBOUND, key, SOURCE, null);
    }

    private MovementCommand internal(String key) {
        return command(PaymentScheme.INTERNAL_TRANSFER, key, SOURCE, DESTINATION);
    }

    private MovementCommand inbound(PaymentScheme scheme, String reference) {
        return new MovementCommand(scheme, reference, AMOUNT, "EUR", PARTY, CONTRACT, null, DESTINATION,
                null, null, null, METHOD, null, null, null, reference);
    }

    private MovementCommand command(PaymentScheme scheme, String key, UUID source, UUID destination) {
        return new MovementCommand(scheme, key, AMOUNT, "EUR", PARTY, CONTRACT, source, destination,
                null, null, null, METHOD, "Beneficiary", "DE89370400440532013000", "COBADEFFXXX", null);
    }

    @Test void legacyBusinessServicesExposeInterfacesAndPreserveIssuedRecordIdentity() throws Exception {
        var tcc=Class.forName("io.fireflysoftware.domain.payments.core.tcc.PaymentTccService");
        assertTrue(tcc.isInterface());
        assertTrue(tcc.isAssignableFrom(Class.forName("io.fireflysoftware.domain.payments.core.tcc.impl.PaymentTccServiceImpl")));
        var sca=Class.forName("io.fireflysoftware.domain.payments.sca.ScaChallengeService");
        assertTrue(sca.isInterface());
        assertTrue(sca.isAssignableFrom(Class.forName("io.fireflysoftware.domain.payments.sca.impl.ScaChallengeServiceImpl")));
        assertEquals("io.fireflysoftware.domain.payments.sca.ScaChallengeService$Issued",sca.getMethod("create",UUID.class,UUID.class).getReturnType().getName());
    }
}
