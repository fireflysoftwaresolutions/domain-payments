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
import io.fireflysoftware.core.banking.ledger.sdk.api.LedgerPostingsApi;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerPostingDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.LedgerPostingRequestDTO;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.core.banking.ledger.sdk.model.PaginationResponse;
import io.fireflysoftware.core.banking.ledger.sdk.model.TransactionDTO;
import io.fireflysoftware.core.banking.ledger.sdk.model.TransactionLegDTO;
import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkLedgerAdapterTest {

    @Test
    void holdConfirmAndPostedReleaseUseAtomicLedgerSdk() {
        TransactionsApi transactions = mock(TransactionsApi.class);
        TransactionLegsApi legs = mock(TransactionLegsApi.class);
        LedgerPostingsApi postings = mock(LedgerPostingsApi.class);
        UUID transactionId = UUID.randomUUID();
        AtomicReference<TransactionDTO> stored = new AtomicReference<>();
        List<TransactionLegDTO> storedLegs = new ArrayList<>();
        when(transactions.findByExternalReference(any(), any())).thenAnswer(invocation -> {
            TransactionDTO row = stored.get();
            if (row == null) {
                return Mono.error(WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, new byte[0],
                        StandardCharsets.UTF_8));
            }
            return Mono.just(row);
        });
        when(postings.createLedgerPosting(any(), any())).thenAnswer(invocation -> {
            LedgerPostingRequestDTO request = invocation.getArgument(0);
            TransactionDTO created = new TransactionDTO(null, null, transactionId, null)
                    .externalReference(request.getIdempotencyKey())
                    .transactionStatus(TransactionDTO.TransactionStatusEnum.fromValue(request.getTransaction().getTransactionStatus().getValue()))
                    .currency(request.getTransaction().getCurrency())
                    .totalAmount(request.getTransaction().getTotalAmount());
            stored.set(created);
            for (var entry : request.getLegs()) {
                storedLegs.add(new TransactionLegDTO(null, null, UUID.randomUUID())
                        .transactionId(transactionId).accountId(entry.getAccountId())
                        .accountSpaceId(entry.getAccountSpaceId()).legType(entry.getLegType())
                        .amount(entry.getAmount()).currency(entry.getCurrency()));
            }
            return Mono.just(new LedgerPostingDTO().transaction(created).legs(storedLegs));
        });
        when(legs.listTransactionLegs(any(), any(), any(), any(), any(), any())).thenAnswer(invocation ->
                Mono.just(new PaginationResponse().content(new ArrayList<Object>(storedLegs)).totalElements((long) storedLegs.size())
                        .totalPages(1).currentPage(0)));
        when(postings.confirmLedgerPosting(any(), any())).thenAnswer(invocation -> {
            stored.get().setTransactionStatus(TransactionDTO.TransactionStatusEnum.POSTED);
            return Mono.just(new LedgerPostingDTO().transaction(stored.get()).legs(storedLegs));
        });
        when(postings.releaseLedgerPosting(any(), any())).thenReturn(Mono.error(WebClientResponseException.create(
                409, "Conflict", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)));
        SdkLedgerAdapter adapter = new SdkLedgerAdapter(transactions, legs, postings);
        UUID debit = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        List<LedgerLeg> pair = List.of(
                new LedgerLeg(debit, null, EntrySide.DEBIT, new BigDecimal("10.00"), "EUR"),
                new LedgerLeg(credit, null, EntrySide.CREDIT, new BigDecimal("10.00"), "EUR"));

        LedgerPosting hold = adapter.hold("hold-1", pair);
        LedgerPosting confirmed = adapter.confirmHold("hold-1");
        assertThrows(PaymentFlowException.class, () -> adapter.releaseHold("hold-1"));

        assertEquals(transactionId, hold.transactionId());
        assertEquals(2, hold.legs().size());
        assertEquals(pair, confirmed.legs());
        verify(postings).createLedgerPosting(any(), any());
        verify(postings).confirmLedgerPosting(transactionId, null);
        verify(postings).releaseLedgerPosting(transactionId, null);
        verify(transactions, never()).createTransaction(any(), any());
        verify(legs, never()).createTransactionLeg(any(), any(), any());
        verify(transactions, never()).updateTransactionStatus(any(), any(), any(), any());
        assertEquals(TransactionDTO.TransactionStatusEnum.POSTED, stored.get().getTransactionStatus());
    }
}
