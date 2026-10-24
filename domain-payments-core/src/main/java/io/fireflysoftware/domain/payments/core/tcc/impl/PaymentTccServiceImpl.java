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

package io.fireflysoftware.domain.payments.core.tcc.impl;

import io.fireflysoftware.domain.payments.core.tcc.*;

import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.MasterData;
import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.MovementResult;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentPhase;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;
import io.fireflysoftware.domain.payments.StoredPayment;
import io.fireflysoftware.domain.payments.sca.ChallengeStatus;
import io.fireflysoftware.domain.payments.sca.SandboxChallengeDeliveryAdapter;
import io.fireflysoftware.domain.payments.sca.SandboxCoreScaClient;
import io.fireflysoftware.domain.payments.sca.ScaChallengeService;
import io.fireflysoftware.domain.payments.sca.impl.ScaChallengeServiceImpl;
import io.fireflysoftware.domain.payments.SyncMessages.InboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentConfigurationPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentMasterPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRailPort;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;
import io.fireflysoftware.domain.payments.core.ports.PspPort;
import io.fireflysoftware.domain.payments.core.routing.SchemeRouter;
import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live payment coordinator. Try, confirm, and cancel are separate {@link TccEngine} transactions.
 * Jafly runs try and then confirm inside one engine call, so the customer challenge sits between
 * three coordinators: {@code PaymentTry}, {@code PaymentConfirm}, and {@code PaymentCancel}.
 */
public final class PaymentTccServiceImpl implements PaymentTccService {

    static final String TRY = "PaymentTry";
    static final String CONFIRM = "PaymentConfirm";
    static final String CANCEL = "PaymentCancel";
    private final LedgerPort ledger;
    private final PaymentRecordPort records;
    private final PaymentRailPort rail;
    private final PspPort psp;
    private final PaymentConfigurationPort configuration;
    private final PaymentMasterPort masters;
    private final SchemeRouter router;
    private final ScaChallengeService sca;
    private final TccEngine engine;
    private final TccDefinition tryTcc;
    private final TccDefinition confirmTcc;
    private final TccDefinition cancelTcc;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byKey = new ConcurrentHashMap<>();

    public PaymentTccServiceImpl(LedgerPort ledger,
                         PaymentRecordPort records,
                         PaymentRailPort rail,
                         PspPort psp,
                         PaymentConfigurationPort configuration,
                         PaymentMasterPort masters,
                         SchemeRouter router,
                         ScaChallengeService sca) {
        this.ledger = ledger;
        this.records = records;
        this.rail = rail;
        this.psp = psp;
        this.configuration = configuration;
        this.masters = masters;
        this.router = router;
        this.sca = sca;
        OrchestrationEvents events = new OrchestrationEvents() { };
        NoOpEventPublisher publisher = new NoOpEventPublisher();
        TccExecutionOrchestrator orchestrator = new TccExecutionOrchestrator(
                new StepInvoker(new ArgumentResolver()), events, publisher);
        this.engine = new TccEngine(null, events, orchestrator, null, null, publisher);
        this.tryTcc = tryDefinition();
        this.confirmTcc = confirmDefinition();
        this.cancelTcc = cancelDefinition();
    }

    public static PaymentTccService create(LedgerPort ledger,
                                       PaymentRecordPort records,
                                       PaymentRailPort rail,
                                       PspPort psp,
                                       PaymentConfigurationPort configuration,
                                       PaymentMasterPort masters,
                                       ScaChallengeService sca) {
        return new PaymentTccServiceImpl(ledger, records, rail, psp, configuration, masters, new SchemeRouter(), sca);
    }

    public static PaymentTccService sandbox(LedgerPort ledger,
                                        PaymentRecordPort records,
                                        PaymentRailPort rail,
                                        PspPort psp,
                                        PaymentConfigurationPort configuration,
                                        PaymentMasterPort masters) {
        return create(ledger, records, rail, psp, configuration, masters,
                new ScaChallengeServiceImpl(new SandboxCoreScaClient(), new SandboxChallengeDeliveryAdapter()));
    }

    public MovementResult tryPayment(MovementCommand command) {
        UUID existingId = existing(command.idempotencyKey());
        if (existingId == null) {
            existingId = existing(command.thirdPartyReference());
        }
        if (existingId != null) {
            return view(sessions.get(existingId));
        }
        TccResult result = engine.execute(tryTcc, TccInputs.of("payment", command)).block();
        if (result == null || !result.isConfirmed()) {
            throw new PaymentFlowException(PaymentFailure.SYNC_REJECTED, "Try did not reserve the payment.");
        }
        UUID paymentId = result.tryResultOf("payment", UUID.class)
                .orElseThrow(() -> new PaymentFlowException(PaymentFailure.NOT_FOUND, "Try did not record a payment."));
        return view(sessions.get(paymentId));
    }

