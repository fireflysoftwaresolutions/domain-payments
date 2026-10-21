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

package io.fireflysoftware.domain.payments.infra;

import io.fireflysoftware.core.banking.ledger.sdk.api.TransactionLegsApi;
import io.fireflysoftware.core.banking.ledger.sdk.api.TransactionsApi;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.ledger.sdk.model.TransactionDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.TransactionLegDTO;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Live ledger adapter. Holds, posts, and releases through {@code core-banking-ledger-sdk}.
 * A hold is a transaction in {@code PENDING}. Confirm sets {@code POSTED}. Cancel sets {@code REVERSED}.
 */
public final class SdkLedgerAdapter implements LedgerPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final TransactionsApi transactions;
    private final TransactionLegsApi legsApi;

    public SdkLedgerAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new TransactionsApi(client), new TransactionLegsApi(client));
    }

    public SdkLedgerAdapter(TransactionsApi transactions, TransactionLegsApi legsApi) {
        this.transactions = transactions;
        this.legsApi = legsApi;
    }

    @Override
    public Optional<LedgerPosting> findByIdempotencyKey(String idempotencyKey) {
        TransactionDTO row = lookup(idempotencyKey);
        if (row == null || terminal(row)) {
            return Optional.empty();
        }
        return Optional.of(toPosting(row, List.of()));
    }

    @Override
    public LedgerPosting post(String idempotencyKey, List<LedgerLeg> legs) {
        TransactionDTO existing = lookup(idempotencyKey);
        if (existing != null && existing.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.POSTED) {
            return toPosting(existing, legs);
        }
        return write(idempotencyKey, legs, TransactionDTO.TransactionStatusEnum.POSTED);
    }

    @Override
    public LedgerPosting hold(String idempotencyKey, List<LedgerLeg> legs) {
        TransactionDTO existing = lookup(idempotencyKey);
        if (existing != null && (existing.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.POSTED
                || existing.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.PENDING)) {
            return toPosting(existing, legs);
        }
        return write(idempotencyKey, legs, TransactionDTO.TransactionStatusEnum.PENDING);
    }

    @Override
    public LedgerPosting confirmHold(String idempotencyKey) {
        TransactionDTO row = required(idempotencyKey);
        if (row.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.POSTED) {
            return toPosting(row, List.of());
        }
        TransactionDTO posted = body(transactions.updateTransactionStatus(row.getTransactionId(), "POSTED",
                "payment-confirm", idempotencyKey));
        return toPosting(posted, List.of());
    }

    @Override
    public void releaseHold(String idempotencyKey) {
        TransactionDTO row = lookup(idempotencyKey);
        if (row == null || row.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.REVERSED) {
            return;
        }
        body(transactions.updateTransactionStatus(row.getTransactionId(), "REVERSED", "payment-cancel", idempotencyKey));
    }

    @Override
    public LedgerPosting reverse(LedgerPosting original, String reversalKey) {
        TransactionDTO existing = lookup(reversalKey);
        if (existing != null && existing.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.POSTED) {
            return toPosting(existing, original.legs());
        }
        List<LedgerLeg> flipped = new ArrayList<>();
        for (LedgerLeg leg : original.legs()) {
            flipped.add(new LedgerLeg(leg.accountId(), leg.accountSpaceId(), leg.side().opposite(), leg.amount(), leg.currency()));
        }
        return post(reversalKey, flipped);
    }

    private LedgerPosting write(String idempotencyKey, List<LedgerLeg> legs, TransactionDTO.TransactionStatusEnum status) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        TransactionDTO request = new TransactionDTO()
                .externalReference(idempotencyKey)
                .requestId(idempotencyKey)
                .transactionDate(now)
                .valueDate(now)
                .bookingDate(now)
                .transactionType(TransactionDTO.TransactionTypeEnum.TRANSFER)
                .transactionStatus(status)
                .totalAmount(legs.get(0).amount())
                .currency(legs.get(0).currency())
                .description("Payment movement")
                .accountId(legs.get(0).accountId());
        if (idempotencyKey.endsWith(":reversal")) {
            request.relationType("REVERSAL");
        }
        TransactionDTO created = body(transactions.createTransaction(request, idempotencyKey));
        if (created.getTransactionId() == null) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger response did not include transactionId.");
        }
        for (LedgerLeg leg : legs) {
            TransactionLegDTO body = new TransactionLegDTO()
                    .transactionId(created.getTransactionId())
                    .accountId(leg.accountId())
                    .legType(leg.side().name())
                    .amount(leg.amount())
                    .currency(leg.currency())
                    .valueDate(now);
            if (leg.accountSpaceId() != null) {
                body.accountSpaceId(leg.accountSpaceId());
            }
            body(legsApi.createTransactionLeg(created.getTransactionId(), body, idempotencyKey));
        }
        return new LedgerPosting(created.getTransactionId(), idempotencyKey, legs.get(0).currency(), List.copyOf(legs));
    }

    private TransactionDTO required(String idempotencyKey) {
        TransactionDTO row = lookup(idempotencyKey);
        if (row == null || row.getTransactionId() == null) {
            throw new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING,
                    "The ledger has no hold to confirm for " + idempotencyKey + ".");
        }
        return row;
    }

    private TransactionDTO lookup(String idempotencyKey) {
        try {
            return transactions.findByExternalReference(idempotencyKey, idempotencyKey).block(TIMEOUT);
        } catch (RuntimeException ex) {
            WebClientResponseException http = http(ex);
            if (http != null && http.getStatusCode().value() == 404) {
                return null;
            }
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger lookup failed.");
        }
    }

    private static boolean terminal(TransactionDTO row) {
        TransactionDTO.TransactionStatusEnum status = row.getTransactionStatus();
        return status == TransactionDTO.TransactionStatusEnum.PENDING
                || status == TransactionDTO.TransactionStatusEnum.REVERSED
                || status == TransactionDTO.TransactionStatusEnum.FAILED;
    }

    private static LedgerPosting toPosting(TransactionDTO row, List<LedgerLeg> legs) {
        return new LedgerPosting(row.getTransactionId(), row.getExternalReference(),
                row.getCurrency() == null ? "EUR" : row.getCurrency(), List.copyOf(legs));
    }

    private static <T> T body(Mono<T> call) {
        try {
            T value = call.block(TIMEOUT);
            if (value == null) {
                throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "The ledger returned an empty response.");
            }
            return value;
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "Ledger call failed.");
        }
    }

    private static WebClientResponseException http(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof WebClientResponseException http) {
                return http;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String strip(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
