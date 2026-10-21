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
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.ClearingAccounts;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;
import io.fireflysoftware.domain.payments.PositionKind;
import io.fireflysoftware.domain.payments.PositionMovement;
import io.fireflysoftware.domain.payments.StoredPayment;
import io.fireflysoftware.domain.payments.SyncMessages.CompensationRequest;
import io.fireflysoftware.domain.payments.SyncMessages.InboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.domain.payments.core.ports.AccountPositionPort;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentConfigurationPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentMasterPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRailPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;
import io.fireflysoftware.domain.payments.core.ports.PspPort;
import io.fireflysoftware.domain.payments.core.routing.SchemeRouter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates a movement. Routing, limits, currency pairs, space types, and clearing accounts
 * come from the configuration and master ports. The ledger is posted here; it is not described and skipped.
 */
public final class PaymentSaga {

    private final LedgerPort ledger;
    private final AccountPositionPort accounts;
    private final PaymentRecordPort records;
    private final PaymentRailPort rail;
    private final PspPort psp;
    private final PaymentConfigurationPort configuration;
    private final PaymentMasterPort masters;
    private final SchemeRouter router;

    public PaymentSaga(LedgerPort ledger,
                       AccountPositionPort accounts,
                       PaymentRecordPort records,
                       PaymentRailPort rail,
                       PspPort psp,
                       PaymentConfigurationPort configuration,
                       PaymentMasterPort masters,
                       SchemeRouter router) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.records = records;
        this.rail = rail;
        this.psp = psp;
        this.configuration = configuration;
        this.masters = masters;
        this.router = router;
    }

    public MovementResult execute(MovementCommand command) {
        if (command == null || command.scheme() == null) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "A payment scheme is required.");
        }
        PaymentsCatalog catalog = configuration.catalog();
        MasterData master = masters.current();
        SchemeRouting routing = router.routing(command.scheme(), catalog);
        String currency = validate(command, catalog, master, routing);
        String key = idempotencyKey(command, routing.route());
        Optional<StoredPayment> existing = locate(command, routing.route(), key);
        if (existing.isPresent()) {
            return toResult(existing.get());
        }
        return switch (routing.route()) {
            case INTERNAL_BOOK -> bookInternally(command, routing, currency, key);
            case OUTBOUND_RAIL -> sendOutbound(command, catalog, routing, currency, key, rail);
            case OUTBOUND_PSP -> sendOutbound(command, catalog, routing, currency, key, psp);
            case INBOUND_RAIL -> receiveInbound(command, catalog, routing, currency, key, rail);
            case INBOUND_PSP -> receiveInbound(command, catalog, routing, currency, key, psp);
        };
    }

    public MovementResult status(UUID paymentId) {
        StoredPayment stored = records.findById(paymentId)
                .orElseThrow(() -> new PaymentFlowException(PaymentFailure.NOT_FOUND,
                        "Payment " + paymentId + " was not found."));
        return toResult(stored);
    }

    private MovementResult bookInternally(MovementCommand command, SchemeRouting routing, String currency, String key) {
        UUID debitAccount = command.sourceAccountId();
        UUID creditAccount = routing.spaceMovement()
                ? command.sourceAccountId()
                : command.destinationAccountId();
        StoredPayment started = start(command, routing, currency, key);
        if (routing.spaceMovement()) {
            accounts.apply(position(key, PositionKind.SPACE_TRANSFER, debitAccount, debitAccount,
                    command.sourceSpaceId(), command.destinationSpaceId(), command.spaceType(), command.amount(), currency));
        } else {
            accounts.apply(position(key, PositionKind.ACCOUNT_TRANSFER, debitAccount, creditAccount,
                    null, null, null, command.amount(), currency));
        }
        LedgerPosting posting = postPair(key, debitAccount, creditAccount,
                command.sourceSpaceId(), command.destinationSpaceId(), command.amount(), currency);
        try {
            return persist(started.withStatus(PaymentStatus.COMPLETED, key, null, false, null, null, null, null));
        } catch (PaymentFlowException ex) {
            LedgerPosting reversal = ledger.reverse(posting, Idempotency.reversalOf(key));
            accounts.reverse(key, reversal.idempotencyKey());
            return persist(started.withStatus(PaymentStatus.FAILED, key, reversal.idempotencyKey(), false, null,
                    PaymentFailure.RECORD_FAILED, null, null));
        }
    }

    private MovementResult sendOutbound(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing,
                                        String currency, String key, SyncGateway sync) {
        ClearingAccounts clearing = catalog.clearingAccounts();
        UUID customer = command.sourceAccountId();
        UUID outboundClearing = clearing.outboundClearingAccountId();
        StoredPayment started = start(command, routing, currency, key);
        LedgerPosting posting = postPair(key, customer, outboundClearing, null, null, command.amount(), currency);
        try {
            accounts.apply(position(key, PositionKind.CUSTOMER_DEBIT, customer, null, null, null, null,
                    command.amount(), currency));
        } catch (RuntimeException ex) {
            return failOutbound(started, posting, key, PaymentFailure.POSITION_FAILED, false, null);
        }
        SyncResult result;
        try {
            result = sync.sendOutbound(outbound(command, routing, currency, key, clearing.nostroAccountId()));
        } catch (RuntimeException ex) {
            return failOutbound(started, posting, key, PaymentFailure.SYNC_REJECTED, false, null);
        }
        if (!result.accepted()) {
            return failOutbound(started, posting, key, PaymentFailure.SYNC_REJECTED, false, null);
        }
        try {
            return persist(started.withStatus(PaymentStatus.COMPLETED, key, null, true, result.reference(), null,
                    null, clearing.nostroAccountId()));
        } catch (PaymentFlowException ex) {
            if (sync.supportsReturn()) {
                sync.returnOrReject(new CompensationRequest(result.reference(), command.amount(), currency,
                        routing.railKind(), routing.pspInstrument()));
                LedgerPosting reversal = ledger.reverse(posting, Idempotency.reversalOf(key));
                accounts.reverse(key, reversal.idempotencyKey());
                return persist(started.withStatus(PaymentStatus.FAILED, key, reversal.idempotencyKey(), true,
                        result.reference(), PaymentFailure.COMPENSATED, null, clearing.nostroAccountId()));
            }
            return persist(started.withStatus(PaymentStatus.FAILED, key, null, true, result.reference(),
                    PaymentFailure.RECORD_FAILED, null, clearing.nostroAccountId()));
        }
    }

    private MovementResult receiveInbound(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing,
                                          String currency, String key, SyncGateway sync) {
        ClearingAccounts clearing = catalog.clearingAccounts();
        SyncResult accepted;
        try {
            accepted = sync.acceptInbound(new InboundInstruction(command.thirdPartyReference(), command.amount(),
                    currency, command.destinationAccountId(), routing.railKind(), routing.pspInstrument()));
        } catch (RuntimeException ex) {
            StoredPayment started = start(command, routing, currency, key);
            return persist(started.withStatus(PaymentStatus.FAILED, null, null, false, null,
                    PaymentFailure.SYNC_REJECTED, clearing.suspenseAccountId(), clearing.nostroAccountId()));
        }
        if (!accepted.accepted()) {
            StoredPayment started = start(command, routing, currency, key);
            return persist(started.withStatus(PaymentStatus.FAILED, null, null, false, null,
                    PaymentFailure.SYNC_REJECTED, clearing.suspenseAccountId(), clearing.nostroAccountId()));
        }
        StoredPayment started = start(command, routing, currency, key);
        LedgerPosting posting;
        try {
            posting = postPair(key, clearing.inboundClearingAccountId(), command.destinationAccountId(),
                    null, null, command.amount(), currency);
        } catch (RuntimeException ex) {
            compensateInbound(sync, command, routing, currency, accepted.reference());
            PaymentStatus status = sync.supportsReturn() ? PaymentStatus.FAILED : PaymentStatus.UNMATCHED;
            return persist(started.withStatus(status, null, null, true, accepted.reference(),
                    PaymentFailure.POSITION_FAILED, clearing.suspenseAccountId(), clearing.nostroAccountId()));
        }
        try {
            accounts.apply(position(key, PositionKind.CUSTOMER_CREDIT, null, command.destinationAccountId(),
                    null, null, null, command.amount(), currency));
        } catch (RuntimeException ex) {
            LedgerPosting reversal = ledger.reverse(posting, Idempotency.reversalOf(key));
            if (sync.supportsReturn()) {
                compensateInbound(sync, command, routing, currency, accepted.reference());
                return persist(started.withStatus(PaymentStatus.FAILED, key, reversal.idempotencyKey(), true,
                        accepted.reference(), PaymentFailure.COMPENSATED, clearing.suspenseAccountId(),
                        clearing.nostroAccountId()));
            }
            return persist(started.withStatus(PaymentStatus.UNMATCHED, key, reversal.idempotencyKey(), true,
                    accepted.reference(), PaymentFailure.POSITION_FAILED, clearing.suspenseAccountId(),
                    clearing.nostroAccountId()));
        }
        return persist(started.withStatus(PaymentStatus.COMPLETED, key, null, true, accepted.reference(), null,
                null, clearing.nostroAccountId()));
    }

    private MovementResult failOutbound(StoredPayment started, LedgerPosting posting, String key,
                                        PaymentFailure failure, boolean synced, String reference) {
        LedgerPosting reversal = ledger.reverse(posting, Idempotency.reversalOf(key));
        accounts.reverse(key, reversal.idempotencyKey());
        return persist(started.withStatus(PaymentStatus.FAILED, key, reversal.idempotencyKey(), synced, reference,
                failure, null, null));
    }

    private void compensateInbound(SyncGateway sync, MovementCommand command, SchemeRouting routing,
                                   String currency, String reference) {
        if (sync.supportsReturn()) {
            sync.returnOrReject(new CompensationRequest(reference, command.amount(), currency,
                    routing.railKind(), routing.pspInstrument()));
        }
    }

    private LedgerPosting postPair(String key, UUID debitAccount, UUID creditAccount, UUID debitSpace, UUID creditSpace,
                                   BigDecimal amount, String currency) {
        return ledger.post(key, List.of(
                new LedgerLeg(debitAccount, debitSpace, EntrySide.DEBIT, amount, currency),
                new LedgerLeg(creditAccount, creditSpace, EntrySide.CREDIT, amount, currency)));
    }

    private StoredPayment start(MovementCommand command, SchemeRouting routing, String currency, String key) {
        StoredPayment started = new StoredPayment(UUID.randomUUID(), command.scheme(), routing.route(),
                PaymentStatus.INITIATED, key, command.thirdPartyReference(), null, null, false, null, null, null, null,
                command.amount(), currency, command.sourceAccountId(), command.destinationAccountId(),
                command.partyId(), command.contractId(), command.paymentMethodId());
        return records.save(started);
    }

    private MovementResult persist(StoredPayment payment) {
        return toResult(records.save(payment));
    }

    private MovementResult toResult(StoredPayment stored) {
        LedgerPosting posting = stored.ledgerKey() == null ? null
                : ledger.findByIdempotencyKey(stored.ledgerKey()).orElse(null);
        LedgerPosting reversal = stored.reversalKey() == null ? null
                : ledger.findByIdempotencyKey(stored.reversalKey()).orElse(null);
        return new MovementResult(stored.paymentId(), stored.status(), stored.scheme(), stored.route(),
                stored.idempotencyKey(), stored.thirdPartyReference(), stored.thirdPartySynced(), stored.syncReference(),
                stored.failure(), stored.suspenseAccountId(), stored.nostroAccountId(), stored.amount(), stored.currency(),
                posting, reversal);
    }

    private Optional<StoredPayment> locate(MovementCommand command, MovementRoute route, String key) {
        Optional<StoredPayment> byKey = records.findByIdempotencyKey(key);
        if (byKey.isPresent()) {
            return byKey;
        }
        if (route.inbound()) {
            return records.findByThirdPartyReference(command.thirdPartyReference());
        }
        return Optional.empty();
    }

    private String idempotencyKey(MovementCommand command, MovementRoute route) {
        if (route.inbound()) {
            if (command.thirdPartyReference() == null || command.thirdPartyReference().isBlank()) {
                throw new PaymentFlowException(PaymentFailure.VALIDATION,
                        "An inbound movement requires the third-party reference.");
            }
            return command.thirdPartyReference();
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "A movement requires an idempotency key.");
        }
        return command.idempotencyKey();
    }

    private String validate(MovementCommand command, PaymentsCatalog catalog, MasterData master, SchemeRouting routing) {
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "Amount must be greater than zero.");
        }
        if (command.currency() == null || command.currency().length() != 3) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "Currency must be a three-letter ISO code.");
        }
        if (command.partyId() == null || command.contractId() == null || command.paymentMethodId() == null) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION,
                    "Party, contract, and payment method identifiers are required.");
        }
        String currency = canonical(command.currency(), master);
        boolean pairAllowed = catalog.currencyPairs().stream()
                .anyMatch(pair -> pair.base().equalsIgnoreCase(currency) && pair.quote().equalsIgnoreCase(currency));
        if (!pairAllowed) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_PAIR_NOT_CONFIGURED,
                    "No same-currency pair is configured for this movement.");
        }
        if (routing.maxAmount() != null && command.amount().compareTo(routing.maxAmount()) > 0) {
            throw new PaymentFlowException(PaymentFailure.LIMIT_EXCEEDED, "Amount exceeds the configured limit.");
        }
        if (routing.spaceMovement()) {
            if (command.sourceAccountId() == null || command.sourceSpaceId() == null || command.destinationSpaceId() == null) {
                throw new PaymentFlowException(PaymentFailure.VALIDATION, "A space movement needs an account and two spaces.");
            }
            if (command.destinationAccountId() != null && !command.destinationAccountId().equals(command.sourceAccountId())) {
                throw new PaymentFlowException(PaymentFailure.VALIDATION,
                        "A movement between account spaces stays inside one account.");
            }
            boolean knownType = command.spaceType() != null && catalog.spaceTypes().stream()
                    .anyMatch(command.spaceType()::equals);
            if (!knownType) {
                throw new PaymentFlowException(PaymentFailure.SPACE_TYPE_NOT_CONFIGURED,
                        "The account-space type is not in the configured catalog.");
            }
        } else if (routing.route() == MovementRoute.INTERNAL_BOOK) {
            if (command.sourceAccountId() == null || command.destinationAccountId() == null) {
                throw new PaymentFlowException(PaymentFailure.VALIDATION, "An internal transfer needs two accounts.");
            }
        } else if (routing.route().outbound()) {
            if (command.sourceAccountId() == null) {
                throw new PaymentFlowException(PaymentFailure.VALIDATION, "An outbound payment needs the customer account.");
            }
        } else if (command.destinationAccountId() == null) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "An inbound credit needs the customer account.");
        }
        return currency;
    }

    private String canonical(String currency, MasterData master) {
        return master.currencies().stream()
                .filter(code -> code.equalsIgnoreCase(currency))
                .findFirst()
                .orElseThrow(() -> new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,
                        "Currency is not in the reference masters."));
    }

    private OutboundInstruction outbound(MovementCommand command, SchemeRouting routing, String currency,
                                         String key, UUID nostro) {
        return new OutboundInstruction(key, command.amount(), currency, command.sourceAccountId(),
                "Customer", command.externalAccountName(), command.externalIban(), command.externalBic(),
                routing.railKind(), routing.pspInstrument(), nostro);
    }

    private PositionMovement position(String key, PositionKind kind, UUID from, UUID to, UUID fromSpace, UUID toSpace,
                                      String spaceType, BigDecimal amount, String currency) {
        return new PositionMovement(key, kind, from, to, fromSpace, toSpace, spaceType, amount, currency);
    }

    private interface SyncGateway {
        SyncResult sendOutbound(OutboundInstruction instruction);

        SyncResult acceptInbound(InboundInstruction instruction);

        boolean supportsReturn();

        SyncResult returnOrReject(CompensationRequest request);
    }

    private MovementResult sendOutbound(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing,
                                        String currency, String key, PaymentRailPort port) {
        return sendOutbound(command, catalog, routing, currency, key, gateway(port));
    }

    private MovementResult sendOutbound(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing,
                                        String currency, String key, PspPort port) {
        return sendOutbound(command, catalog, routing, currency, key, gateway(port));
    }

    private MovementResult receiveInbound(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing,
                                          String currency, String key, PaymentRailPort port) {
        return receiveInbound(command, catalog, routing, currency, key, gateway(port));
    }

    private MovementResult receiveInbound(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing,
                                          String currency, String key, PspPort port) {
        return receiveInbound(command, catalog, routing, currency, key, gateway(port));
    }

    private static SyncGateway gateway(PaymentRailPort port) {
        return new SyncGateway() {
            @Override
            public SyncResult sendOutbound(OutboundInstruction instruction) {
                return port.sendOutbound(instruction);
            }

            @Override
            public SyncResult acceptInbound(InboundInstruction instruction) {
                return port.acceptInbound(instruction);
            }

            @Override
            public boolean supportsReturn() {
                return port.supportsReturn();
            }

            @Override
            public SyncResult returnOrReject(CompensationRequest request) {
                return port.returnOrReject(request);
            }
        };
    }

    private static SyncGateway gateway(PspPort port) {
        return new SyncGateway() {
            @Override
            public SyncResult sendOutbound(OutboundInstruction instruction) {
                return port.sendOutbound(instruction);
            }

            @Override
            public SyncResult acceptInbound(InboundInstruction instruction) {
                return port.acceptInbound(instruction);
            }

            @Override
            public boolean supportsReturn() {
                return port.supportsReturn();
            }

            @Override
            public SyncResult returnOrReject(CompensationRequest request) {
                return port.returnOrReject(request);
            }
        };
    }
}
