# Managed internal payment journeys

The managed journey coordinates an explicitly authorized internal transfer between two distinct, enrolled customer accounts. A caller creates an instruction, obtains a held reservation, and explicitly confirms or cancels that instruction. Recovery observes the actual financial result and resumes an already authorized effect; it does not make a new customer decision.

The native route starts at `/api/v1/tenants/{tenant}/internal-payments`. It uses its own trusted JWT configuration, native Jafly command and query handlers, a per-invocation saga, and generated payment-core and ledger SDKs. The legacy payment routes and their sandbox mode do not configure this route.

## Ownership and authoritative records

| Component | Owns | Financial meaning |
| --- | --- | --- |
| Experience | Customer-facing request and current actor context | A request is an instruction, not evidence of a posting |
| Domain | Current orchestration invocation and SDK coordination | Saga success alone does not establish money movement |
| Payment core | Immutable instruction, decisions, stable effects, leases, history, outbox, and retained projection | Its financial fields come from independently fetched, exact ledger reconciliation |
| Accounts | Canonical account identity, enrollment, lifecycle, and conservative account controls | Its committed enrollment linkage is required before usable managed funds |
| Ledger | Booked and held funds, atomic reservations, postings, reversals, and no-effect closure receipts | The ledger transaction establishes the financial result |

The domain has no balance store or financial database. Its Jafly diagnostic persistence contains nonsecret business step results, not an authoritative payment journal. Restart recovery starts from payment-core records and a freshly authenticated invocation, rather than reconstructing credentials from a saga checkpoint.

A first instruction needs two distinct, explicitly granted `CUSTOMER` accounts in the same tenant and supported enrolled currency, with compatible exact scale and currency policy. Payment core observes that metadata through the ledger SDK. A later account restriction can still prevent the participant operation; an earlier metadata observation is not a remote lock.

## Request sequence

| Step | Sender to receiver | Operation and reason |
| --- | --- | --- |
| 1 | Caller to domain | Submit an immutable action ID, accounts, amount, currency, TTL, and optional fixed customer deadline |
| 2 | Domain to native CQRS | Dispatch a typed command with the current subject, tenant, and fresh request ID; keep bearer credentials only in transient Reactor context |
| 3 | Domain saga to payment core | Record or replay the exact instruction through the genuine generated SDK |
| 4 | Domain saga to payment core | Claim the already persisted pending effect; core chooses its kind and retains its participant action ID |
| 5 | Domain saga to ledger | Execute only that effect using its retained identifiers and current delegated authority |
| 6 | Domain saga to payment core | Acknowledge the effect without submitting a claimed financial result |
| 7 | Payment core to ledger | Reconcile the full original reservation intent, including the original TTL and pinned deadline |
| 8 | Payment core to its database | Recheck the claim and commit operation, effect, projection, history, and outbox atomically |
| 9 | Domain saga to payment core to caller | Read and return the current authorized retained receipt |

The saga performs no automatic monetary compensation. If a COMMIT response is lost, releasing the reservation could contradict a committed transfer. The instruction remains discoverable for reconciliation instead. Saga retries are disabled for these steps; participant identity and recovery belong to the durable core protocol.

## Public domain API

| Method and path suffix | Input | Response |
| --- | --- | --- |
| `POST /` | `Create` | `201` first acceptance or `200` identical authorized replay, with the current payment view |
| `GET /{paymentId}` | Payment ID | `200` current authorized retained view; `404` absent or invisible at the owning core |
| `GET /{paymentId}/history?afterVersion=-1&limit=25` | Strict version cursor and bounded page size | `200` typed `HistoryPage.items` with original actors, actions, versions, and financial references |
| `POST /{paymentId}/decisions` | `Decision` | `200` decisive retained view, or `202` when pending or uncertain |
| `POST /{paymentId}/recovery` | `Recovery` | `200` decisive retained view, or `202` when pending or uncertain |

