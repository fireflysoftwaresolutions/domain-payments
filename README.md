<a href="https://fireflysoftware.io/openplatform/">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-dark.svg">
  <img src="https://raw.githubusercontent.com/fireflysoftwaresolutions/.github/main/profile/assets/banner-light.svg" alt="Firefly OpenPlatform - Payments - Domain" width="100%">
</picture>
</a>

# Firefly OpenPlatform - Payments - Domain

Developed by the Firefly Software Foundation. [dev@fireflysoftware.io](mailto:dev@fireflysoftware.io)

[Website](https://fireflysoftware.io/) · [OpenPlatform](https://fireflysoftware.io/openplatform/)

Payments is a movement of money this bank holds. The hub orchestrates the customer position, the ledger, and a third party when money enters or leaves. It does not keep a second payment ledger. `core-banking-payments` is the system of record for the instruction. `core-banking-ledger` is the book. `core-banking-accounts` holds the customer position, including account spaces.

## Posting

Every completed movement posts a balanced double entry in the same currency.

| Movement | Ledger | Third party |
| --- | --- | --- |
| Internal transfer, and a movement between account spaces | Debit the source account, credit the destination account | None |
| Outbound domestic transfer, outbound international transfer, or a PSP payout | Debit the customer account, credit the outbound clearing account, then sync | `PaymentRailPort` or `PspPort` |
| Inbound credit from a rail or a PSP | Debit the inbound clearing account, credit the customer account | The sync port accepts the instruction |

Account spaces already exist in `core-banking-accounts` (`AccountSpace`, types `MAIN`, `SAVINGS`, `VACATION`, `EMERGENCY`, `GOALS`, `CUSTOM`, and `POST /api/v1/account-spaces/transfer`). A space movement is an internal booking inside one account. The hub does not create another account master. The allowed space types are read from the configuration catalog.

Acceptance of an outbound sync leaves the clearing entry as the handoff to the third party. Rejection, or a failed sync, reverses that ledger posting and marks the instruction failed. A retry sends the same idempotency key. The ledger stores that key as the transaction external reference, so the original entry is not posted twice. The reversal key is the original key plus `:reversal`. An inbound credit uses the third-party reference as its idempotency key. Replaying that reference does not post again.

If the customer credit fails after an inbound clearing entry, and the sync port can return or reject, the hub does that and reverses the ledger posting. `library-rails` exposes `requestReturn` and `cancelPayment`. `library-psps` exposes `cancelPayout`, `cancelPayment`, and `createRefund`. If a port reports that return is not supported, the hub records the payment as `UNMATCHED`, stores the suspense account from configuration, and reverses the clearing entry. `core-banking-payments` `PaymentStatusEnum` is only `INITIATED`, `COMPLETED`, and `FAILED`, so the live record adapter writes `FAILED` with error code `UNMATCHED` and keeps the domain status in the instruction notes.

If the rail or PSP accepts a payment and the core write of the completed instruction fails, the hub compensates the third party when the port supports return, and reverses the ledger posting. If return is not supported, the clearing entry stays and the instruction is stored as `FAILED` with `RECORD_FAILED`.

## Routing

`PaymentScheme` is the product the customer asked for. `SchemeRouter` reads `PaymentsCatalog` and selects `MovementRoute`. The sandbox catalog is assembled by calling `BusinessParameterPort` and `ReferenceCatalogPort`. It does not copy account identifiers. Clearing and nostro accounts come from `payments.clearing.outbound.account-id`, `payments.clearing.inbound.account-id`, and `payments.nostro.account-id`. International outbound and inbound routes come from `payments.transfer-type.INTERNATIONAL.outbound.route` and `payments.transfer-type.INTERNATIONAL.inbound.route`. The `TRANSFER_TYPE` catalog entry `INTERNATIONAL` uses `LIBRARY_RAILS`. That rail is SWIFT, the international wire `library-rails` exposes. A PSP route is only for an instrument `library-psps` supports, such as a card payout or a card collection. Currencies and account-space types come from the reference catalog. The sample actor identifiers are not account identifiers.

Domestic outbound uses a rail the catalog names. The sandbox catalog uses `SEPA`, which `library-rails` exposes. International outbound uses `SWIFT` and the library operation `TransactionType.WIRE_TRANSFER`. A credit transfer does not go through the PSP. The PSP is used for a payout (`PayoutPort`) or a card collection (`PaymentPort` with `PaymentMethodType.CARD`). `library-rails` also lists `CARD_NETWORK`, `MOBILE_WALLET`, and `CRYPTO_BLOCKCHAIN`; those are not credit-transfer rails in this hub.

`library-rails` has no inbound-credit operation. The sync port accepts the inbound instruction. The sandbox injects one and does not open a socket. A later return uses `requestReturn`.

## Sandbox and live

`firefly.payments.mode` defaults to `sandbox`. `live` selects the HTTP and library adapters. `firefly.payments.configuration` defaults to `sandbox`. `live` reads channel parameter `payments.catalog` from core-common-config-mgmt and currencies from `GET /api/v1/currencies` on the reference-data service.

An installer replaces these sandbox classes:

| Sandbox class | Live class |
| --- | --- |
| `SandboxLedgerAdapter` | `HttpLedgerAdapter` |
| `SandboxAccountPositionAdapter` | `HttpAccountPositionAdapter` |
| `SandboxPaymentRecordAdapter` | `HttpPaymentRecordAdapter` |
| `SandboxPaymentRailAdapter` | `LibraryPaymentRailAdapter` |
| `SandboxPspAdapter` | `LibraryPspAdapter` |
| `SandboxPaymentConfigurationAdapter` | `HttpPaymentConfigurationAdapter` |
| `SandboxPaymentMasterAdapter` | `HttpPaymentMasterAdapter` |

The live rail adapter needs a `io.fireflysoftware.rails.adapter.ports.PaymentRailPort` bean. The live PSP adapter needs `PaymentPort`, `PayoutPort`, and `RefundPort` beans.

## Servicing

The journey can read status with `GET /api/v1/payments/{paymentId}`.

`core-banking-payments` has no cancel or recall operation on a payment order or payment instruction. `PaymentOrderManagerService` and `PaymentInstructionManagerService` create, read, update, and delete. `PaymentStatusEnum` is `INITIATED`, `COMPLETED`, and `FAILED`. `PaymentInstructionDTO.instructionStatus` uses that enum, so `InstructionStatusEnum.CANCELLED` is not stored on an instruction. `PaymentScheduleStatusEnum` includes `CANCELLED`, and a schedule can be updated, but that is not a cancel or recall of a payment. This hub does not invent one. Status read is the servicing operation, and a cancel does not reverse the ledger because the core does not offer that operation.

## API

`POST /api/v1/payments/movements`

`GET /api/v1/payments/{paymentId}`

Party, account, contract, and payment-method identifiers are UUIDs. Currency is a three-letter ISO code.

```bash
mvn test
```
