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
import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLedgerAdapterTest {

    @Test
    void postsABalancedTransactionAndTwoLegs() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + path + " " + body);
            byte[] response;
            if (path.contains("/by-reference/")) {
                response = new byte[0];
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (path.endsWith("/legs")) {
                response = "{\"transactionLegId\":\"10000000-0000-4000-8000-000000000099\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                response = "{\"transactionId\":\"10000000-0000-4000-8000-000000000077\",\"currency\":\"EUR\",\"totalAmount\":25.00,\"accountId\":\"10000000-0000-4000-8000-000000000011\"}"
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpLedgerAdapter adapter = new HttpLedgerAdapter(base);
            UUID debit = UUID.fromString("10000000-0000-4000-8000-000000000011");
            UUID credit = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
            LedgerPosting posting = adapter.post("outbound-1", List.of(
                    new LedgerLeg(debit, null, EntrySide.DEBIT, new BigDecimal("25.00"), "EUR"),
                    new LedgerLeg(credit, null, EntrySide.CREDIT, new BigDecimal("25.00"), "EUR")));
            assertEquals(UUID.fromString("10000000-0000-4000-8000-000000000077"), posting.transactionId());
            String joined = String.join("\n", calls);
            assertTrue(joined.contains("POST /api/v1/transactions "));
            assertTrue(joined.contains("/api/v1/transactions/10000000-0000-4000-8000-000000000077/legs"));
            assertTrue(joined.contains("\"legType\":\"DEBIT\""));
            assertTrue(joined.contains("\"legType\":\"CREDIT\""));
            assertTrue(joined.contains("\"externalReference\":\"outbound-1\""));
            assertTrue(joined.contains("\"transactionStatus\":\"POSTED\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void holdConfirmAndCancelUseLedgerStatus() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        String transaction = "{\"transactionId\":\"10000000-0000-4000-8000-000000000077\",\"transactionStatus\":\"PENDING\",\"currency\":\"EUR\",\"totalAmount\":25.00,\"accountId\":\"10000000-0000-4000-8000-000000000011\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + path + "?" + query + " " + body);
            byte[] response = transaction.getBytes(StandardCharsets.UTF_8);
            int status = path.endsWith("/legs") || "POST".equals(exchange.getRequestMethod()) ? 201 : 200;
            if (path.contains("/by-reference/") && calls.stream().noneMatch(call -> call.startsWith("POST "))) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            HttpLedgerAdapter adapter = new HttpLedgerAdapter("http://127.0.0.1:" + server.getAddress().getPort());
            UUID debit = UUID.fromString("10000000-0000-4000-8000-000000000011");
            UUID credit = UUID.fromString("44444444-4444-4444-8444-444444444441");
            adapter.hold("hold-1", List.of(
                    new LedgerLeg(debit, null, EntrySide.DEBIT, new BigDecimal("25.00"), "EUR"),
                    new LedgerLeg(credit, null, EntrySide.CREDIT, new BigDecimal("25.00"), "EUR")));
            adapter.confirmHold("hold-1");
            adapter.releaseHold("hold-1");
            String joined = String.join("\n", calls);
            assertTrue(joined.contains("\"transactionStatus\":\"PENDING\""));
            assertTrue(joined.contains("PATCH /api/v1/transactions/10000000-0000-4000-8000-000000000077/status?newStatus=POSTED"));
            assertTrue(joined.contains("PATCH /api/v1/transactions/10000000-0000-4000-8000-000000000077/status?newStatus=REVERSED"));
        } finally {
            server.stop(0);
        }
    }
}