A `201` means a new instruction was accepted. Read `state`, `pendingEffectId`, and the explicit ledger references to understand the outcome. Do not equate an HTTP success with a completed transfer.

Create example with synthetic UUIDs:

```json
{
  "actionId": "107ee63b-a74a-4454-8ed0-57e0a1f5df8d",
  "sourceAccountId": "7c5e3c17-71f2-433d-a207-ce99b9f3d12a",
  "destinationAccountId": "26db6c5a-e090-434a-bbbe-a1f62ba43056",
  "amount": 25.00,
  "currency": "USD",
  "ttlSeconds": 300,
  "customerNotAfterEpochMillis": null
}
```

The UUIDs must identify actual authorized enrolled resources in the deployment. They are not seed data. Supply `customerNotAfterEpochMillis` when the caller needs a fixed absolute cutoff. Use an integer millisecond epoch value, rather than a quoted, fractional, or floating-point value.

The core pins its effective deadline once as the earlier of its receipt time plus the TTL and the explicit customer cutoff. Every later reserve or reconciliation uses that retained deadline and original TTL. Recovery never extends the customer window.

To confirm a held instruction:

```json
{
  "actionId": "1165a681-43d2-4a1a-be76-f1b54e38ce0d",
  "kind": "CONFIRM",
  "expectedVersion": 2
}
```

Use the actual current retained version. `CANCEL` is a separate explicit decision. A competing, changed, or stale action returns `409`; the domain does not substitute the opposite decision's successful view. Reuse an action ID only to replay the exact same immutable request.

History is an authorized business timeline, independent of saga diagnostics. `afterVersion` must be at least `-1`, and `limit` must be between `1` and `100`. Events are returned in strictly increasing version order. Use the last returned event's version as the next `afterVersion`; an empty page means no later events visible in that authorized query, not financial absence. The original `LEDGER_COMMITTED` event and a later `LEDGER_REVERSED` event remain distinct, with the original posting and linked reversal references preserved. History uses the same current tenant and exact account policy as the payment view and disables query caching.

Explicit recovery accepts a fresh recovery action ID and the expected current version:

```json
{
  "actionId": "f651bd8c-414f-4198-be9c-12de46743d5e",
  "expectedVersion": 5
}
```

Recovery may resume a previously authorized RESERVE, COMMIT, or RELEASE, or create an observation of retained work. It cannot authorize a new COMMIT or RELEASE decision.

## Use the generated domain SDK

Use the generated `ManagedPaymentJourneysApi` with a fresh per-call authenticated client. The API/model packages are `io.fireflysoftware.domain.payments.sdk.api` and `.model`; its `ApiClient` lives under `.invoker`. Do not reuse mutable default bearer headers across actors or tenants.

```java
var client = new ApiClient().setBasePath(domainBaseUrl);
client.addDefaultHeader("Authorization", "Bearer " + currentDelegatedBearer);
var payments = new ManagedPaymentJourneysApi(client);

return payments.createManagedPaymentJourneyWithHttpInfo(tenant, retainedCreateCommand)
    .flatMap(response -> payments.getManagedPaymentJourneyHistory(
        tenant, response.getBody().getPaymentId(), -1L, 25, null));
```

Supply the already retained typed `Create` command, current validated authority and actual enrolled account IDs. The final nullable history parameter is a compatibility transport header, not identity or a business action. Read the HTTP acceptance status and receipt state separately; retrieving history does not confirm a transfer. A customer decision must use its own retained action and actual expected version.

The native `View.amount` is an exact decimal string paired with its currency scale/policy. Preserve it rather than converting it to `double`. History is a typed `HistoryPage`, and its `items` retain distinct posting/reversal/no-effect references. The complete maintained actual-process SDK probe is in `scripts/ManagedPaymentDomainSdkProbe.java`.

The genuine legacy `MovementCommand` and `MovementResult` SDK models also use their declared enum fields. Consumers of the earlier hand-authored string schema must explicitly use `SchemeEnum.fromValue`/`getValue` as appropriate. Retaining an incorrect cached string schema would conceal a consumer incompatibility.

