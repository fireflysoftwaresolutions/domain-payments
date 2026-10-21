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

package io.fireflysoftware.domain.payments.core.saga;

import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.Idempotency;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.MasterData;
import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.MovementResult;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.ClearingAccounts;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;
import io.fireflysoftware.domain.payments.PositionKind;
import io.fireflysoftware.domain.payments.RailKind;
import io.fireflysoftware.domain.payments.core.routing.SchemeRouter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxAccountPositionAdapter;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentSagaTest {

    private static final UUID PARTY = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONTRACT = UUID.fromString("10000000-0000-4000-8000-000000000021");
    private static final UUID METHOD = UUID.fromString("10000000-0000-4000-8000-000000000031");
    private static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID DESTINATION = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final UUID FROM_SPACE = UUID.fromString("10000000-0000-4000-8000-000000000041");
    private static final UUID TO_SPACE = UUID.fromString("10000000-0000-4000-8000-000000000042");
    private static final BigDecimal AMOUNT = new BigDecimal("25.00");

    private SandboxLedgerAdapter ledger;
    private SandboxAccountPositionAdapter accounts;
    private SandboxPaymentRecordAdapter records;
    private SandboxPaymentRailAdapter rail;
    private SandboxPspAdapter psp;
    private PaymentSaga saga;

    @BeforeEach
    void setUp() {
        ledger = new SandboxLedgerAdapter();
        accounts = new SandboxAccountPositionAdapter();
        records = new SandboxPaymentRecordAdapter();
        rail = new SandboxPaymentRailAdapter();
        psp = new SandboxPspAdapter();
        saga = sagaWith(new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
    }

    @Test
    void internalTransferPostsBalancedEntryAndDoesNotSync() {
        MovementResult result = saga.execute(command(PaymentScheme.INTERNAL_TRANSFER, "internal-1", SOURCE, DESTINATION, null));

        assertEquals(PaymentStatus.COMPLETED, result.status());
        assertEquals(MovementRoute.INTERNAL_BOOK, result.route());
        assertFalse(result.thirdPartySynced());
        assertNull(result.reversal());
        assertBalanced(result.posting(), SOURCE, DESTINATION, "EUR");
        assertEquals(0, rail.outboundCalls());
        assertEquals(0, psp.outboundCalls());
        assertEquals(1, ledger.postings().size());
        assertTrue(accounts.movements().stream().anyMatch(movement -> movement.kind() == PositionKind.ACCOUNT_TRANSFER));
    }

    @Test
    void spaceMovementPostsInsideOneAccountAndDoesNotSync() {
        MovementResult result = saga.execute(new MovementCommand(PaymentScheme.SPACE_TRANSFER, "space-1", AMOUNT, "EUR",
                PARTY, CONTRACT, SOURCE, SOURCE, FROM_SPACE, TO_SPACE, "SAVINGS", METHOD, null, null, null, null));

        assertEquals(MovementRoute.INTERNAL_BOOK, result.route());
        assertFalse(result.thirdPartySynced());
        assertEquals(SOURCE, result.posting().legs().get(0).accountId());
        assertEquals(SOURCE, result.posting().legs().get(1).accountId());
        assertEquals(FROM_SPACE, result.posting().legs().get(0).accountSpaceId());
        assertEquals(TO_SPACE, result.posting().legs().get(1).accountSpaceId());
        assertEquals(EntrySide.DEBIT, result.posting().legs().get(0).side());
        assertEquals(EntrySide.CREDIT, result.posting().legs().get(1).side());
        assertEquals(0, rail.outboundCalls());
        assertEquals(0, psp.outboundCalls());
        assertTrue(accounts.movements().stream().anyMatch(movement -> movement.kind() == PositionKind.SPACE_TRANSFER));
    }

    @Test
    void outboundDebitsCustomerCreditsClearingThenSyncs() {
        UUID clearing = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
        UUID nostro = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4");
        saga = sagaWith(new SandboxPaymentConfigurationAdapter(catalogWithClearing(clearing, nostro)), new SandboxPaymentMasterAdapter());

        MovementResult result = saga.execute(command(PaymentScheme.DOMESTIC_OUTBOUND, "out-1", SOURCE, null, null));

        assertEquals(PaymentStatus.COMPLETED, result.status());
        assertEquals(MovementRoute.OUTBOUND_RAIL, result.route());
        assertTrue(result.thirdPartySynced());
        assertNotNull(result.syncReference());
        assertEquals(nostro, result.nostroAccountId());
        assertBalanced(result.posting(), SOURCE, clearing, "EUR");
        assertEquals(1, rail.outboundCalls());
        assertEquals(0, psp.outboundCalls());
        assertNull(result.reversal());
    }

    @Test
    void internationalOutboundUsesTheRailAndNotThePsp() {
        MovementResult result = saga.execute(command(PaymentScheme.INTERNATIONAL_OUTBOUND, "swift-1", SOURCE, null, null));

        assertEquals(MovementRoute.OUTBOUND_RAIL, result.route());
        assertEquals(RailKind.SWIFT, SandboxCatalog.standard().routes().stream()
                .filter(route -> route.scheme() == PaymentScheme.INTERNATIONAL_OUTBOUND)
                .findFirst().orElseThrow().railKind());
        assertEquals(1, rail.outboundCalls());
        assertEquals(0, psp.outboundCalls());
        assertTrue(result.thirdPartySynced());
    }

    @Test
    void outboundRejectionReversesTheLedgerAndRetryDoesNotPostAgain() {
        rail.rejectOutbound("reject-1");
        MovementResult failed = saga.execute(command(PaymentScheme.DOMESTIC_OUTBOUND, "reject-1", SOURCE, null, null));

        assertEquals(PaymentStatus.FAILED, failed.status());
        assertEquals(PaymentFailure.SYNC_REJECTED, failed.failure());
        assertNotNull(failed.reversal());
        assertEquals(Idempotency.reversalOf("reject-1"), failed.reversal().idempotencyKey());
        assertEquals(EntrySide.CREDIT, leg(failed.reversal(), SOURCE).side());
        assertEquals(2, ledger.postings().size());

        MovementResult again = saga.execute(command(PaymentScheme.DOMESTIC_OUTBOUND, "reject-1", SOURCE, null, null));
        assertEquals(failed.paymentId(), again.paymentId());
        assertEquals(2, ledger.postings().size());
        assertEquals(1, rail.outboundCalls());
    }

    @Test
    void outboundRetryAfterSuccessDoesNotPostTwice() {
        MovementResult first = saga.execute(command(PaymentScheme.DOMESTIC_OUTBOUND, "out-retry", SOURCE, null, null));
        MovementResult second = saga.execute(command(PaymentScheme.DOMESTIC_OUTBOUND, "out-retry", SOURCE, null, null));

        assertEquals(first.paymentId(), second.paymentId());
        assertEquals(1, ledger.postings().size());
        assertEquals(1, rail.outboundCalls());
    }

    @Test
    void inboundCreditsTheCustomerFromClearingOncePerReference() {
        UUID inboundClearing = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2");
        saga = sagaWith(new SandboxPaymentConfigurationAdapter(catalogWithClearing(SandboxCatalog.OUTBOUND_CLEARING, inboundClearing, SandboxCatalog.SUSPENSE, SandboxCatalog.NOSTRO)),
                new SandboxPaymentMasterAdapter());

        MovementResult result = saga.execute(inbound("BANK-REF-1"));
        MovementResult replay = saga.execute(inbound("BANK-REF-1"));

        assertEquals(PaymentStatus.COMPLETED, result.status());
        assertEquals(MovementRoute.INBOUND_RAIL, result.route());
        assertTrue(result.thirdPartySynced());
        assertBalanced(result.posting(), inboundClearing, DESTINATION, "EUR");
        assertEquals(result.paymentId(), replay.paymentId());
        assertEquals(1, ledger.postings().size());
        assertEquals(1, rail.inboundCalls());
    }

    @Test
    void inboundCustomerCreditFailureWithoutReturnIsUnmatchedAndReversed() {
        rail.supportsReturn(false);
        accounts.failNextCredit();

        MovementResult result = saga.execute(inbound("BANK-REF-2"));

        assertEquals(PaymentStatus.UNMATCHED, result.status());
        assertEquals(SandboxCatalog.SUSPENSE, result.suspenseAccountId());
        assertNotNull(result.reversal());
        assertEquals(0, rail.returnCalls());
        assertEquals(2, ledger.postings().size());

        saga.execute(inbound("BANK-REF-2"));
        assertEquals(2, ledger.postings().size());
        assertEquals(1, rail.inboundCalls());
    }

    @Test
    void inboundCustomerCreditFailureWithReturnCompensates() {
        accounts.failNextCredit();

        MovementResult result = saga.execute(inbound("BANK-REF-3"));

        assertEquals(PaymentStatus.FAILED, result.status());
        assertEquals(PaymentFailure.COMPENSATED, result.failure());
        assertNotNull(result.reversal());
        assertEquals(1, rail.returnCalls());
    }

    @Test
    void statusReadReturnsThePostingAndDoesNotPostAgain() {
        MovementResult created = saga.execute(command(PaymentScheme.INTERNAL_TRANSFER, "status-1", SOURCE, DESTINATION, null));
        MovementResult read = saga.status(created.paymentId());

        assertEquals(created.paymentId(), read.paymentId());
        assertEquals(created.posting().transactionId(), read.posting().transactionId());
        assertEquals(1, ledger.postings().size());
    }

    @Test
    void routeComesFromTheCatalog() {
        PaymentsCatalog rerouted = new PaymentsCatalog(
                List.of(new SchemeRouting(PaymentScheme.INTERNAL_TRANSFER, MovementRoute.OUTBOUND_RAIL, RailKind.SEPA, null, false, new BigDecimal("1000000.00"))),
                SandboxCatalog.standard().currencyPairs(),
                SandboxCatalog.standard().spaceTypes(),
                SandboxCatalog.clearing());
        saga = sagaWith(new SandboxPaymentConfigurationAdapter(rerouted), new SandboxPaymentMasterAdapter());

        MovementResult result = saga.execute(command(PaymentScheme.INTERNAL_TRANSFER, "reroute-1", SOURCE, DESTINATION, null));

        assertEquals(MovementRoute.OUTBOUND_RAIL, result.route());
        assertEquals(1, rail.outboundCalls());
        assertEquals(SandboxCatalog.OUTBOUND_CLEARING, result.posting().legs().get(1).accountId());
    }

    @Test
    void unknownCurrencyAndUnknownSpaceTypeDoNotPost() {
        assertEquals(PaymentFailure.CURRENCY_NOT_IN_MASTER,
                assertThrows(PaymentFlowException.class, () -> saga.execute(command(PaymentScheme.INTERNAL_TRANSFER, "bad-ccy", SOURCE, DESTINATION, "ZZZ"))).failure());
        MovementCommand space = new MovementCommand(PaymentScheme.SPACE_TRANSFER, "bad-space", AMOUNT, "EUR",
                PARTY, CONTRACT, SOURCE, SOURCE, FROM_SPACE, TO_SPACE, "POCKET", METHOD, null, null, null, null);
        assertEquals(PaymentFailure.SPACE_TYPE_NOT_CONFIGURED,
                assertThrows(PaymentFlowException.class, () -> saga.execute(space)).failure());
        assertEquals(0, ledger.postings().size());
    }

    @Test
    void pspPayoutUsesThePspAndNotTheRail() {
        MovementResult result = saga.execute(command(PaymentScheme.PSP_PAYOUT, "payout-1", SOURCE, null, null));

        assertEquals(MovementRoute.OUTBOUND_PSP, result.route());
        assertEquals(1, psp.outboundCalls());
        assertEquals(0, rail.outboundCalls());
        assertBalanced(result.posting(), SOURCE, SandboxCatalog.OUTBOUND_CLEARING, "EUR");
    }

    private PaymentSaga sagaWith(SandboxPaymentConfigurationAdapter configuration, SandboxPaymentMasterAdapter master) {
        return new PaymentSaga(ledger, accounts, records, rail, psp, configuration, master, new SchemeRouter());
    }

    private MovementCommand command(PaymentScheme scheme, String key, UUID source, UUID destination, String currency) {
        return new MovementCommand(scheme, key, AMOUNT, currency == null ? "EUR" : currency, PARTY, CONTRACT,
                source, destination, null, null, null, METHOD, "Beneficiary", "DE89370400440532013000", "COBADEFFXXX", null);
    }

    private MovementCommand inbound(String reference) {
        return new MovementCommand(PaymentScheme.RAIL_INBOUND, null, AMOUNT, "EUR", PARTY, CONTRACT,
                null, DESTINATION, null, null, null, METHOD, null, null, null, reference);
    }

    private PaymentsCatalog catalogWithClearing(UUID outbound, UUID nostro) {
        return catalogWithClearing(outbound, SandboxCatalog.INBOUND_CLEARING, SandboxCatalog.SUSPENSE, nostro);
    }

    private PaymentsCatalog catalogWithClearing(UUID outbound, UUID inbound, UUID suspense, UUID nostro) {
        return new PaymentsCatalog(SandboxCatalog.standard().routes(), SandboxCatalog.standard().currencyPairs(),
                SandboxCatalog.standard().spaceTypes(), new ClearingAccounts(outbound, inbound, suspense, nostro));
    }

    private void assertBalanced(LedgerPosting posting, UUID debitAccount, UUID creditAccount, String currency) {
        assertEquals(2, posting.legs().size());
        assertEquals(currency, posting.currency());
        LedgerLeg debit = posting.legs().get(0);
        LedgerLeg credit = posting.legs().get(1);
        assertEquals(EntrySide.DEBIT, debit.side());
        assertEquals(EntrySide.CREDIT, credit.side());
        assertEquals(debitAccount, debit.accountId());
        assertEquals(creditAccount, credit.accountId());
        assertEquals(0, debit.amount().compareTo(credit.amount()));
        assertEquals(debit.currency(), credit.currency());
    }

    private LedgerLeg leg(LedgerPosting posting, UUID accountId) {
        return posting.legs().stream().filter(candidate -> accountId.equals(candidate.accountId())).findFirst().orElseThrow();
    }
}
