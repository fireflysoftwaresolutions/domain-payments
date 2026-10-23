# Configure payment adapters

The application selects payment infrastructure through `PaymentsWiring`. The selectors are independent: `firefly.payments.mode` chooses transaction adapters and SCA, while `firefly.payments.configuration` chooses routing configuration and reference data. Both default to `sandbox`. Set both explicitly in a deployment so a change to one cannot be mistaken for a complete environment switch.

## Sandbox behavior

```yaml
firefly:
  payments:
    mode: sandbox
    configuration: sandbox
```

This selects in-process ledger, account-position, payment-record, rail and PSP adapters, the synthetic routing catalog, a no-socket `SandboxCoreScaClient`, and `SandboxChallengeDeliveryAdapter`. No SCA URL is needed in this mode. The challenge is generated per request and returned in the response so a developer can complete a local demonstration. The literal response `sandbox` is rejected.

These adapters keep process-local state. Restarting the application loses sessions, holds, challenges and synthetic records. Their confirmation and `thirdPartySynced` results describe simulation inside this process. They do not establish a bank balance, durable reservation, rail settlement, notification delivery or external SCA result. Use synthetic identifiers and private loopback access.

The existing sample controller flow uses these UUIDs:

| Resource | Synthetic identifier |
| --- | --- |
| Party | `10000000-0000-4000-8000-0000000000aa` |
| Contract | `10000000-0000-4000-8000-000000000021` |
| Source account | `10000000-0000-4000-8000-000000000011` |
| Payment method | `10000000-0000-4000-8000-000000000031` |

They are fixture values, not records provisioned in a remote service. Submit `DOMESTIC_OUTBOUND`, an explicit idempotency key, an amount such as `25.00`, and currency `EUR` to `POST /api/v1/payments/tries`. Confirm with its returned payment ID and challenge value using `POST /api/v1/payments/{paymentId}/confirmations`. The cancellation and status routes operate on the same process-local payment identity. Preserve the original key for an exact retry; do not infer durable or cross-replica idempotency from this demonstration.

## SDK and provider selection

| Boundary | Sandbox selection | Live selection |
| --- | --- | --- |
| Ledger | `SandboxLedgerAdapter` | `SdkLedgerAdapter`, core ledger SDK |
| Account position | `SandboxAccountPositionAdapter` | `SdkAccountPositionAdapter`, **core banking accounts** SDK |
| Payment record | `SandboxPaymentRecordAdapter` | `SdkPaymentRecordAdapter`, core payment SDK |
| Payment rail | `SandboxPaymentRailAdapter` | `LibraryPaymentRailAdapter`, deployment-provided rail library port |
| PSP | `SandboxPspAdapter` | `LibraryPspAdapter`, deployment-provided payment, payout and refund ports |
| SCA storage/validation | `SandboxCoreScaClient` | `SdkCoreScaClient`, core SCA SDK |
| Challenge delivery | Synthetic in-process inbox | Explicit deployment-provided `ChallengeDeliveryPort` |
| Routing and masters | In-process synthetic catalog | Configuration and masters SDKs, selected separately |

The account adapter calls `AccountBalancesApi` and `AccountSpacesApi` from `core-banking-accounts-sdk`. Its base URL must address that **core**, even if an account domain runs nearby. The supplied configuration key is `firefly.payments.accounts-base-url`, populated from `ACCOUNTS_CORE_URL`, with a development default of `http://localhost:8080`. The former `accounts-domain-base-url`/`ACCOUNTS_DOMAIN_URL` setting did not supply the property consumed by this bean; update deployment configuration accordingly.

The other supplied URL variables are `LEDGER_URL`, `PAYMENTS_CORE_URL`, `CONFIG_URL`, `MASTERS_URL` and `SCA_URL`. Defaults are development endpoints; configure actual deployment addresses explicitly. URL configuration alone does not establish authentication, tenant/resource authority, availability, financial correctness or provider support.

## Replace challenge delivery

Live mode requires an explicit `ChallengeDeliveryPort` bean. No built-in live SMS, email or push implementation is installed here. The application rejects the built-in sandbox delivery adapter, including its standard Spring AOP proxies, when selected for live SCA. A custom implementation is still responsible for its own behavior; this check cannot certify arbitrary provider code.

Implement the existing port in a deployment integration module and register it as a bean. Read credentials from the deployment secret store. Deliver the core-generated challenge through the authorized customer channel and return the original challenge identity. Treat the returned `challengeValue` as a public-response field: a real delivery adapter must not return the secret OTP to the requesting application. Establish customer/channel ownership and provider authentication in that integration; the payment ID alone is insufficient authority.

Validate explicit delivery failure, timeout and uncertain acknowledgment handling with the provider sandbox. The current orchestration does not establish durable recovery of a partially created challenge or notification. A failed delivery must not be converted to a successful payment or silently replaced by the synthetic inbox. Missing live delivery prevents the application context from starting.

## Run the packaged local check

Build using the repository's configured JDK and Maven verification command, then run:

```sh
python3 domain-payments-web/src/test/scripts/verify-packaged-runtime.py \
  domain-payments-web/target/domain-payments-web-26.10.6.jar \
  --exercise-sandbox
```

The command starts only its own application with both sandbox selectors, binds a random loopback port, checks health and malformed UUID handling, and exercises HTTP TRY, exact replay, challenge confirmation, status and cancellation. It terminates the owned process afterward. Add `--log-file PATH` to retain the application log. It does not contact a database, PSP, payment rail, live SCA service or notification provider. This check is separate from Maven test totals.

## Recovery and remaining dependencies

For sandbox demonstrations, start a fresh disposable process and repeat with fresh synthetic input after a restart. Do not apply this reset procedure to real payment records. For SDK/provider work, retain the original operation identifiers and inspect the authoritative core/provider state before retrying an uncertain effect.

Live payment qualification still requires trusted identity and resource scope throughout the chain, durable TCC coordination and replay, available-funds enforcement, supported rail/PSP semantics, authenticated SCA/delivery, reconciliation and operator repair. The current hub and account-position adapter have process-local coordination/deduplication; account balance record writes do not prove safe available-funds accounting. These gaps remain even when the application starts and every adapter has a URL.
