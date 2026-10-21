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

import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.domain.payments.PositionKind;
import io.fireflysoftware.domain.payments.PositionMovement;
import io.fireflysoftware.domain.payments.PspInstrument;
import io.fireflysoftware.domain.payments.RailKind;
import io.fireflysoftware.domain.payments.SyncMessages.OutboundInstruction;
import io.fireflysoftware.domain.payments.SyncMessages.SyncResult;
import io.fireflysoftware.rails.domain.PaymentStatus;
import io.fireflysoftware.rails.dtos.payments.PaymentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAdapterTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void railAdapterCallsInitiatePayment() {
        AtomicInteger calls = new AtomicInteger();
        io.fireflysoftware.rails.adapter.ports.PaymentRailPort rails = proxy(io.fireflysoftware.rails.adapter.ports.PaymentRailPort.class, (method, args) -> {
            if ("initiatePayment".equals(method.getName())) {
                calls.incrementAndGet();
                PaymentResponse response = PaymentResponse.builder().paymentId("rail-1").status(PaymentStatus.COMPLETED).build();
                return Mono.just(ResponseEntity.ok(response));
            }
            return Mono.empty();
        });
        LibraryPaymentRailAdapter adapter = new LibraryPaymentRailAdapter(rails);
        SyncResult result = adapter.sendOutbound(new OutboundInstruction("swift-1", new BigDecimal("10.00"), "EUR",
                UUID.randomUUID(), "Customer", "Beneficiary", "DE89370400440532013000", "COBADEFFXXX",
                RailKind.SWIFT, null, UUID.randomUUID()));
        assertTrue(result.accepted());
        assertEquals("rail-1", result.reference());
        assertEquals(1, calls.get());
        assertTrue(adapter.supportsReturn());
    }

    @Test
    void spaceMovementConfirmsSpacesOnTheAccountsDomain() throws Exception {
        UUID account = UUID.fromString("10000000-0000-4000-8000-000000000011");
        UUID from = UUID.fromString("10000000-0000-4000-8000-000000000041");
        UUID to = UUID.fromString("10000000-0000-4000-8000-000000000042");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/backoffice/accounts/" + account + "/spaces", exchange -> {
            byte[] body = ("{\"content\":[{\"accountSpaceId\":\"" + from + "\"},{\"accountSpaceId\":\"" + to + "\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        HttpDomainAccountsAdapter adapter = new HttpDomainAccountsAdapter("http://127.0.0.1:" + server.getAddress().getPort());
        adapter.apply(new PositionMovement("space-1", PositionKind.SPACE_TRANSFER, account, account, from, to, "SAVINGS",
                new BigDecimal("5.00"), "EUR"));
        adapter.apply(new PositionMovement("space-1", PositionKind.SPACE_TRANSFER, account, account, from, to, "SAVINGS",
                new BigDecimal("5.00"), "EUR"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> 1;
                    default -> false;
                };
            }
            return call.invoke(method, args);
        });
    }

    @FunctionalInterface
    private interface Call {
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }
}
