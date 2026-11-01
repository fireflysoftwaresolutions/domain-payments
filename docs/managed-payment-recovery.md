# Automatic recovery of managed internal payments

The opt-in domain worker resumes work that payment core has already authorized and persisted. It discovers a bounded page of due work, authenticates a service actor again for each instruction, and sends the same native `RecoverManagedPaymentCommand` used by an explicitly authorized recovery request. Each invocation uses the existing Jafly saga and genuine payment-core and ledger SDKs.

A worker never creates a customer instruction or chooses to confirm, cancel, or reverse one. A retained COMMIT or RELEASE effect can be completed because the customer decision is already durable. A held instruction without a decision remains held until the ledger establishes expiry or the customer submits an authorized decision.

## Authority and retained state

| Concern | Owning component | Worker behavior |
| --- | --- | --- |
| Discovery eligibility | Payment core and its database clock | Process only the current authorized recovery queue page |
| Original customer intent | Immutable core instruction | Preserve amount, currency, accounts, original TTL, pinned deadline, and initiator |
| Retry eligibility and budget | Persisted core effect | Honor `next_attempt_at`, active lease, and exhausted state across scheduler ticks and process restarts |
| Claim identity | Payment core | Reuse the stable effect and participant action; obtain a new fence only through the core protocol |
| Actual money and expiry | Ledger | Execute the retained effect; let exact ledger reconciliation establish the outcome |
| Current service identity | Native OAuth client manager and configured JWT trust | Acquire and validate a signed, finite service token; never reconstruct a customer bearer from saga data |
| Audit and business timeline | Core transaction | Retain the actual service actor separately from the original customer initiator |

The worker holds no monetary database or independent retry journal. Its in-memory overlap guard, tenant rotation, scheduler handle, and counters are scheduling aids. Losing them cannot establish or erase a financial effect.

Every command uses the version returned by discovery. A competing change produces a conflict and defers the row. The worker does not fetch a newer version and immediately retry. A later scheduler tick must discover eligible work again. A repeated recovery action for the same observed version and pending effect has the same deterministic action ID.

## Configure the worker explicitly

Recovery is disabled by default. Configure a dedicated OAuth client registration for each routed tenant, native issuer/audience/JWKS trust, the existing peer SDK endpoints and delegation policy, and exact resource grants. The routing map does not grant tenant or account authority.

```yaml
managed-payments:
  recovery:
    enabled: true
    actor-mode: oauth2-client-credentials
    interval-seconds: 5
    batch-size: 25
    concurrency: 2
    max-run-seconds: 60
    registrations:
      example-tenant: example-tenant-recovery

spring:
  security:
    oauth2:
      client:
        registration:
          example-tenant-recovery:
            provider: organization-identity
            client-id: ${PAYMENT_RECOVERY_CLIENT_ID}
            client-secret: ${PAYMENT_RECOVERY_CLIENT_SECRET}
            authorization-grant-type: client_credentials
        provider:
          organization-identity:
            token-uri: ${PAYMENT_RECOVERY_TOKEN_URI}
```

Use the deployment secret manager or equivalent environment-backed configuration for the client secret. Token and peer URLs use HTTPS. The existing loopback HTTP option is reserved for explicitly configured private sandbox qualification.

The configured identity provider must issue signed service JWTs with a positive lifetime of at most 60 seconds. Both the native OAuth access-token timestamps and the signed JWT timestamps must be finite and current. The JWT must contain the configured domain issuer and audience, an actual nonempty subject, matching `client_id` or `azp` binding, the exact tenant, current scopes, and exact account grants. Every present client-binding claim must match the registration's client ID.

Each tenant uses a distinct registration ID and a native authorized-client principal cache key of `firefly-managed-recovery/<tenant>`. Reusing one registration across multiple tenant entries is rejected. There is no shared customer credential or default service identity.

The native OAuth manager retains its own caching and clock-skew behavior. With the qualified manager's 60-second refresh skew and tokens no longer than 60 seconds, subsequent calls refresh through the configured token endpoint. Local JWT validation alone cannot detect immediate issuer-side revocation of a still-valid token. Treat the signed token's finite validity as the authorization window; the issuer must return changed grants on a later acquisition when authority changes.

### Project-specific actor adapters

A Boot application may provide one plain `@Bean` implementing `ManagedPaymentWorkerActorPort` to integrate an existing trusted service-identity mechanism. The default OAuth actor factory is a deferred Boot auto-configuration, so it backs off after project bean definitions are known, independent of their registration order. No `@Primary` or bean-definition overriding is required. The worker and its native scheduler retain the same opt-in configuration.

A project adapter must return a currently authorized, finite, trusted service call for the requested tenant. It must preserve the signed actor and exact resource grants, and must not reconstruct credentials from stored business or saga data. Current worker and participant checks still apply. Providing an adapter does not enable recovery by itself; enabling recovery still requires a nonempty tenant routing map and bounded scheduling configuration. Without a project adapter or configured native OAuth trust, the default remains closed.

### Required permissions