## Read financial states deliberately

| State | Interpretation and next action |
| --- | --- |
| `RESERVING` | A durable reservation effect exists; the actual financial result is not yet established in the payment projection |
| `RESERVED` | Exact reconciliation found the held reservation; the customer may explicitly confirm or cancel |
| `COMMITTING` | A CONFIRM decision and its durable effect exist; completion still requires exact ledger evidence |
| `CANCELLING` | A CANCEL decision and its durable release effect exist; cancellation still requires exact ledger evidence |
| `COMPLETED` | Exact reconciliation found the committed ledger posting |
| `REVERSED` | Exact reconciliation found a distinct linked reversal after a completed posting |
| `CANCELLED` | Exact reconciliation found a released reservation after an authorized cancellation |
| `EXPIRED` | Exact reconciliation found expiry, or a retained ledger no-effect closure established the elapsed creation window |
| `RECOVERY_REQUIRED` | The exact outcome remains unresolved; retain identifiers and recover with current authority |

`reservationId`, `postingId`, `reversalId`, and `absenceReceiptId` are different evidence references. A reversal preserves the original posting and points to its distinct reversal. A no-effect closure has no invented reservation or posting.

An ordinary reservation GET or `404` is never proof of no effect. The core observer uses exact full-intent reconciliation. At or after the ledger's deadline, an absent operation can close only through a committed immutable `ABSENT_CLOSED` receipt serialized against an in-flight reserve. A later reserve with that operation ID cannot create money movement.

For held reservations, an OBSERVE effect can invoke the ledger's explicit EXPIRE action using its persisted participant action ID. The ledger clock decides whether expiry is due. An early `409` is followed by exact reconciliation; local domain time never makes a reservation financially expired. A missing current `funds:expire` permission is a denial, not a fallback to local expiry.

## Trusted actor and delegation configuration

The domain validates JWT signature, issuer, audience, and finite token lifetime independently. Signed `tenant_id` must equal the path tenant; `funds_accounts` must be a bounded list of actual UUID grants. Both accounts must be present. Arbitrary username headers, a body initiator, or an unverified bearer are not authority.

| Operation | Current payment permission | Additional coordination and financial permissions |
| --- | --- | --- |
| Create | `payments:create` | `payments:read`, `payments:coordinate`, `funds:read`, `funds:reserve`, `funds:reconcile` |
| Read | `payments:read` | Exact current resource grants |
| Confirm | `payments:confirm` | Read/coordinate/reconcile permissions plus `funds:commit` |
| Cancel | `payments:cancel` | Read/coordinate/reconcile permissions plus `funds:release` |
| Recover | `payments:recover` | Read/coordinate/reconcile and the actual retained effect's financial permission; held OBSERVE additionally requires `funds:expire` |

`payments:recover` can replace an original payment action scope only when executing an already durably authorized effect. It does not replace the corresponding funds scope. The payment core and ledger independently enforce their own current policies.

Every new mutation disables the transport idempotency cache through the supported method-level Jafly annotation. The business receipt still supports exact replay, but replay executes current authority checks. Read queries explicitly disable CQRS caching. GET-only OpenAPI contract routes remain public for contract generation; the exception does not permit unknown application routes. Native orchestration diagnostic routes under `/api/orchestration/**` deny external access; they are not an alternate financial read API. This restriction does not disable the internal saga engine.

Use environment-backed configuration from `application.yaml`:

