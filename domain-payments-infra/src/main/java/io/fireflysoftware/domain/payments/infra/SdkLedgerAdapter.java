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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fireflysoftware.core.banking.ledger.sdk.api.TransactionLegsApi;
import io.fireflysoftware.core.banking.ledger.sdk.api.TransactionsApi;
import io.fireflysoftware.core.banking.ledger.sdk.api.LedgerPostingsApi;
import io.fireflysoftware.core.banking.ledger.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.ledger.sdk.model.PaginationResponse;
import io.fireflysoftware.core.banking.ledger.sdk.model.TransactionDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.TransactionLegDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerPostingDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerPostingHeaderDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerPostingRequestDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerPostingLegDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerReversalRequestDTO;
import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Live ledger adapter. Holds, posts, and releases through {@code core-banking-ledger-sdk}.
 * A hold is a durable balanced aggregate in {@code PENDING}, not an available-funds reservation.
 * Dedicated core commands confirm, release pending entries, or append a linked opposite-leg reversal.
 * Incomplete or inconsistent persisted postings fail with {@code UNBALANCED_POSTING} before confirmation
 * or reversal writes; transport failures use {@code RECORD_FAILED}.
 */
public final class SdkLedgerAdapter implements LedgerPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int LEG_PAGE_SIZE = 100;
    private static final ObjectMapper LEG_MAPPER = ApiClient.createDefaultMapper(null);

    private final TransactionsApi transactions;
    private final TransactionLegsApi legsApi;
    private final LedgerPostingsApi postings;

    public SdkLedgerAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new TransactionsApi(client), new TransactionLegsApi(client));
    }

    public SdkLedgerAdapter(TransactionsApi transactions, TransactionLegsApi legsApi) {
        this(transactions, legsApi, new LedgerPostingsApi(transactions.getApiClient()));
    }

    public SdkLedgerAdapter(TransactionsApi transactions, TransactionLegsApi legsApi, LedgerPostingsApi postings) {
        this.transactions = transactions;
        this.legsApi = legsApi;
        this.postings = postings;
        // The generated page contains untyped objects: retain decimal amounts before mapping its legs.
        if (legsApi.getApiClient() != null) {
            legsApi.getApiClient().getObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        }
    }

    @Override
    public Optional<LedgerPosting> findByIdempotencyKey(String idempotencyKey) {
        TransactionDTO row = lookup(idempotencyKey);
        if (row == null || terminal(row)) {
            return Optional.empty();
        }
        return Optional.of(toPosting(row));
    }

    @Override
    public LedgerPosting post(String idempotencyKey, List<LedgerLeg> legs) {
        return write(idempotencyKey, legs, TransactionDTO.TransactionStatusEnum.POSTED);
    }

    @Override
    public LedgerPosting hold(String idempotencyKey, List<LedgerLeg> legs) {
        return write(idempotencyKey, legs, TransactionDTO.TransactionStatusEnum.PENDING);
    }

    @Override
    public LedgerPosting confirmHold(String idempotencyKey) {
        TransactionDTO row = required(idempotencyKey);
        LedgerPosting posting = toPosting(row);
        if (row.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.POSTED) {
            return posting;
        }
        if (row.getTransactionStatus() != TransactionDTO.TransactionStatusEnum.PENDING) {
            throw invalidPosting("Only a pending hold can be confirmed.");
        }
        return atomicPosting(body(postings.confirmLedgerPosting(row.getTransactionId(), null)));
    }

    @Override
    public void releaseHold(String idempotencyKey) {
        TransactionDTO row = lookup(idempotencyKey);
        if (row == null || row.getTransactionStatus() == TransactionDTO.TransactionStatusEnum.REVERSED) {
            return;
        }
        atomicPosting(body(postings.releaseLedgerPosting(row.getTransactionId(), null)));
    }

    @Override
    public LedgerPosting reverse(LedgerPosting original, String reversalKey) {
        if (original == null || original.transactionId() == null) {
            throw invalidPosting("A reversal needs the original transaction reference.");
        }
        validateLegs(original.legs(), original.currency());
        return atomicPosting(body(postings.reverseLedgerPosting(original.transactionId(),
                new LedgerReversalRequestDTO().idempotencyKey(reversalKey), null)));
    }

    private LedgerPosting write(String idempotencyKey, List<LedgerLeg> legs, TransactionDTO.TransactionStatusEnum status) {
        String currency = legs == null || legs.isEmpty() || legs.getFirst() == null ? null : legs.getFirst().currency();
        BigDecimal amount = validateLegs(legs, currency);
        LedgerPostingHeaderDTO request = new LedgerPostingHeaderDTO()
                .externalReference(idempotencyKey)
                .requestId(idempotencyKey)
                .transactionType(LedgerPostingHeaderDTO.TransactionTypeEnum.TRANSFER)
                .transactionStatus(LedgerPostingHeaderDTO.TransactionStatusEnum.fromValue(status.getValue()))
                .totalAmount(amount)
                .currency(currency)
                .description("Payment movement")
                .accountId(legs.get(0).accountId());
        List<LedgerPostingLegDTO> entries = legs.stream().map(leg -> new LedgerPostingLegDTO()
                .accountId(leg.accountId()).accountSpaceId(leg.accountSpaceId()).legType(leg.side().name())
                .amount(leg.amount()).currency(leg.currency())).toList();
        // The core's database claim is authoritative; optional HTTP response caches must not decide replay.
        return atomicPosting(body(postings.createLedgerPosting(new LedgerPostingRequestDTO()
                .idempotencyKey(idempotencyKey).transaction(request).legs(entries), null)));
    }

    private static LedgerPosting atomicPosting(LedgerPostingDTO response) {
        if (response.getTransaction() == null || response.getLegs() == null) {
            throw invalidPosting("The atomic ledger response is incomplete.");
        }
        List<LedgerLeg> entries = new ArrayList<>();
        Set<UUID> identities = new HashSet<>();
        for (TransactionLegDTO leg : response.getLegs()) {
            if (leg == null || leg.getTransactionLegId() == null || !identities.add(leg.getTransactionLegId())
                    || response.getTransaction().getTransactionId() == null
                    || !response.getTransaction().getTransactionId().equals(leg.getTransactionId())
                    || !("DEBIT".equals(leg.getLegType()) || "CREDIT".equals(leg.getLegType()))) {
                throw invalidPosting("The atomic ledger response has inconsistent leg identities or sides.");
            }
            entries.add(new LedgerLeg(leg.getAccountId(), leg.getAccountSpaceId(), EntrySide.valueOf(leg.getLegType()),
                    leg.getAmount(), leg.getCurrency()));
        }
        return checkedPosting(response.getTransaction(), entries);
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
            return transactions.findByExternalReference(idempotencyKey, null).block(TIMEOUT);
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

    private LedgerPosting toPosting(TransactionDTO row) {
        if (row.getTransactionId() == null) {
            throw invalidPosting("The ledger posting has no transaction reference.");
        }
        List<LedgerLeg> legs = new ArrayList<>();
        Set<UUID> legIds = new HashSet<>();
        Long totalElements = null;
        Integer totalPages = null;
        for (int page = 0; ; page++) {
            PaginationResponse response = body(legsApi.listTransactionLegs(row.getTransactionId(), page, LEG_PAGE_SIZE,
                    "transactionLegId", "ASC", null));
            if (response.getContent() == null || response.getContent().isEmpty()
                    || response.getCurrentPage() == null || response.getCurrentPage() != page
                    || response.getTotalElements() == null || response.getTotalElements() < 2
                    || response.getTotalPages() == null || response.getTotalPages() < 1
                    || response.getTotalPages() > response.getTotalElements()) {
                throw invalidPosting("The ledger returned an incomplete leg page.");
            }
            if (page == 0) {
                totalElements = response.getTotalElements();
                totalPages = response.getTotalPages();
            } else if (!totalElements.equals(response.getTotalElements()) || !totalPages.equals(response.getTotalPages())) {
                throw invalidPosting("The ledger leg page totals changed during reconstruction.");
            }
            for (Object value : response.getContent()) {
                TransactionLegDTO leg;
                try {
                    leg = LEG_MAPPER.convertValue(value, TransactionLegDTO.class);
                } catch (IllegalArgumentException ex) {
                    throw invalidPosting("The ledger returned a malformed leg.");
                }
                if (leg == null || leg.getTransactionLegId() == null || !legIds.add(leg.getTransactionLegId())
                        || !row.getTransactionId().equals(leg.getTransactionId())
                        || !("DEBIT".equals(leg.getLegType()) || "CREDIT".equals(leg.getLegType()))) {
                    throw invalidPosting("The ledger returned an inconsistent leg identity or side.");
                }
                legs.add(new LedgerLeg(leg.getAccountId(), leg.getAccountSpaceId(), EntrySide.valueOf(leg.getLegType()),
                        leg.getAmount(), leg.getCurrency()));
            }
            if (legs.size() > totalElements) {
                throw invalidPosting("The ledger returned more legs than its declared total.");
            }
            if (page == totalPages - 1) {
                break;
            }
        }
        if (legs.size() != totalElements) {
            throw invalidPosting("The ledger did not return all persisted legs.");
        }
        return checkedPosting(row, legs);
    }

    private static LedgerPosting checkedPosting(TransactionDTO row, List<LedgerLeg> legs) {
        BigDecimal amount = validateLegs(legs, row.getCurrency());
        if (row.getTransactionId() == null || row.getExternalReference() == null || row.getExternalReference().isBlank()
                || row.getTotalAmount() == null || row.getTotalAmount().compareTo(amount) != 0) {
            throw invalidPosting("The ledger transaction header does not match its persisted legs.");
        }
        return new LedgerPosting(row.getTransactionId(), row.getExternalReference(), row.getCurrency(), List.copyOf(legs));
    }

    private static BigDecimal validateLegs(List<LedgerLeg> legs, String currency) {
        if (legs == null || legs.size() < 2 || currency == null || currency.isBlank()) {
            throw invalidPosting("A ledger posting needs a currency, debit and credit.");
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LedgerLeg leg : legs) {
            if (leg == null || leg.accountId() == null || leg.side() == null || leg.amount() == null
                    || leg.amount().signum() <= 0 || !currency.equals(leg.currency())) {
                throw invalidPosting("Ledger legs need accounts, sides and positive amounts in the transaction currency.");
            }
            if (leg.side() == EntrySide.DEBIT) {
                debit = debit.add(leg.amount());
            } else {
                credit = credit.add(leg.amount());
            }
        }
        if (debit.signum() <= 0 || debit.compareTo(credit) != 0) {
            throw invalidPosting("Ledger debits and credits must balance and be positive.");
        }
        return debit;
    }

    private static PaymentFlowException invalidPosting(String message) {
        return new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, message);
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
