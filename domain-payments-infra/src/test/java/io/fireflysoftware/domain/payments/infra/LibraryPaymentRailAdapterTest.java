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

import io.fireflysoftware.domain.payments.RailKind;
import io.fireflysoftware.domain.payments.SyncMessages.CompensationRequest;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.rails.domain.PaymentStatus;
import io.fireflysoftware.rails.domain.TransactionType;
import io.fireflysoftware.rails.dtos.payments.InitiatePaymentRequest;
import io.fireflysoftware.rails.dtos.payments.PaymentResponse;
import io.fireflysoftware.rails.dtos.payments.ReturnRequest;
import io.fireflysoftware.rails.dtos.payments.ReturnResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryPaymentRailAdapterTest {

    @Test
    void outboundInternationalUsesWireTransferAndReturnUsesRequestReturn() {
        AtomicReference<InitiatePaymentRequest> initiated = new AtomicReference<>();
        AtomicReference<ReturnRequest> returned = new AtomicReference<>();
        io.fireflysoftware.rails.adapter.ports.PaymentRailPort rails = proxy(initiated, returned);
        LibraryPaymentRailAdapter adapter = new LibraryPaymentRailAdapter(rails);
        SyncResult result = adapter.sendOutbound(new OutboundInstruction("wire-1", new BigDecimal("40.00"), "EUR",
                UUID.fromString("10000000-0000-4000-8000-000000000011"), "Ada", "Beneficiary",
                "DE89370400440532013000", "COBADEFFXXX", RailKind.SWIFT, null,
                UUID.fromString("00000000-0000-4000-8000-0000000000c4")));
        assertTrue(result.accepted());
        assertEquals("rail-1", result.reference());
        assertEquals(TransactionType.WIRE_TRANSFER, initiated.get().getTransactionType());

        SyncResult compensation = adapter.returnOrReject(new CompensationRequest("rail-1", new BigDecimal("40.00"),
                "EUR", RailKind.SWIFT, null));
        assertEquals("rail-1", compensation.reference());
        assertEquals("rail-1", returned.get().getId());
    }

    @Test
    void domesticCreditTransferDoesNotUseTheWireOperation() {
        AtomicReference<InitiatePaymentRequest> initiated = new AtomicReference<>();
        LibraryPaymentRailAdapter adapter = new LibraryPaymentRailAdapter(proxy(initiated, new AtomicReference<>()));
        adapter.sendOutbound(new OutboundInstruction("sepa-1", new BigDecimal("10.00"), "EUR",
                UUID.fromString("10000000-0000-4000-8000-000000000011"), "Ada", "Beneficiary",
                "DE89370400440532013000", "COBADEFFXXX", RailKind.SEPA, null, null));
        assertEquals(TransactionType.CREDIT_TRANSFER, initiated.get().getTransactionType());
    }

    private static io.fireflysoftware.rails.adapter.ports.PaymentRailPort proxy(
            AtomicReference<InitiatePaymentRequest> initiated,
            AtomicReference<ReturnRequest> returned) {
        return (io.fireflysoftware.rails.adapter.ports.PaymentRailPort) Proxy.newProxyInstance(
                LibraryPaymentRailAdapterTest.class.getClassLoader(),
                new Class[]{io.fireflysoftware.rails.adapter.ports.PaymentRailPort.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "rail";
                            case "hashCode" -> 1;
                            case "equals" -> false;
                            default -> null;
                        };
                    }
                    if ("initiatePayment".equals(method.getName())) {
                        initiated.set((InitiatePaymentRequest) args[0]);
                        return Mono.just(ResponseEntity.ok(PaymentResponse.builder()
                                .paymentId("rail-1")
                                .status(PaymentStatus.COMPLETED)
                                .build()));
                    }
                    if ("requestReturn".equals(method.getName())) {
                        returned.set((ReturnRequest) args[0]);
                        return Mono.just(ResponseEntity.ok(ReturnResponse.builder().id("rail-1").status("returned").build()));
                    }
                    return Mono.empty();
                });
    }
}
