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

import io.fireflysoftware.experience.payments.sdk.api.ManagedPaymentExperiencesApi;
import io.fireflysoftware.experience.payments.sdk.invoker.ApiClient;
import io.fireflysoftware.experience.payments.sdk.model.*;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/** Owned synthetic typed consumer; transient credentials stay in environment and HTTP only. */
public class ManagedPaymentExperienceSdkProbe {
    private static int checks;
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
        System.out.println("OWNED_EXPERIENCE_SDK_CHECK=" + description);
    }

    private static ManagedPaymentExperiencesApi client(String token) {
        var client = new ApiClient().setBasePath(System.getenv("OWNED_EXPERIENCE_URL"));
        client.addDefaultHeader("Authorization", "Bearer " + token);
        return new ManagedPaymentExperiencesApi(client);
    }

    private static void denied(Runnable request, int status) {
        try {
            request.run();
            throw new AssertionError("Expected HTTP " + status);
        } catch (WebClientResponseException error) {
            check(
                    error.getStatusCode().value() == status,
                    "Exact current-authority/conflict status" + status);
        }
    }

    public static void main(String[] arguments) {
        var api = client(System.getenv("OWNED_EXPERIENCE_TOKEN"));
        var revoked = client(System.getenv("OWNED_REVOKED_TOKEN"));
        var deadline = System.currentTimeMillis() + 120000;
        var input =
                new Create()
                        .actionId(UUID.randomUUID())
                        .sourceAccountId(UUID.fromString(System.getenv("OWNED_SOURCE_ACCOUNT")))
                        .destinationAccountId(
                                UUID.fromString(System.getenv("OWNED_DESTINATION_ACCOUNT")))
                        .amount(new BigDecimal("1.00"))
                        .currency("USD")
                        .ttlSeconds(300)
                        .customerNotAfterEpochMillis(deadline);
        var first = api.createManagedPaymentExperienceWithHttpInfo("owned", input).block(TIMEOUT);
        check(
                first != null && first.getStatusCode().value() == 201 && first.getBody() != null,
                "Genuine experience SDK creation201 typed body");
        var held = first.getBody();
        check(
                held.getState() == View.StateEnum.RESERVED
                        && held.getPendingEffectId() == null
                        && held.getReservationId() != null
                        && held.getPostingId() != null
                        && held.getAmount().equals("1.00")
                        && held.getNotAfterEpochMillis() == deadline
                        && held.getCustomerNotAfterEpochMillis() == deadline,
                "Typed actual held funds and exact original deadline");
        var read =
                api.getManagedPaymentExperience("owned", held.getPaymentId(), null).block(TIMEOUT);
        check(
                read != null
                        && read.getReservationId().equals(held.getReservationId())
                        && read.getVersion().equals(held.getVersion()),
                "Genuine typed current read preserves financial identities");
        var replay = api.createManagedPaymentExperienceWithHttpInfo("owned", input).block(TIMEOUT);
        check(
                replay != null
                        && replay.getStatusCode().value() == 200
                        && replay.getBody().getPaymentId().equals(held.getPaymentId())
                        && replay.getBody().getVersion().equals(held.getVersion()),
                "Experience SDK exact replay200 without another effect");
        input.ttlSeconds(301);
        denied(() -> api.createManagedPaymentExperience("owned", input).block(TIMEOUT), 409);
        input.ttlSeconds(300);
        denied(() -> revoked.createManagedPaymentExperience("owned", input).block(TIMEOUT), 403);
        denied(
                () ->
                        revoked.getManagedPaymentExperience("owned", held.getPaymentId(), null)
                                .block(TIMEOUT),
                404);
        var cancelled =
                api.decideManagedPaymentExperience(
                                "owned",
                                held.getPaymentId(),
                                new Decision()
                                        .actionId(UUID.randomUUID())
                                        .kind(Decision.KindEnum.CANCEL)
                                        .expectedVersion(held.getVersion()))
                        .block(TIMEOUT);
        check(
                cancelled != null
                        && cancelled.getState() == View.StateEnum.CANCELLED
                        && cancelled.getPendingEffectId() == null,
                "Genuine experience SDK cancellation releases actual hold");
        var commitInput =
                new Create()
                        .actionId(UUID.randomUUID())
                        .sourceAccountId(input.getSourceAccountId())
                        .destinationAccountId(input.getDestinationAccountId())
                        .amount(new BigDecimal("2.25"))
                        .currency("USD")
                        .ttlSeconds(300);
        var next = api.createManagedPaymentExperience("owned", commitInput).block(TIMEOUT);
        check(
                next != null
                        && next.getState() == View.StateEnum.RESERVED
                        && next.getAmount().equals("2.25"),
                "Genuine exact decimal funds through experience SDK");
        var decision =
                new Decision()
                        .actionId(UUID.randomUUID())
                        .kind(Decision.KindEnum.CONFIRM)
                        .expectedVersion(next.getVersion());
        var committed =
                api.decideManagedPaymentExperience("owned", next.getPaymentId(), decision)
                        .block(TIMEOUT);
        check(
                committed != null
                        && committed.getState() == View.StateEnum.COMPLETED
                        && committed.getPostingId() != null
                        && committed.getPendingEffectId() == null,
                "Genuine experience SDK CONFIRM reaches actual committed ledger");
        var repeated =
                api.decideManagedPaymentExperience("owned", next.getPaymentId(), decision)
                        .block(TIMEOUT);
        check(
                repeated != null
                        && repeated.getVersion().equals(committed.getVersion())
                        && repeated.getPostingId().equals(committed.getPostingId()),
                "Experience SDK decision replay retains actual posting once");
        var ledgerClient =
                new io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient()
                        .setBasePath(System.getenv("OWNED_LEDGER_URL"));
        ledgerClient.addDefaultHeader(
                "Authorization", "Bearer " + System.getenv("OWNED_EXPERIENCE_TOKEN"));
        var ledger =
                new io.fireflysoftware.core.banking.ledger.sdk.api.LedgerFundsApi(ledgerClient);
        var reversal =
                ledger.actOnFundsReservation(
                                "owned",
                                committed.getReservationId(),
                                new io.fireflysoftware.core.banking.ledger.sdk.model.Command()
                                        .actionId(UUID.randomUUID())
                                        .action(
                                                io.fireflysoftware.core.banking.ledger.sdk.model
                                                        .Command.ActionEnum.REVERSE),
                                null)
                        .block(TIMEOUT);
        if (reversal == null || reversal.getReversalId() == null)
            throw new AssertionError("Actual privileged synthetic reversal required");
        var observed =
                api.recoverManagedPaymentExperience(
                                "owned",
                                committed.getPaymentId(),
                                new Recovery()
                                        .actionId(UUID.randomUUID())
                                        .expectedVersion(committed.getVersion()))
                        .block(TIMEOUT);
        check(
                observed != null
                        && observed.getState() == View.StateEnum.REVERSED
                        && observed.getPostingId().equals(committed.getPostingId())
                        && observed.getReversalId().equals(reversal.getReversalId()),
                "Genuine experience SDK recovery preserves distinct actual reversal");
        var timeline =
                api.getManagedPaymentExperienceHistory(
                                "owned", committed.getPaymentId(), -1L, 25, null)
                        .block(TIMEOUT);
        check(
                timeline != null
                        && timeline.getItems() != null
                        && timeline.getItems().stream()
                                .anyMatch(
                                        e ->
                                                e.getType().equals("LEDGER_COMMITTED")
                                                        && e.getPostingId()
                                                                .equals(committed.getPostingId()))
                        && timeline.getItems().stream()
                                .anyMatch(
                                        e ->
                                                e.getType().equals("LEDGER_REVERSED")
                                                        && e.getPostingId()
                                                                .equals(committed.getPostingId())
                                                        && e.getReversalId()
                                                                .equals(reversal.getReversalId())),
                "Typed genuine experience business timeline retains original completion and"
                    + " distinct reversal");
        denied(
                () ->
                        revoked.getManagedPaymentExperienceHistory(
                                        "owned", committed.getPaymentId(), -1L, 25, null)
                                .block(TIMEOUT),
                404);
        denied(
                () ->
                        api.getManagedPaymentExperienceHistory(
                                        "owned", committed.getPaymentId(), -1L, 101, null)
                                .block(TIMEOUT),
                400);
        System.out.println("OWNED_EXPERIENCE_SDK_ASSERTIONS=" + checks);
    }
}
