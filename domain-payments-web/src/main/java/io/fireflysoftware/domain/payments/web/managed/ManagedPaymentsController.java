/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.domain.payments.web.managed;

import io.fireflysoftware.domain.payments.core.managed.*;
import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;

import org.fireflyframework.cqrs.command.CommandBus;
import org.fireflyframework.cqrs.context.ExecutionContext;
import org.fireflyframework.cqrs.query.QueryBus;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.util.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/internal-payments")
@io.swagger.v3.oas.annotations.tags.Tag(name = "ManagedPaymentJourneys")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "managedPaymentBearer")
@io.swagger.v3.oas.annotations.security.SecurityScheme(
        name = "managedPaymentBearer",
        type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid or unsupported payment input"),
    @ApiResponse(responseCode = "401", description = "Trusted current bearer required"),
    @ApiResponse(
            responseCode = "403",
            description = "Current action or exact resource authority denied"),
    @ApiResponse(responseCode = "404", description = "No payment visible to current authority"),
    @ApiResponse(responseCode = "409", description = "Immutable intent or current state conflict"),
    @ApiResponse(
            responseCode = "503",
            description = "Required peer or delegated authority unavailable")
})
public final class ManagedPaymentsController {
    private final CommandBus commands;
    private final QueryBus queries;

    public ManagedPaymentsController(CommandBus commands, QueryBus queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @DisableIdempotency
    @PostMapping
    @Operation(operationId = "createManagedPaymentJourney")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "New instruction accepted; financial state is explicit in receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized replay",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @RequestBody Create input) {
        return Mono.defer(
                () -> {
                    var call = call(jwt);
                    return commands.send(
                                    new CreateManagedPaymentCommand(tenant, input),
                                    context(call, tenant, input == null ? null : input.actionId()))
                            .map(
                                    a ->
                                            ResponseEntity.status(a.replayed() ? 200 : 201)
                                                    .body(a.payment()))
                            .contextWrite(c -> ManagedPaymentContext.with(c, call));
                });
    }

    @DisableIdempotency
    @GetMapping("/{paymentId}")
    @Operation(operationId = "getManagedPaymentJourney")
    @ApiResponse(
            responseCode = "200",
            description = "Current authorized financial receipt",
            content = @Content(schema = @Schema(implementation = View.class)))
    public Mono<View> read(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId) {
        return Mono.defer(
                () -> {
                    var call = call(jwt);
                    return queries.query(
                                    new GetManagedPaymentQuery(tenant, paymentId),
                                    context(call, tenant, paymentId))
                            .onErrorMap(ManagedPaymentsController::queryFailure)
                            .contextWrite(c -> ManagedPaymentContext.with(c, call));
                });
    }

    @DisableIdempotency
    @GetMapping("/{paymentId}/history")
    @Operation(operationId = "getManagedPaymentJourneyHistory")
    @ApiResponse(
            responseCode = "200",
            description = "Bounded current-authorized retained business timeline",
            content = @Content(schema = @Schema(implementation = HistoryPage.class)))
    public Mono<HistoryPage> history(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestParam(defaultValue = "-1") long afterVersion,
            @RequestParam(defaultValue = "25") int limit) {
        return Mono.defer(
                () -> {
                    var call = call(jwt);
                    return queries.query(
                                    new GetManagedPaymentHistoryQuery(
                                            tenant, paymentId, afterVersion, limit),
                                    context(call, tenant, paymentId))
                            .onErrorMap(ManagedPaymentsController::queryFailure)
                            .contextWrite(c -> ManagedPaymentContext.with(c, call));
                });
    }

    @DisableIdempotency
    @PostMapping("/{paymentId}/decisions")
    @Operation(operationId = "decideManagedPaymentJourney")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Decision retained with pending or uncertain financial effect",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> decide(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Decision input) {
        return Mono.defer(
                () -> {
                    var call = call(jwt);
                    return commands.send(
                                    new DecideManagedPaymentCommand(tenant, paymentId, input),
                                    context(call, tenant, input == null ? null : input.actionId()))
                            .map(ManagedPaymentsController::response)
                            .contextWrite(c -> ManagedPaymentContext.with(c, call));
                });
    }

    @DisableIdempotency
    @PostMapping("/{paymentId}/recovery")
    @Operation(operationId = "recoverManagedPaymentJourney")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authorized receipt",
                content = @Content(schema = @Schema(implementation = View.class))),
        @ApiResponse(
                responseCode = "202",
                description = "Recovery retained with pending or uncertain financial effect",
                content = @Content(schema = @Schema(implementation = View.class)))
    })
    public Mono<ResponseEntity<View>> recover(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable String tenant,
            @PathVariable UUID paymentId,
            @RequestBody Recovery input) {
        return Mono.defer(
                () -> {
                    var call = call(jwt);
                    return commands.send(
                                    new RecoverManagedPaymentCommand(tenant, paymentId, input),
                                    context(call, tenant, input == null ? null : input.actionId()))
                            .map(ManagedPaymentsController::response)
                            .contextWrite(c -> ManagedPaymentContext.with(c, call));
                });
    }

    static Throwable queryFailure(Throwable error) {
        Throwable known = error;
        for (int depth = 0;
                depth < 4
                        && known
                                instanceof
                                org.fireflyframework.cqrs.query.DefaultQueryBus
                                        .QueryProcessingException
                        && known.getCause() != null;
                depth++) known = known.getCause();
        if (known instanceof org.springframework.web.server.ResponseStatusException e
                && Set.of(400, 401, 403, 404, 409, 503).contains(e.getStatusCode().value()))
            return ManagedPaymentContext.fail(
                    e.getStatusCode().value(),
                    "MANAGED_PAYMENT_QUERY_" + e.getStatusCode().value());
        return error;
    }

    static ResponseEntity<View> response(View v) {
        return ResponseEntity.status(
                        v.pendingEffectId() != null || v.state() == State.RECOVERY_REQUIRED
                                ? 202
                                : 200)
                .body(v);
    }

    static ExecutionContext context(ManagedPaymentContext.Call call, String tenant, UUID identity) {
        if (identity == null)
            throw ManagedPaymentContext.fail(400, "NATIVE_PAYMENT_REQUEST_ID_REQUIRED");
        return ExecutionContext.builder()
                .withUserId(call.principal().subject())
                .withTenantId(tenant)
                .withRequestId(identity.toString())
                .build();
    }

    static ManagedPaymentContext.Call call(Jwt jwt) {
        if (jwt == null) throw ManagedPaymentContext.fail(401, "TRUSTED_PAYMENT_CONTEXT_REQUIRED");
        try {
            String scopes = jwt.getClaimAsString("scope");
            var p =
                    SecurityPrincipal.builder()
                            .issuer(jwt.getIssuer().toString())
                            .subject(jwt.getSubject())
                            .tenantId(jwt.getClaimAsString("tenant_id"))
                            .scopes(
                                    scopes == null
                                            ? Set.of()
                                            : new HashSet<>(Arrays.asList(scopes.split(" "))))
                            .claims(jwt.getClaims())
                            .build();
            return new ManagedPaymentContext.Call(
                    p, jwt.getTokenValue(), Set.copyOf(jwt.getAudience()));
        } catch (RuntimeException e) {
            throw ManagedPaymentContext.fail(401, "VALID_PAYMENT_PRINCIPAL_REQUIRED");
        }
    }
}
