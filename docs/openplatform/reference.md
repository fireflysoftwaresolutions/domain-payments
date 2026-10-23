# Payments Orchestration: source reference

[Repository guide](../../README.md) · [Machine-readable inventory](source-inventory.json)

This reference is derived from checked-in source. It separates declared HTTP mappings, typed contracts, property placeholders, and validation locations. Java reflection, inherited framework routes, generated targets, and runtime infrastructure are outside this static inventory.

## HTTP operations

| Mapping | Method | Controller | Kind |
| --- | --- | --- | --- |
| `GET /api/v1/payment-records/standing-orders/{runId}` | `public Mono<StandingOrderView> standingOrder(@PathVariable UUID runId)` | [PaymentRecordsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentRecordsController.java) | Local declaration |
| `GET /api/v1/payment-records/direct-debits/{mandateId}` | `public Mono<DirectDebitView> directDebit(@PathVariable UUID mandateId)` | [PaymentRecordsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentRecordsController.java) | Local declaration |
| `POST /api/v1/payment-records/standing-orders` | `public Mono<RecordedId> standingOrder(@RequestBody StandingOrderBody body)` | [PaymentRecordsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentRecordsController.java) | Local declaration |
| `POST /api/v1/payment-records/direct-debits` | `public Mono<RecordedId> directDebit(@RequestBody DirectDebitBody body)` | [PaymentRecordsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentRecordsController.java) | Local declaration |
| `GET /api/v1/payments/{paymentId}` | `public Mono<MovementResult> status(@PathVariable UUID paymentId)` | [PaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsController.java) | Local declaration |
| `POST /api/v1/payments/tries` | `public Mono<MovementResult> tryPayment(@RequestBody MovementCommand command)` | [PaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsController.java) | Local declaration |
| `POST /api/v1/payments/{paymentId}/confirmations` | `public Mono<MovementResult> confirm(@PathVariable UUID paymentId, @RequestBody Confirmation confirmation)` | [PaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsController.java) | Local declaration |
| `POST /api/v1/payments/{paymentId}/cancellations` | `public Mono<MovementResult> cancel(@PathVariable UUID paymentId)` | [PaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsController.java) | Local declaration |
| `GET /api/v1/tenants/{tenant}/internal-payments/{paymentId}` | `public Mono<View> read( @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable String tenant, @PathVariable UUID paymentId)` | [ManagedPaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsController.java) | Local declaration |
| `GET /api/v1/tenants/{tenant}/internal-payments/{paymentId}/history` | `public Mono<HistoryPage> history( @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable String tenant, @PathVariable UUID paymentId, @RequestParam(defaultValue = "-1") long afterVersion, @RequestParam(defaultValue = "25") int limit)` | [ManagedPaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsController.java) | Local declaration |
| `POST /api/v1/tenants/{tenant}/internal-payments` | `public Mono<ResponseEntity<View>> create( @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable String tenant, @RequestBody Create input)` | [ManagedPaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsController.java) | Local declaration |
| `POST /api/v1/tenants/{tenant}/internal-payments/{paymentId}/decisions` | `public Mono<ResponseEntity<View>> decide( @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable String tenant, @PathVariable UUID paymentId, @RequestBody Decision input)` | [ManagedPaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsController.java) | Local declaration |
| `POST /api/v1/tenants/{tenant}/internal-payments/{paymentId}/recovery` | `public Mono<ResponseEntity<View>> recover( @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @PathVariable String tenant, @PathVariable UUID paymentId, @RequestBody Recovery input)` | [ManagedPaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsController.java) | Local declaration |

## Data contracts

### CreateManagedPaymentCommand

