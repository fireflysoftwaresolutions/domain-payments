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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SdkLedgerAdapterTest {

    @Test
    void holdConfirmAndReleaseUseTheLedgerSdk() {
        TransactionsApi transactions = mock(TransactionsApi.class);
        TransactionLegsApi legs = mock(TransactionLegsApi.class);
        UUID transactionId = UUID.randomUUID();
        AtomicReference<TransactionDTO> stored = new AtomicReference<>();
        when(transactions.findByExternalReference(any(), any())).thenAnswer(invocation -> {
            TransactionDTO row = stored.get();
            if (row == null) {
                return Mono.error(WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, new byte[0],
                        StandardCharsets.UTF_8));
            }
            return Mono.just(row);
        });
        when(transactions.createTransaction(any(), any())).thenAnswer(invocation -> {
            TransactionDTO request = invocation.getArgument(0);
            TransactionDTO created = new TransactionDTO(null, null, transactionId, null)
                    .externalReference(request.getExternalReference())
                    .transactionStatus(request.getTransactionStatus())
                    .currency(request.getCurrency())
                    .totalAmount(request.getTotalAmount());
            stored.set(created);
            return Mono.just(created);
        });
        when(legs.createTransactionLeg(any(), any(), any())).thenReturn(Mono.just(new TransactionLegDTO()));
        when(transactions.updateTransactionStatus(any(), any(), any(), any())).thenAnswer(invocation -> {
            stored.get().setTransactionStatus(TransactionDTO.TransactionStatusEnum.fromValue(invocation.getArgument(1)));
            return Mono.just(stored.get());
        });
        SdkLedgerAdapter adapter = new SdkLedgerAdapter(transactions, legs);
        UUID debit = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        List<LedgerLeg> pair = List.of(
                new LedgerLeg(debit, null, EntrySide.DEBIT, new BigDecimal("10.00"), "EUR"),
                new LedgerLeg(credit, null, EntrySide.CREDIT, new BigDecimal("10.00"), "EUR"));

        LedgerPosting hold = adapter.hold("hold-1", pair);
        adapter.confirmHold("hold-1");
        adapter.releaseHold("hold-1");

        assertEquals(transactionId, hold.transactionId());
        assertEquals(2, hold.legs().size());
        verify(transactions).createTransaction(any(), any());
        verify(legs, times(2)).createTransactionLeg(any(), any(), any());
        verify(transactions).updateTransactionStatus(transactionId, "POSTED", "payment-confirm", "hold-1");
        verify(transactions).updateTransactionStatus(transactionId, "REVERSED", "payment-cancel", "hold-1");
        assertEquals(TransactionDTO.TransactionStatusEnum.REVERSED, stored.get().getTransactionStatus());
    }
}