    public MovementResult confirm(UUID paymentId, String response) {
        Session session = required(paymentId);
        if (session.phase == PaymentPhase.CONFIRMED) {
            return view(session);
        }
        if (session.phase == PaymentPhase.CANCELLED) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "A cancelled payment cannot be confirmed.");
        }
        TccResult result = engine.execute(confirmTcc, TccInputs.of("payment", new ConfirmCommand(paymentId, response))).block();
        if (result == null || !result.isConfirmed()) {
            cancel(paymentId);
            Session cancelled = sessions.get(paymentId);
            return view(cancelled);
        }
        return view(sessions.get(paymentId));
    }

    public MovementResult cancel(UUID paymentId) {
        Session session = required(paymentId);
        if (session.phase == PaymentPhase.CANCELLED || session.phase == PaymentPhase.CONFIRMED) {
            return view(session);
        }
        TccResult result = engine.execute(cancelTcc, TccInputs.of("payment", paymentId)).block();
        if (result == null || !result.isConfirmed()) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Cancel did not release the payment.");
        }
        return view(sessions.get(paymentId));
    }

    public MovementResult status(UUID paymentId) {
        return view(required(paymentId));
    }

    private TccDefinition tryDefinition() {
        return TccBuilder.tccNoRetry(TRY)
                .participant("payment")
                .tryHandler((input, ctx) -> Mono.fromCallable(() -> reserve((MovementCommand) input)))
                .confirmHandler((tryResult, ctx) -> Mono.just(Boolean.TRUE))
                .cancelHandler((tryResult, ctx) -> Mono.fromRunnable(() -> release((UUID) tryResult)).thenReturn(Boolean.TRUE))
                .add()
                .build();
    }

    private TccDefinition confirmDefinition() {
        return TccBuilder.tccNoRetry(CONFIRM)
                .participant("payment")
                .tryHandler((input, ctx) -> Mono.fromCallable(() -> accept((ConfirmCommand) input)))
                .confirmHandler((tryResult, ctx) -> Mono.fromRunnable(() -> commit((UUID) tryResult)).thenReturn(Boolean.TRUE))
                .cancelHandler((tryResult, ctx) -> Mono.just(Boolean.TRUE))
                .add()
                .build();
    }

    private TccDefinition cancelDefinition() {
        return TccBuilder.tccNoRetry(CANCEL)
                .participant("payment")
                .tryHandler((input, ctx) -> Mono.fromCallable(() -> input))
                .confirmHandler((tryResult, ctx) -> Mono.fromRunnable(() -> release((UUID) tryResult)).thenReturn(Boolean.TRUE))
                .cancelHandler((tryResult, ctx) -> Mono.just(Boolean.TRUE))
                .add()
                .build();
    }

    private UUID reserve(MovementCommand command) {
        PaymentsCatalog catalog = configuration.catalog();
        MasterData master = masters.current();
        SchemeRouting routing = router.routing(command.scheme(), catalog);
        String currency = canonical(command.currency(), master);
        validate(command, catalog, routing, currency);
        String key = routing.route().inbound() ? reference(command) : callerKey(command);
        UUID existing = byKey.get(key);
        if (existing != null) {
            return existing;
        }
        if (routing.route().inbound()) {
            SyncResult accepted = acceptInbound(command, routing, currency);
            if (!accepted.accepted()) {
                throw new PaymentFlowException(PaymentFailure.SYNC_REJECTED,
                        accepted.reason() == null ? "The inbound payment was not accepted." : accepted.reason());
            }
        }
        UUID paymentId = UUID.randomUUID();
        List<LedgerLeg> legs = legs(command, routing, currency);
        ledger.hold(key, legs);
        UUID operationId = null;
        UUID challengeId = null;
        String challengeValue = null;
        if (scaRequired(routing)) {
            ScaChallengeService.Issued issued = sca.create(paymentId, command.partyId());
            operationId = issued.operationId();
            challengeId = issued.challengeId();
            challengeValue = issued.challengeValue();
        }
        Session session = new Session(paymentId, command, routing, currency, key, PaymentPhase.TRYING,
                operationId, challengeId, challengeValue, null, false);
        sessions.put(paymentId, session);
        byKey.put(key, paymentId);
        records.save(stored(session, PaymentStatus.INITIATED));
        return paymentId;
    }

    private UUID accept(ConfirmCommand command) {
        Session session = required(command.paymentId());
        if (session.phase == PaymentPhase.CONFIRMED) {
            return session.paymentId;
        }
        if (session.challengeId != null) {
            ChallengeStatus status = sca.validate(session.operationId, session.challengeId, command.response());
            if (status != ChallengeStatus.VALIDATED) {
                throw new PaymentFlowException(PaymentFailure.VALIDATION, "The challenge response was not accepted.");
            }
        }
        return session.paymentId;
    }

    private void commit(UUID paymentId) {
        Session session = required(paymentId);
        if (session.phase == PaymentPhase.CONFIRMED) {
            return;
        }
        LedgerPosting posting = ledger.confirmHold(session.idempotencyKey);
        boolean synced = false;
        String reference = null;
        if (session.routing.route().outbound()) {
            SyncResult sync = sync(session);
            if (!sync.accepted()) {
                ledger.reverse(posting, session.idempotencyKey + ":reversal");
                throw new PaymentFlowException(PaymentFailure.SYNC_REJECTED, sync.reason());
            }
            synced = true;
            reference = sync.reference();
        } else if (session.routing.route().inbound()) {
            synced = true;
            reference = session.command.thirdPartyReference();
        }
        Session confirmed = session.confirmed(posting, synced, reference);
        sessions.put(paymentId, confirmed);
        records.save(stored(confirmed, PaymentStatus.COMPLETED));
    }

    private void release(UUID paymentId) {
        if (paymentId == null) {
            return;
        }
        Session session = sessions.get(paymentId);
        if (session == null || session.phase == PaymentPhase.CANCELLED || session.phase == PaymentPhase.CONFIRMED) {
            return;
        }
        ledger.releaseHold(session.idempotencyKey);
        if (session.challengeId != null) {
            sca.expire(session.operationId, session.challengeId);
        }
        Session cancelled = session.cancelled();
        sessions.put(paymentId, cancelled);
        records.save(stored(cancelled, PaymentStatus.CANCELLED));
    }

    private SyncResult sync(Session session) {
        OutboundInstruction instruction = new OutboundInstruction(session.idempotencyKey, session.command.amount(),
                session.currency, session.command.sourceAccountId(), "Customer",
                session.command.externalAccountName(), session.command.externalIban(), session.command.externalBic(),
                session.routing.railKind(), session.routing.pspInstrument(),
                configuration.catalog().clearingAccounts().nostroAccountId());
        if (session.routing.route() == MovementRoute.OUTBOUND_PSP) {
            return psp.sendOutbound(instruction);
        }
        return rail.sendOutbound(instruction);
    }

    private boolean scaRequired(SchemeRouting routing) {
        if (routing.route() == MovementRoute.INTERNAL_BOOK) {
            return false;
        }
        return routing.route().outbound();
    }

    private SyncResult acceptInbound(MovementCommand command, SchemeRouting routing, String currency) {
        InboundInstruction instruction = new InboundInstruction(command.thirdPartyReference(), command.amount(),
                currency, command.destinationAccountId(), routing.railKind(), routing.pspInstrument());
        if (routing.route() == MovementRoute.INBOUND_PSP) {
            return psp.acceptInbound(instruction);
        }
        return rail.acceptInbound(instruction);
    }

    private static String reference(MovementCommand command) {
        if (command.thirdPartyReference() == null || command.thirdPartyReference().isBlank()) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "An inbound payment needs a third-party reference.");
        }
        return command.thirdPartyReference();
    }

    private static String callerKey(MovementCommand command) {
        return command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                ? UUID.randomUUID().toString() : command.idempotencyKey();
    }

    private UUID existing(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return byKey.get(key);
    }

    private List<LedgerLeg> legs(MovementCommand command, SchemeRouting routing, String currency) {
        UUID debit;
        UUID credit;
        UUID debitSpace = null;
        UUID creditSpace = null;
        if (routing.route() == MovementRoute.INTERNAL_BOOK) {
            debit = command.sourceAccountId();
            credit = routing.spaceMovement() ? command.sourceAccountId() : command.destinationAccountId();
            debitSpace = command.sourceSpaceId();
            creditSpace = command.destinationSpaceId();
        } else if (routing.route().outbound()) {
            debit = command.sourceAccountId();
            credit = configuration.catalog().clearingAccounts().outboundClearingAccountId();
        } else {
            debit = configuration.catalog().clearingAccounts().inboundClearingAccountId();
            credit = command.destinationAccountId();
        }
        return List.of(
                new LedgerLeg(debit, debitSpace, EntrySide.DEBIT, command.amount(), currency),
                new LedgerLeg(credit, creditSpace, EntrySide.CREDIT, command.amount(), currency));
    }

    private void validate(MovementCommand command, PaymentsCatalog catalog, SchemeRouting routing, String currency) {
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "Amount must be greater than zero.");
        }
        if (command.partyId() == null || command.contractId() == null || command.paymentMethodId() == null) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "Party, contract, and payment method identifiers are required.");
        }
        boolean pair = catalog.currencyPairs().stream()
                .anyMatch(item -> item.base().equalsIgnoreCase(currency) && item.quote().equalsIgnoreCase(currency));
        if (!pair) {
            throw new PaymentFlowException(PaymentFailure.CURRENCY_PAIR_NOT_CONFIGURED,
                    "No same-currency pair is configured for this movement.");
        }
        if (routing.maxAmount() != null && command.amount().compareTo(routing.maxAmount()) > 0) {
            throw new PaymentFlowException(PaymentFailure.LIMIT_EXCEEDED, "Amount exceeds the configured limit.");
        }
        if (routing.spaceMovement()) {
            boolean known = command.spaceType() != null && catalog.spaceTypes().contains(command.spaceType());
            if (!known) {
                throw new PaymentFlowException(PaymentFailure.SPACE_TYPE_NOT_CONFIGURED,
                        "The account-space type is not in the configured catalog.");
            }
        }
    }

    private String canonical(String currency, MasterData master) {
        if (currency == null || currency.length() != 3) {
            throw new PaymentFlowException(PaymentFailure.VALIDATION, "Currency must be a three-letter ISO code.");
        }
        return master.currencies().stream()
                .filter(code -> code.equalsIgnoreCase(currency))
                .findFirst()
                .orElseThrow(() -> new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,
                        "Currency is not in the reference masters."));
    }

    private Session required(UUID paymentId) {
        Session session = sessions.get(paymentId);
        if (session == null) {
            throw new PaymentFlowException(PaymentFailure.NOT_FOUND, "Payment " + paymentId + " was not found.");
        }
        return session;
    }

    private MovementResult view(Session session) {
        LedgerPosting posting = ledger.findByIdempotencyKey(session.idempotencyKey).orElse(null);
        PaymentStatus status = switch (session.phase) {
            case TRYING -> PaymentStatus.INITIATED;
            case CONFIRMED -> PaymentStatus.COMPLETED;
            case CANCELLED -> PaymentStatus.CANCELLED;
        };
        return new MovementResult(session.paymentId, status, session.command.scheme(), session.routing.route(),
                session.idempotencyKey, session.command.thirdPartyReference(), session.synced, session.syncReference,
                null, null, configuration.catalog().clearingAccounts().nostroAccountId(), session.command.amount(),
                session.currency, posting, null, session.phase, session.challengeId, session.challengeValue);
    }

    private StoredPayment stored(Session session, PaymentStatus status) {
        return new StoredPayment(session.paymentId, session.command.scheme(), session.routing.route(), status,
                session.idempotencyKey, session.command.thirdPartyReference(),
                session.phase == PaymentPhase.CONFIRMED ? session.idempotencyKey : null, null, session.synced,
                session.syncReference, null, null, configuration.catalog().clearingAccounts().nostroAccountId(),
                session.command.amount(), session.currency, session.command.sourceAccountId(),
                session.command.destinationAccountId(), session.command.partyId(), session.command.contractId(),
                session.command.paymentMethodId());
    }

    private record ConfirmCommand(UUID paymentId, String response) {
    }

    private record Session(UUID paymentId, MovementCommand command, SchemeRouting routing, String currency,
                           String idempotencyKey, PaymentPhase phase, UUID operationId, UUID challengeId,
                           String challengeValue, String syncReference, boolean synced) {
        Session confirmed(LedgerPosting posting, boolean synced, String reference) {
            return new Session(paymentId, command, routing, currency, idempotencyKey, PaymentPhase.CONFIRMED,
                    operationId, challengeId, challengeValue, reference, synced);
        }

        Session cancelled() {
            return new Session(paymentId, command, routing, currency, idempotencyKey, PaymentPhase.CANCELLED,
                    operationId, challengeId, challengeValue, syncReference, false);
        }
    }
}