[CreateManagedPaymentCommand](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/CreateManagedPaymentCommand.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `tenant` | `String` | See declaration / service rules |
| `input` | `Create` | See declaration / service rules |
| `commandId` | `String` | See declaration / service rules |

### DecideManagedPaymentCommand

[DecideManagedPaymentCommand](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/DecideManagedPaymentCommand.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `tenant` | `String` | See declaration / service rules |
| `paymentId` | `java.util.UUID` | See declaration / service rules |
| `input` | `Decision` | See declaration / service rules |
| `commandId` | `String` | See declaration / service rules |

### GetManagedPaymentHistoryQuery

[GetManagedPaymentHistoryQuery](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/GetManagedPaymentHistoryQuery.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `tenant` | `String` | See declaration / service rules |
| `paymentId` | `UUID` | See declaration / service rules |
| `afterVersion` | `long` | See declaration / service rules |
| `limit` | `int` | See declaration / service rules |
| `queryId` | `String` | See declaration / service rules |

### GetManagedPaymentQuery

[GetManagedPaymentQuery](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/GetManagedPaymentQuery.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `tenant` | `String` | See declaration / service rules |
| `paymentId` | `UUID` | See declaration / service rules |
| `queryId` | `String` | See declaration / service rules |

### RecoverManagedPaymentCommand

[RecoverManagedPaymentCommand](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/RecoverManagedPaymentCommand.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `tenant` | `String` | See declaration / service rules |
| `paymentId` | `java.util.UUID` | See declaration / service rules |
| `input` | `Recovery` | See declaration / service rules |
| `commandId` | `String` | See declaration / service rules |

### LedgerLeg

[LedgerLeg](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/LedgerLeg.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `accountId` | `UUID` | See declaration / service rules |
| `accountSpaceId` | `UUID` | See declaration / service rules |
| `side` | `EntrySide` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |

### LedgerPosting

[LedgerPosting](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/LedgerPosting.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `transactionId` | `UUID` | See declaration / service rules |
| `idempotencyKey` | `String` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |
| `legs` | `List<LedgerLeg>` | See declaration / service rules |

### MasterData

[MasterData](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/MasterData.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `currencies` | `List<String>` | See declaration / service rules |

### MovementCommand

[MovementCommand](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/MovementCommand.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `scheme` | `PaymentScheme` | See declaration / service rules |
| `idempotencyKey` | `String` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |
| `partyId` | `UUID` | See declaration / service rules |
| `contractId` | `UUID` | See declaration / service rules |
| `sourceAccountId` | `UUID` | See declaration / service rules |
| `destinationAccountId` | `UUID` | See declaration / service rules |
| `sourceSpaceId` | `UUID` | See declaration / service rules |
| `destinationSpaceId` | `UUID` | See declaration / service rules |
| `spaceType` | `String` | See declaration / service rules |
| `paymentMethodId` | `UUID` | See declaration / service rules |
| `externalAccountName` | `String` | See declaration / service rules |
| `externalIban` | `String` | See declaration / service rules |
| `externalBic` | `String` | See declaration / service rules |
| `thirdPartyReference` | `String` | See declaration / service rules |

### MovementResult

[MovementResult](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/MovementResult.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `paymentId` | `UUID` | See declaration / service rules |
| `status` | `PaymentStatus` | See declaration / service rules |
| `scheme` | `PaymentScheme` | See declaration / service rules |
| `route` | `MovementRoute` | See declaration / service rules |
| `idempotencyKey` | `String` | See declaration / service rules |
| `thirdPartyReference` | `String` | See declaration / service rules |
| `thirdPartySynced` | `boolean` | See declaration / service rules |
| `syncReference` | `String` | See declaration / service rules |
| `failure` | `PaymentFailure` | See declaration / service rules |
| `suspenseAccountId` | `UUID` | See declaration / service rules |
| `nostroAccountId` | `UUID` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |
| `posting` | `LedgerPosting` | See declaration / service rules |
| `reversal` | `LedgerPosting` | See declaration / service rules |
| `phase` | `PaymentPhase` | See declaration / service rules |
| `challengeId` | `UUID` | See declaration / service rules |
| `challengeValue` | `String` | See declaration / service rules |

### PaymentsCatalog

[PaymentsCatalog](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/PaymentsCatalog.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `routes` | `List<SchemeRouting>` | See declaration / service rules |
| `currencyPairs` | `List<CurrencyPair>` | See declaration / service rules |
| `spaceTypes` | `List<String>` | See declaration / service rules |
| `clearingAccounts` | `ClearingAccounts` | See declaration / service rules |

### PositionMovement

[PositionMovement](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/PositionMovement.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `idempotencyKey` | `String` | See declaration / service rules |
| `kind` | `PositionKind` | See declaration / service rules |
| `fromAccountId` | `UUID` | See declaration / service rules |
| `toAccountId` | `UUID` | See declaration / service rules |
| `fromSpaceId` | `UUID` | See declaration / service rules |
| `toSpaceId` | `UUID` | See declaration / service rules |
| `spaceType` | `String` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |

### StoredPayment

[StoredPayment](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/StoredPayment.java) · DTO / record / model

| Field | Java type | Validation annotations |
| --- | --- | --- |
| `paymentId` | `UUID` | See declaration / service rules |
| `scheme` | `PaymentScheme` | See declaration / service rules |
| `route` | `MovementRoute` | See declaration / service rules |
| `status` | `PaymentStatus` | See declaration / service rules |
| `idempotencyKey` | `String` | See declaration / service rules |
| `thirdPartyReference` | `String` | See declaration / service rules |
| `ledgerKey` | `String` | See declaration / service rules |
| `reversalKey` | `String` | See declaration / service rules |
| `thirdPartySynced` | `boolean` | See declaration / service rules |
| `syncReference` | `String` | See declaration / service rules |
| `failure` | `PaymentFailure` | See declaration / service rules |
| `suspenseAccountId` | `UUID` | See declaration / service rules |
| `nostroAccountId` | `UUID` | See declaration / service rules |
| `amount` | `BigDecimal` | See declaration / service rules |
| `currency` | `String` | See declaration / service rules |
| `sourceAccountId` | `UUID` | See declaration / service rules |
| `destinationAccountId` | `UUID` | See declaration / service rules |
| `partyId` | `UUID` | See declaration / service rules |
| `contractId` | `UUID` | See declaration / service rules |
| `paymentMethodId` | `UUID` | See declaration / service rules |

## Ports and service contracts

### ManagedFundsPort

[ManagedFundsPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/ports/ManagedFundsPort.java)

```java
record ReservationObservation( UUID operationId, UUID reservationId, String state, UUID postingId, UUID reversalId, UUID absenceReceiptId);
Mono<ReservationObservation> reserve(String tenant, EffectClaim claim);
Mono<ReservationObservation> act(String tenant, EffectClaim claim);
Mono<ReservationObservation> observe(String tenant, EffectClaim claim);
```

### ManagedPaymentCorePort

[ManagedPaymentCorePort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/ports/ManagedPaymentCorePort.java)

```java
Mono<Acceptance> create(String tenant, Create input);
Mono<View> read(String tenant, UUID payment);
Mono<EffectClaim> claim(String tenant, UUID payment, Claim input);
Mono<View> acknowledge(String tenant, UUID payment, Acknowledge input);
Mono<View> failure(String tenant, UUID payment, Failure input);
Mono<View> decide(String tenant, UUID payment, Decision input);
Mono<View> recover(String tenant, UUID payment, Recovery input);
Mono<RecoveryPage> queue(String tenant, UUID after, int limit);
Flux<HistoryEvent> history(String tenant, UUID payment, long after, int limit);
```

### ManagedPaymentWorkerActorPort

[ManagedPaymentWorkerActorPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/ports/ManagedPaymentWorkerActorPort.java)

```java
Mono<ManagedPaymentContext.Call> current(String tenant);
```

### PaymentDelegationPort

[PaymentDelegationPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/ports/PaymentDelegationPort.java)

```java
Mono<String> token(String audience, String tenant, Set<UUID> accounts, Set<String> scopes);
```

### AccountPositionPort

[AccountPositionPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/AccountPositionPort.java)

```java
void apply(PositionMovement movement);
void reverse(String originalKey, String reversalKey);
```

### LedgerPort

[LedgerPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/LedgerPort.java)

```java
Optional<LedgerPosting> findByIdempotencyKey(String idempotencyKey);
LedgerPosting post(String idempotencyKey, List<LedgerLeg> legs);
LedgerPosting reverse(LedgerPosting original, String reversalKey);
LedgerPosting hold(String idempotencyKey, List<LedgerLeg> legs);
LedgerPosting confirmHold(String idempotencyKey);
void releaseHold(String idempotencyKey);
```

### PaymentConfigurationPort

[PaymentConfigurationPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/PaymentConfigurationPort.java)

```java
PaymentsCatalog catalog();
```

### PaymentMasterPort

[PaymentMasterPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/PaymentMasterPort.java)

```java
MasterData current();
```

### PaymentRailPort

[PaymentRailPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/PaymentRailPort.java)

```java
SyncResult sendOutbound(OutboundInstruction instruction);
SyncResult acceptInbound(InboundInstruction instruction);
boolean supportsReturn();
SyncResult returnOrReject(CompensationRequest request);
```

### PaymentRecordPort

[PaymentRecordPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/PaymentRecordPort.java)

```java
Optional<StoredPayment> findById(UUID paymentId);
Optional<StoredPayment> findByIdempotencyKey(String idempotencyKey);
Optional<StoredPayment> findByThirdPartyReference(String thirdPartyReference);
StoredPayment save(StoredPayment payment);
```

### PspPort

[PspPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/ports/PspPort.java)

```java
SyncResult sendOutbound(OutboundInstruction instruction);
SyncResult acceptInbound(InboundInstruction instruction);
boolean supportsReturn();
SyncResult returnOrReject(CompensationRequest request);
```

### ChallengeDeliveryPort

[ChallengeDeliveryPort](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/sca/ChallengeDeliveryPort.java)

```java
Delivery deliver(UUID challengeId, String challengeValue);
record Delivery(UUID challengeId, String challengeValue);
```

### CoreScaClient

[CoreScaClient](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/sca/CoreScaClient.java)

```java
CreatedChallenge create(UUID paymentId, UUID partyId);
boolean validate(UUID operationId, UUID challengeId, String response);
void expire(UUID operationId, UUID challengeId);
record CreatedChallenge(UUID operationId, UUID challengeId, String challengeCode);
```

## Configuration

Values below come from source placeholders. Plain YAML values and configuration-property classes must also be reviewed at the linked configuration sources. Secret fallback values are deliberately redacted.

| Key | Fallback | Source |
| --- | --- | --- |
| `firefly.payments.payments-core-base-url` | `http://localhost:8080` | [PaymentInstructionClientFactory](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/PaymentInstructionClientFactory.java) |
| `firefly.payments.sca-base-url` | No fallback | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `firefly.payments.ledger-base-url` | No fallback | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `firefly.payments.accounts-base-url` | No fallback | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `firefly.payments.payments-core-base-url` | No fallback | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `firefly.payments.config-base-url` | No fallback | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `firefly.payments.masters-base-url` | No fallback | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `managed-payments.security.issuer` | Empty | [ManagedPaymentWorkerActorAutoConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerActorAutoConfiguration.java) |
| `managed-payments.security.audience` | Empty | [ManagedPaymentWorkerActorAutoConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerActorAutoConfiguration.java) |
| `managed-payments.security.jwk-set-uri` | Empty | [ManagedPaymentWorkerActorAutoConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerActorAutoConfiguration.java) |
| `managed-payments.allow-loopback-http` | `false` | [ManagedPaymentWorkerActorAutoConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerActorAutoConfiguration.java) |
| `managed-payments.security.issuer` | Empty | [ManagedPaymentsSecurityConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsSecurityConfiguration.java) |
| `managed-payments.security.audience` | Empty | [ManagedPaymentsSecurityConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsSecurityConfiguration.java) |
| `managed-payments.security.jwk-set-uri` | Empty | [ManagedPaymentsSecurityConfiguration](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsSecurityConfiguration.java) |
| `managed-payments.delegation.mode` | `unconfigured` | [ManagedPaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsWiring.java) |
| `managed-payments.peers.payment-core.base-url` | Empty | [ManagedPaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsWiring.java) |
| `managed-payments.peers.payment-core.audience` | Empty | [ManagedPaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsWiring.java) |
| `managed-payments.allow-loopback-http` | `false` | [ManagedPaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsWiring.java) |
| `managed-payments.peers.ledger.base-url` | Empty | [ManagedPaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsWiring.java) |
| `managed-payments.peers.ledger.audience` | Empty | [ManagedPaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsWiring.java) |
| `SERVER_PORT` | `8092` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `LEDGER_URL` | `http://localhost:8083` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `ACCOUNTS_CORE_URL` | `http://localhost:8080` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `PAYMENTS_CORE_URL` | `http://localhost:8084` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `CONFIG_URL` | `http://localhost:8085` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MASTERS_URL` | `http://localhost:8086` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `SCA_URL` | `http://localhost:8087` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_ISSUER` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_AUDIENCE` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_JWKS_URI` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_DELEGATION_MODE` | `unconfigured` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_ALLOW_LOOPBACK_HTTP` | `false` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_CORE_URL` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_CORE_AUDIENCE` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_LEDGER_URL` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_LEDGER_AUDIENCE` | Empty | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_RECOVERY_ENABLED` | `false` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_WORKER_ACTOR_MODE` | `unconfigured` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_RECOVERY_INTERVAL_SECONDS` | `5` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_RECOVERY_BATCH_SIZE` | `25` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_RECOVERY_CONCURRENCY` | `2` | [application](../../domain-payments-web/src/main/resources/application.yaml) |
| `MANAGED_PAYMENTS_DOMAIN_RECOVERY_MAX_RUN_SECONDS` | `60` | [application](../../domain-payments-web/src/main/resources/application.yaml) |

## Security and failure evidence

| Declaration or failure site | Source |
| --- | --- |
| `new ResponseStatusException(HttpStatusCode.valueOf(code), reason);` | [ManagedPaymentContext](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentContext.java) |
| `new IllegalArgumentException("BOUNDED_MANAGED_RECOVERY_CONFIGURATION_REQUIRED");` | [ManagedPaymentRecoveryWorker](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentRecoveryWorker.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Payment routing is not configured.");` | [SchemeRouter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/routing/SchemeRouter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [SchemeRouter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/routing/SchemeRouter.java) |
| `new PaymentFlowException(PaymentFailure.POSITION_FAILED, "The customer credit was rejected.");` | [SandboxAccountPositionAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxAccountPositionAdapter.java) |
| `new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "No ledger hold exists for " + idempotencyKey + ".");` | [SandboxLedgerAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "A ledger posting needs a debit and a credit.");` | [SandboxLedgerAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "Both sides of a posting use the same currency.");` | [SandboxLedgerAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "Debits and credits must balance and be positive.");` | [SandboxLedgerAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "The payments core rejected the completion write.");` | [SandboxPaymentRecordAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxPaymentRecordAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [SandboxPspAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxPspAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [SandboxPspAdapter](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/sandbox/SandboxPspAdapter.java) |
| `new PaymentFlowException(PaymentFailure.SYNC_REJECTED, "Try did not reserve the payment.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.NOT_FOUND, "Try did not record a payment."));` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.VALIDATION, "A cancelled payment cannot be confirmed.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Cancel did not release the payment.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.SYNC_REJECTED,` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.VALIDATION, "The challenge response was not accepted.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.SYNC_REJECTED, sync.reason());` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.VALIDATION, "An inbound payment needs a third-party reference.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.VALIDATION, "Amount must be greater than zero.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.VALIDATION, "Party, contract, and payment method identifiers are required.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_PAIR_NOT_CONFIGURED,` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.LIMIT_EXCEEDED, "Amount exceeds the configured limit.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.SPACE_TYPE_NOT_CONFIGURED,` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.VALIDATION, "Currency must be a three-letter ISO code.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new PaymentFlowException(PaymentFailure.NOT_FOUND, "Payment " + paymentId + " was not found.");` | [PaymentTccHub](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHub.java) |
| `new IllegalStateException("core-common-sca-mgmt did not return an operation id.");` | [SdkCoreScaClient](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/sca/SdkCoreScaClient.java) |
| `new IllegalStateException("core-common-sca-mgmt did not return a challenge code.");` | [SdkCoreScaClient](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/sca/SdkCoreScaClient.java) |
| `new IllegalStateException("core-common-sca-mgmt returned an empty response.");` | [SdkCoreScaClient](../../domain-payments-core/src/main/java/io/fireflysoftware/domain/payments/sca/SdkCoreScaClient.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,` | [LibraryPaymentRailAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/LibraryPaymentRailAdapter.java) |
| `new PaymentFlowException(PaymentFailure.SYNC_REJECTED, "The rail returned an empty response.");` | [LibraryPaymentRailAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/LibraryPaymentRailAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [LibraryPspAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/LibraryPspAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [LibraryPspAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/LibraryPspAdapter.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER,` | [LibraryPspAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/LibraryPspAdapter.java) |
| `new PaymentFlowException(PaymentFailure.SYNC_REJECTED, "The PSP returned an empty response.");` | [LibraryPspAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/LibraryPspAdapter.java) |
| `new PaymentFlowException(PaymentFailure.POSITION_FAILED, "Accounts refused the space transfer.");` | [SdkAccountPositionAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkAccountPositionAdapter.java) |
| `new PaymentFlowException(PaymentFailure.POSITION_FAILED,` | [SdkAccountPositionAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkAccountPositionAdapter.java) |
| `new PaymentFlowException(PaymentFailure.POSITION_FAILED, "The accounts call failed: " + ex.getMessage());` | [SdkAccountPositionAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkAccountPositionAdapter.java) |
| `new PaymentFlowException(PaymentFailure.POSITION_FAILED, "Accounts base URL is required.");` | [SdkAccountPositionAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkAccountPositionAdapter.java) |
| `new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING,` | [SdkLedgerAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger lookup failed.");` | [SdkLedgerAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, message);` | [SdkLedgerAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "The ledger returned an empty response.");` | [SdkLedgerAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger call failed.");` | [SdkLedgerAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration catalog is missing.");` | [SdkPaymentConfigurationAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentConfigurationAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [SdkPaymentConfigurationAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentConfigurationAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,` | [SdkPaymentConfigurationAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentConfigurationAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration catalog could not be read.");` | [SdkPaymentConfigurationAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentConfigurationAdapter.java) |
| `new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Configuration base URL is required.");` | [SdkPaymentConfigurationAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentConfigurationAdapter.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters returned no currencies.");` | [SdkPaymentMasterAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentMasterAdapter.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters returned no currencies.");` | [SdkPaymentMasterAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentMasterAdapter.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters rejected the currency read.");` | [SdkPaymentMasterAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentMasterAdapter.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters could not be read.");` | [SdkPaymentMasterAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentMasterAdapter.java) |
| `new PaymentFlowException(PaymentFailure.CURRENCY_NOT_IN_MASTER, "Reference masters base URL is required.");` | [SdkPaymentMasterAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentMasterAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "core-banking-payments rejected the order.");` | [SdkPaymentRecordAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentRecordAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED,` | [SdkPaymentRecordAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentRecordAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "core-banking-payments could not be reached.");` | [SdkPaymentRecordAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentRecordAdapter.java) |
| `new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Payments base URL is required.");` | [SdkPaymentRecordAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/SdkPaymentRecordAdapter.java) |
| `new IllegalArgumentException("DEDICATED_TENANT_WORKER_REGISTRATIONS_REQUIRED");` | [OAuthManagedPaymentWorkerActorAdapter](../../domain-payments-infra/src/main/java/io/fireflysoftware/domain/payments/infra/managed/v1/OAuthManagedPaymentWorkerActorAdapter.java) |
| `new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");` | [ManagedPaymentContracts](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/managed/v1/ManagedPaymentContracts.java) |
| `new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");` | [ManagedPaymentContracts](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/managed/v1/ManagedPaymentContracts.java) |
| `new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");` | [ManagedPaymentContracts](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/managed/v1/ManagedPaymentContracts.java) |
| `new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");` | [ManagedPaymentContracts](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/managed/v1/ManagedPaymentContracts.java) |
| `new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");` | [ManagedPaymentContracts](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/managed/v1/ManagedPaymentContracts.java) |
| `new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");` | [ManagedPaymentContracts](../../domain-payments-interfaces/src/main/java/io/fireflysoftware/domain/payments/managed/v1/ManagedPaymentContracts.java) |
| `@ResponseStatus(HttpStatus.CREATED)` | [PaymentsController](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsController.java) |
| `new IllegalStateException("Live payments require a non-sandbox challenge delivery adapter.");` | [PaymentsWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/PaymentsWiring.java) |
| `new IllegalArgumentException("BOUNDED_RECOVERY_INTERVAL_REQUIRED");` | [ManagedPaymentWorkerWiring](../../domain-payments-web/src/main/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerWiring.java) |

## Build and dependencies

### domain-payments-core

[domain-payments-core/pom.xml](../../domain-payments-core/pom.xml)

Coordinates: `io.fireflysoftware:domain-payments-core:26.10.6`. Parent: `io.fireflysoftware:domain-payments:26.10.6`.

- `io.fireflysoftware:core-common-sca-mgmt-sdk:26.10.6`
- `io.fireflysoftware:domain-payments-interfaces:`
- `org.fireflyframework:fireflyframework-cqrs:`
- `org.fireflyframework:fireflyframework-orchestration:26.10.22`
- `org.fireflyframework:fireflyframework-security-api:`
- `org.springframework.boot:spring-boot-starter-test:`

### domain-payments-infra

[domain-payments-infra/pom.xml](../../domain-payments-infra/pom.xml)

Coordinates: `io.fireflysoftware:domain-payments-infra:26.10.6`. Parent: `io.fireflysoftware:domain-payments:26.10.6`.

- `io.fireflysoftware:core-banking-accounts-sdk:26.10.6`
- `io.fireflysoftware:core-banking-ledger-sdk:26.10.6`
- `io.fireflysoftware:core-banking-payments-sdk:26.10.6`
- `io.fireflysoftware:core-common-config-mgmt-sdk:26.10.6`
- `io.fireflysoftware:core-common-reference-master-data-sdk:26.10.6`
- `io.fireflysoftware:domain-payments-core:`
- `io.fireflysoftware:library-psps:26.10.6`
- `io.fireflysoftware:library-rails:26.10.6`
- `org.fireflyframework:fireflyframework-security-oauth2-client:`
- `org.fireflyframework:fireflyframework-security-resource-server:`
- `org.postgresql:postgresql:`
- `org.springframework.boot:spring-boot-starter-json:`
- `org.springframework.boot:spring-boot-starter-test:`
- `org.testcontainers:testcontainers-junit-jupiter:`
- `org.testcontainers:testcontainers-postgresql:`

### domain-payments-interfaces

[domain-payments-interfaces/pom.xml](../../domain-payments-interfaces/pom.xml)

Coordinates: `io.fireflysoftware:domain-payments-interfaces:26.10.6`. Parent: `io.fireflysoftware:domain-payments:26.10.6`.

- `com.fasterxml.jackson.core:jackson-databind:`
- `io.swagger.core.v3:swagger-annotations:`
- `tools.jackson.core:jackson-databind:`

### domain-payments-sdk

[domain-payments-sdk/pom.xml](../../domain-payments-sdk/pom.xml)

Coordinates: `io.fireflysoftware:domain-payments-sdk:26.10.6`. Parent: `io.fireflysoftware:domain-payments:26.10.6`.

- `com.fasterxml.jackson.core:jackson-databind:`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:`
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:`
- `com.squareup.okhttp3:logging-interceptor:`
- `com.squareup.okhttp3:okhttp:`
- `io.swagger.core.v3:swagger-annotations:`
- `jakarta.annotation:jakarta.annotation-api:`
- `javax.annotation:javax.annotation-api:1.3.2`
- `org.openapitools:jackson-databind-nullable:0.2.8`
- `org.projectlombok:lombok:`
- `org.springframework.boot:spring-boot-starter-test:`
- `org.springframework:spring-webflux:`

### domain-payments-web

[domain-payments-web/pom.xml](../../domain-payments-web/pom.xml)

Coordinates: `io.fireflysoftware:domain-payments-web:26.10.6`. Parent: `io.fireflysoftware:domain-payments:26.10.6`.

- `io.fireflysoftware:domain-payments-core:`
- `io.fireflysoftware:domain-payments-infra:`
- `org.fireflyframework:fireflyframework-security-resource-server:`
- `org.fireflyframework:fireflyframework-web:`
- `org.springframework.boot:spring-boot-starter-oauth2-client:`
- `org.springframework.boot:spring-boot-starter-oauth2-resource-server:`
- `org.springframework.boot:spring-boot-starter-test:`
- `org.springframework.boot:spring-boot-starter-webflux:`

### domain-payments

[pom.xml](../../pom.xml)

Coordinates: `io.fireflysoftware:domain-payments:26.10.6`. Parent: `io.fireflysoftware:firefly-parent:26.10.6`.

- `io.fireflysoftware:domain-payments-core:${project.version}`
- `io.fireflysoftware:domain-payments-infra:${project.version}`
- `io.fireflysoftware:domain-payments-interfaces:${project.version}`
- `io.fireflysoftware:domain-payments-sdk:${project.version}`
- `io.fireflysoftware:domain-payments-web:${project.version}`
- `io.fireflysoftware:firefly-bom:26.10.6`

## Test sources

- [domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentFlowTest.java](../../domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentFlowTest.java)
- [domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentHandlersTest.java](../../domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentHandlersTest.java)
- [domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentRecoveryWorkerTest.java](../../domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/managed/v1/ManagedPaymentRecoveryWorkerTest.java)
- [domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHubTest.java](../../domain-payments-core/src/test/java/io/fireflysoftware/domain/payments/core/tcc/PaymentTccHubTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/LedgerAtomicRuntimeIT.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/LedgerAtomicRuntimeIT.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/LibraryPaymentRailAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/LibraryPaymentRailAdapterTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/LiveAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/LiveAdapterTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/PaymentInstructionRecordsTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/PaymentInstructionRecordsTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapterHttpTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapterHttpTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/SdkLedgerAdapterTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/ManagedPaymentDelegationAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/ManagedPaymentDelegationAdapterTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/OAuthManagedPaymentWorkerActorAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/OAuthManagedPaymentWorkerActorAdapterTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/SdkManagedFundsAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/SdkManagedFundsAdapterTest.java)
- [domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/SdkManagedPaymentCoreAdapterTest.java](../../domain-payments-infra/src/test/java/io/fireflysoftware/domain/payments/infra/managed/v1/SdkManagedPaymentCoreAdapterTest.java)
- [domain-payments-sdk/src/test/java/io/fireflysoftware/domain/payments/sdk/GeneratedLegacyClientsTest.java](../../domain-payments-sdk/src/test/java/io/fireflysoftware/domain/payments/sdk/GeneratedLegacyClientsTest.java)
- [domain-payments-sdk/src/test/java/io/fireflysoftware/domain/payments/sdk/LegacyPaymentClientsCompatibilityTest.java](../../domain-payments-sdk/src/test/java/io/fireflysoftware/domain/payments/sdk/LegacyPaymentClientsCompatibilityTest.java)
- [domain-payments-sdk/src/test/java/io/fireflysoftware/domain/payments/sdk/ManagedPaymentJourneysContractTest.java](../../domain-payments-sdk/src/test/java/io/fireflysoftware/domain/payments/sdk/ManagedPaymentJourneysContractTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/PaymentsControllerTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/PaymentsControllerTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/PaymentsWiringTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/PaymentsWiringTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentTransportErrorsTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentTransportErrorsTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerActorConfigurationTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerActorConfigurationTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerWiringTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentWorkerWiringTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsAuthenticatedRuntimeTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsAuthenticatedRuntimeTest.java)
- [domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsRuntimeTest.java](../../domain-payments-web/src/test/java/io/fireflysoftware/domain/payments/web/managed/v1/ManagedPaymentsRuntimeTest.java)
- [domain-payments-web/src/test/scripts/verify-packaged-runtime.py](../../domain-payments-web/src/test/scripts/verify-packaged-runtime.py)

## CI definitions

- [.github/workflows/release.yml](../../.github/workflows/release.yml)