| Environment variable | Purpose |
| --- | --- |
| `MANAGED_PAYMENTS_DOMAIN_ISSUER` | Trusted JWT issuer |
| `MANAGED_PAYMENTS_DOMAIN_AUDIENCE` | This domain's required audience |
| `MANAGED_PAYMENTS_DOMAIN_JWKS_URI` | Trusted issuer key endpoint |
| `MANAGED_PAYMENTS_DOMAIN_DELEGATION_MODE` | Defaults to `unconfigured`; explicit `validated-multi-audience` permits only a currently validated token for each exact target |
| `MANAGED_PAYMENTS_DOMAIN_CORE_URL` | Payment-core base URL |
| `MANAGED_PAYMENTS_DOMAIN_CORE_AUDIENCE` | Required payment-core audience |
| `MANAGED_PAYMENTS_DOMAIN_LEDGER_URL` | Ledger base URL |
| `MANAGED_PAYMENTS_DOMAIN_LEDGER_AUDIENCE` | Required ledger audience |
| `MANAGED_PAYMENTS_DOMAIN_ALLOW_LOOPBACK_HTTP` | Defaults to `false`; private synthetic loopback verification only |

Peer properties live under `managed-payments.peers.payment-core` and `managed-payments.peers.ledger`. Peer URLs require HTTPS, except explicitly opted-in loopback HTTP. Credentials in URL user information, query strings, or fragments are rejected. SDK clients are created per call with bounded buffers and timeouts, so one caller's Authorization header cannot become another caller's default.

A real token-exchange deployment supplies a trusted `PaymentDelegationPort` implementation. It must preserve or explicitly attest the actor, tenant, exact resources, target audience, current scopes, and finite lifetime. Exchanging to an unconstrained service principal would break the contract. The repository's unconfigured adapter fails closed; it does not simulate token exchange.

## Failure handling and recovery operations

A peer timeout, lost response, or conflicting financial observation preserves uncertainty. The domain records only bounded failure codes, never raw provider text. It then reads the core's current retained state. A stale fenced acknowledgment cannot overwrite a reclaimed worker's result.

Native commands retain their own stable nonsecret command IDs. Transient credentials do not enter command fields, saga inputs, saga variables, or step results. A restarted or resumed invocation needs a new trusted call context. Domain diagnostics are operational information only and cannot serve as a payment recovery journal.

Explicit manual recovery is an authorized intervention. Automatic recovery must use the core's bounded due queue and its retained backoff. It must not repeatedly issue direct claim or manual-recovery requests to bypass that schedule. Automatic worker qualification is a separate implementation gate from this native domain request path.

`400` identifies malformed or unsupported input, `401` missing or invalid current identity, `403` denied current action/resource/delegation, `404` an absent or invisible record, `409` immutable intent/state/fence conflict, and `503` unavailable required peer or trust configuration. A known native query wrapper is normalized to the original sanitized HTTP denial; unrelated unknown exceptions retain the standard internal-error behavior.

## Running the private actual-process qualification

The maintained `scripts/qualify-managed-payment-domain.py` accepts accounts, ledger, payment-core, and domain executable JARs plus the accepted funds fixture script. It copies and hashes the inputs, creates new disposable PostgreSQL containers with a non-superuser/non-BYPASSRLS application role, starts the actual executables on random loopback ports, and exercises genuine SDK calls from the domain and financial observer. Logs and the bounded result are written only under the requested output directory.

The fixture creates synthetic canonical accounts through APIs, performs explicit signed enrollment and committed cross-core linkage, and uses the privileged balanced bank-control funding action. It does not insert customer opening balances or infer balances from legacy CRUD. Use owned local test infrastructure; do not point this script at an existing database.

Framework unit tests and synthetic SDK transport fixtures verify contracts independently from that actual financial-process gate. Genuine generated-domain SDK consumption is exercised separately from experience adoption and automatic recovery workers. Each changed executable/SDK combination needs current evidence; a working domain request path does not establish the complete channel journey or worker recovery.

## Required financial extensions

FX quotes and currency conversion, fees and commissions, approved overdrafts and interest, and rail settlement, returns, and reconciliation are mandatory program deliveries. Their owning contracts, exact calculation policies, multileg financial postings, and provider uncertainty need explicit implementation and qualification. They must not be approximated by silently adding fields to this initial internal-transfer request. The current request rejects unsupported fields while those dependent capabilities are developed in their owning contexts.
