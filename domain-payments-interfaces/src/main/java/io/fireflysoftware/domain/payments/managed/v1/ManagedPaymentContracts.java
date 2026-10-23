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

package io.fireflysoftware.domain.payments.managed.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ManagedPaymentContracts {
    private ManagedPaymentContracts() {}

    public record Acceptance(View payment, boolean replayed) {}

    public enum State {
        RECEIVED,
        RESERVING,
        RESERVED,
        COMMITTING,
        CANCELLING,
        COMPLETED,
        REVERSED,
        CANCELLED,
        EXPIRED,
        RECOVERY_REQUIRED
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Create(
            UUID actionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactInteger.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactInteger3.class)
                    int ttlSeconds,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long customerNotAfterEpochMillis) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnsupportedField(String name, Object ignored) {
            throw new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");
        }
    }

    public record View(
            UUID paymentId,
            UUID createActionId,
            String tenant,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String amount,
            String currency,
            int currencyScale,
            String currencyPolicy,
            int ttlSeconds,
            Long customerNotAfterEpochMillis,
            long notAfterEpochMillis,
            String initiator,
            State state,
            long version,
            UUID reservationId,
            UUID postingId,
            UUID reversalId,
            UUID absenceReceiptId,
            Long ledgerVersion,
            Instant observedAt,
            UUID pendingEffectId,
            String recoveryCode,
            String ledgerState,
            Instant reservationExpiresAt) {}

    public enum EffectKind {
        RESERVE,
        COMMIT,
        RELEASE,
        OBSERVE
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Claim(
            UUID actionId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long expectedVersion) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnsupportedField(String name, Object ignored) {
            throw new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");
        }
    }

    public record EffectClaim(
            UUID paymentId,
            UUID effectId,
            EffectKind kind,
            UUID participantActionId,
            long fence,
            Instant leaseUntil,
            long version,
            View payment) {}

    public enum FailureCode {
        TRANSPORT_UNAVAILABLE,
        OBSERVATION_UNAVAILABLE,
        PEER_REJECTED,
        INCONSISTENT_OBSERVATION,
        RETRY_EXHAUSTED
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Failure(
            UUID effectId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long fence,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long expectedVersion,
            FailureCode code) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnsupportedField(String name, Object ignored) {
            throw new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");
        }
    }

    public record RecoveryPage(java.util.List<View> items, UUID nextId) {}

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Acknowledge(
            UUID effectId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long fence,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long expectedVersion) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnsupportedField(String name, Object ignored) {
            throw new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");
        }
    }

    public enum DecisionKind {
        CONFIRM,
        CANCEL
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Decision(
            UUID actionId,
            DecisionKind kind,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long expectedVersion) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnsupportedField(String name, Object ignored) {
            throw new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties =
                    io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    public record Recovery(
            UUID actionId,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ExactLong.class)
                    @tools.jackson.databind.annotation.JsonDeserialize(using = ExactLong3.class)
                    Long expectedVersion) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnsupportedField(String name, Object ignored) {
            throw new IllegalArgumentException("UNSUPPORTED_MANAGED_PAYMENT_FIELD");
        }
    }

    public record HistoryPage(java.util.List<HistoryEvent> items) {
        public HistoryPage {
            items = java.util.List.copyOf(items);
        }
    }

    public record HistoryEvent(
            long version,
            String type,
            String actor,
            Instant recordedAt,
            UUID actionId,
            UUID effectId,
            State state,
            UUID postingId,
            UUID reversalId) {}

    public static final class ExactLong
            extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        public Long deserialize(
                com.fasterxml.jackson.core.JsonParser p,
                com.fasterxml.jackson.databind.DeserializationContext c)
                throws java.io.IOException {
            if (p.currentToken() != com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)
                return (Long) c.handleUnexpectedToken(Long.class, p);
            return p.getLongValue();
        }
    }

    public static final class ExactLong3 extends tools.jackson.databind.ValueDeserializer<Long> {
        public Long deserialize(
                tools.jackson.core.JsonParser p, tools.jackson.databind.DeserializationContext c) {
            if (p.currentToken() != tools.jackson.core.JsonToken.VALUE_NUMBER_INT)
                return (Long) c.handleUnexpectedToken(Long.class, p);
            return p.getLongValue();
        }
    }

    public static final class ExactInteger
            extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        public Integer deserialize(
                com.fasterxml.jackson.core.JsonParser p,
                com.fasterxml.jackson.databind.DeserializationContext c)
                throws java.io.IOException {
            if (p.currentToken() != com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)
                return (Integer) c.handleUnexpectedToken(Integer.class, p);
            return p.getIntValue();
        }
    }

    public static final class ExactInteger3
            extends tools.jackson.databind.ValueDeserializer<Integer> {
        public Integer deserialize(
                tools.jackson.core.JsonParser p, tools.jackson.databind.DeserializationContext c) {
            if (p.currentToken() != tools.jackson.core.JsonToken.VALUE_NUMBER_INT)
                return (Integer) c.handleUnexpectedToken(Integer.class, p);
            return p.getIntValue();
        }
    }
}