Discovery and every item require `payments:recover`, `payments:read`, `payments:coordinate`, `funds:read`, and `funds:reconcile`. Each item also requires both retained account grants in the same signed tenant. The actual participant kind requires its current ledger scope:

| Retained work | Additional scope | Result that must be reconciled |
| --- | --- | --- |
| RESERVE | `funds:reserve` | Actual held reservation, or authoritative no-effect closure |
| COMMIT | `funds:commit` | Actual committed posting or retained current reservation state |
| RELEASE | `funds:release` | Actual release or a conflicting retained financial state |
| Due observation of HELD funds | `funds:expire` | Actual ledger-clock expiry; an early conflict triggers exact re-observation |

The worker does not need `payments:create`, `payments:confirm`, `payments:cancel`, or `funds:reverse`. Do not grant them to a recovery-only registration. The validated multi-audience sandbox delegation mode requires the actual returned token to authorize the core and ledger audiences as well. A production token-exchange or delegation adapter must preserve current tenant, exact resources, and requested scopes; it cannot invent missing grants.

Enabling recovery with an empty tenant map or invalid scheduling bounds fails startup. Missing actor trust, unavailable native client services, a failed token acquisition, invalid signature, expired or overlong token, wrong tenant/client/audience, or insufficient grants defers work without a financial fallback. Current denial is never replaced with an anonymous request or a fabricated bearer.

## Scheduling and retry behavior

The worker uses the existing shared Jafly `OrchestrationScheduler` with a named fixed-delay task. It does not create a second timer or disable the orchestration engine. Startup registers only its own task; shutdown cancels that task and its active subscriptions while leaving unrelated scheduled work running.

| Setting | Default | Bounds |
| --- | --- | --- |
| Tenant registrations | None | 1–25 distinct tenant/registration pairs when enabled |
| Fixed delay | 5 seconds | 1–60 seconds |
| Total rows per run | 25 | 1–100 |
| Concurrent item commands | 2 | 1–2 |
| Maximum asynchronous run | 60 seconds | 1–120 seconds |

A subscription-wide guard prevents overlapping runs within one process, including while remote operations are still pending. Different replicas can discover the same row. The core's version checks, lease, fence, retained participant identity, and independent financial observation decide which attempt can commit. Replicas do not coordinate through a local lock or treat an SDK timeout as proof of no effect.

The owner retains exponential delays of 1, 2, 4, 8, 16, and then 30 seconds, with at most ten attempts per effect. Automatic recovery never resets that budget, reopens EXHAUSTED work, invokes manual recovery outside the queue, or loops immediately after a failure. Explicit operator recovery remains a separate currently authorized action under the core's rules; it is not an automatic workaround for the budget.

The counters `scanned`, `advanced`, `deferred`, and `exhausted` describe one scheduling run. `advanced` means the orchestration observed an eligible retained result; it is not a count of completed transfers or settled payments. Warning logs contain bounded counts and fixed failure categories rather than bearer credentials or provider response bodies. Use the authorized core view and timeline to inspect a particular instruction.

## Failure and restart handling

| Interruption | Required recovery |
| --- | --- |
| Process exits after core create | Discover the retained instruction and original effect after restart |
| Ledger reserves funds but its reply is lost | Recover the same reservation intent; never establish an additional hold |
| Ledger commits but its reply is lost | Reconcile the existing posting; never release as an assumed compensation |
| Ledger releases but its reply is lost | Reconcile the actual release without booking a payment |
| Original claim outlives its worker | Wait for the owning core's natural lease expiry, then claim through the retained protocol |
| A stale acknowledgment arrives after replacement | Reject the stale fence/version and preserve the replacement receipt |
| The pinned customer deadline passes before any reserve | Obtain the ledger's typed, immutable `ABSENT_CLOSED` receipt; ordinary `404` and the host clock do not prove finality |
| An old reserve arrives after closure | The ledger's closed operation intent prevents a new financial effect |
| Identity authority changes | Validate the next returned finite token and current exact grants; defer denied work |

The worker never extends the original TTL or customer deadline. Expiry requires a currently authorized ledger command or an authoritative no-effect closure. The due queue does not continuously watch every completed payment for future external changes. A later change requires an authorized observation or reconciliation trigger. When processed, a genuine reversal remains a distinct financial event and history entry; it is not collapsed into cancellation or expiry.

## Verify a deployment

First verify native OAuth registration and issuer trust with a synthetic service account in an isolated sandbox. Confirm that the disabled configuration schedules no recovery and that missing authority fails closed. Then submit an authorized instruction and inspect its actual core history and ledger references through the public SDKs. Establish a hold without giving the worker customer-decision scopes, and verify that only an explicit customer decision or authoritative expiry resolves it.

The maintained private qualifier is `scripts/qualify-managed-payment-worker.py`; its network timing helper is `scripts/ManagedPaymentFaultGateway.py`. It accepts explicit copied executable/SDK inputs and an owned funds fixture, creates private databases and nonprivileged runtime roles, and uses actual services and generated clients. Its output distinguishes successful assertions from a failed gate and retains input hashes. Run it against deployment candidates in a private environment rather than shared databases. The qualifier does not establish external payment-rail settlement or production identity-provider acceptance.
