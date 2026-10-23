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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentPhase;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentConfigurationAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentMasterAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRailAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRecordAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPspAdapter;
import io.fireflysoftware.domain.payments.core.tcc.PaymentTccHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkLedgerAdapterHttpTest {
    private static final UUID DEBIT = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID CREDIT = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final UUID SPACE = UUID.fromString("10000000-0000-4000-8000-000000000013");
    private static final BigDecimal AMOUNT = new BigDecimal("25.00");
    private LedgerHttpFixture ledger;
    private SdkLedgerAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        ledger = new LedgerHttpFixture();
        adapter = new SdkLedgerAdapter(ledger.baseUrl());
    }

    @AfterEach
    void stop() {
        ledger.close();
    }

    @Test
    void confirmedHoldCanBeReversedOnceAcrossAdapterRestarts() {
        LedgerPosting hold = adapter.hold("payment", pair());
        LedgerPosting confirmed = adapter.confirmHold("payment");
        assertEquals(pair(), confirmed.legs());
        assertEquals(hold.transactionId(), confirmed.transactionId());
        assertEquals(confirmed, new SdkLedgerAdapter(ledger.baseUrl()).confirmHold("payment"));
        assertEquals(1, ledger.statusWrites);

        LedgerPosting reversal = adapter.reverse(confirmed, "rejection-compensation");
        assertEquals(List.of(
                new LedgerLeg(DEBIT, SPACE, EntrySide.CREDIT, AMOUNT, "EUR"),
                new LedgerLeg(CREDIT, null, EntrySide.DEBIT, AMOUNT, "EUR")), reversal.legs());
        assertEquals(hold.transactionId().toString(), ledger.transaction("rejection-compensation").get("relatedTransactionId"));
        assertEquals("REVERSAL", ledger.transaction("rejection-compensation").get("relationType"));
        LedgerPosting replay = new SdkLedgerAdapter(ledger.baseUrl()).reverse(confirmed, "rejection-compensation");
        assertEquals(reversal, replay);
        assertEquals(reversal, adapter.findByIdempotencyKey("rejection-compensation").orElseThrow());
        assertEquals(2, ledger.transactionWrites);
        assertEquals(4, ledger.legWrites);
        assertEquals("POSTED", ledger.transaction("payment").get("transactionStatus"));
    }

    @Test
    void actualTccProviderRejectionUsesPersistedConfirmedLegs() {
        SandboxPaymentRailAdapter rail = new SandboxPaymentRailAdapter();
        rail.rejectOutbound("rejected-payment");
        PaymentTccHub hub = PaymentTccHub.sandbox(adapter, new SandboxPaymentRecordAdapter(), rail,
                new SandboxPspAdapter(), new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
        MovementCommand command = new MovementCommand(PaymentScheme.INTERNATIONAL_OUTBOUND, "rejected-payment", AMOUNT, "EUR",
                UUID.fromString("10000000-0000-4000-8000-0000000000aa"),
                UUID.fromString("10000000-0000-4000-8000-000000000021"), DEBIT, null,
                null, null, null, UUID.fromString("10000000-0000-4000-8000-000000000031"),
                "Beneficiary", "DE89370400440532013000", "COBADEFFXXX", null);
        var tried = hub.tryPayment(command);
        var result = hub.confirm(tried.paymentId(), tried.challengeValue());
        assertEquals(PaymentPhase.CANCELLED, result.phase());
        assertTrue(adapter.findByIdempotencyKey("rejected-payment:reversal").isPresent());
        LedgerPosting reversal = adapter.findByIdempotencyKey("rejected-payment:reversal").orElseThrow();
        assertEquals(EntrySide.CREDIT, reversal.legs().get(0).side());
        assertEquals(DEBIT, reversal.legs().get(0).accountId());
        assertEquals(AMOUNT, reversal.legs().get(0).amount());
        assertEquals(ledger.transaction("rejected-payment").get("transactionId"),
                ledger.transaction("rejected-payment:reversal").get("relatedTransactionId"));
        assertEquals(2, ledger.transactionWrites);
        assertEquals(4, ledger.legWrites);
    }

    @Test
    void existingReversalReturnsItsPersistedOppositeLegs() {
        List<LedgerLeg> reversed = List.of(
                new LedgerLeg(DEBIT, SPACE, EntrySide.CREDIT, AMOUNT, "EUR"),
                new LedgerLeg(CREDIT, null, EntrySide.DEBIT, AMOUNT, "EUR"));
        ledger.seed("existing-reversal", "POSTED", AMOUNT, reversed);
        ledger.seed("original", "POSTED", AMOUNT, pair());
        UUID originalId = UUID.fromString((String) ledger.transaction("original").get("transactionId"));
        ledger.transaction("existing-reversal").put("relatedTransactionId", originalId.toString());
        ledger.transaction("existing-reversal").put("relationType", "REVERSAL");
        LedgerPosting original = new LedgerPosting(originalId, "original", "EUR", pair());
        assertEquals(reversed, adapter.reverse(original, "existing-reversal").legs());
        assertEquals(0, ledger.transactionWrites);
        assertEquals(0, ledger.legWrites);
    }

    @Test
    void lookupPreservesPersistedLegsAndChangedIntentCannotBypassTheClaim() {
        ledger.seed("posted", "POSTED", AMOUNT, pair());
        List<LedgerLeg> unrelated = List.of(
                new LedgerLeg(CREDIT, null, EntrySide.DEBIT, BigDecimal.ONE, "USD"),
                new LedgerLeg(DEBIT, null, EntrySide.CREDIT, BigDecimal.ONE, "USD"));
        assertEquals(pair(), adapter.findByIdempotencyKey("posted").orElseThrow().legs());
        assertThrows(PaymentFlowException.class, () -> adapter.post("posted", unrelated));
        assertThrows(PaymentFlowException.class, () -> adapter.hold("posted", unrelated));
        ledger.seed("pending", "PENDING", AMOUNT, pair());
        assertThrows(PaymentFlowException.class, () -> adapter.hold("pending", unrelated));
        assertTrue(adapter.findByIdempotencyKey("pending").isEmpty());
        assertEquals(0, ledger.transactionWrites);
    }

    @Test
    void reconstructsEveryPageWithoutRoundingMoney() {
        BigDecimal amount = new BigDecimal("123456789012345.67");
        List<LedgerLeg> entries = List.of(
                new LedgerLeg(DEBIT, SPACE, EntrySide.DEBIT, amount, "EUR"),
                new LedgerLeg(CREDIT, null, EntrySide.CREDIT, amount, "EUR"),
                new LedgerLeg(DEBIT, null, EntrySide.DEBIT, new BigDecimal("0.01"), "EUR"),
                new LedgerLeg(CREDIT, SPACE, EntrySide.CREDIT, new BigDecimal("0.01"), "EUR"));
        ledger.seed("paged", "POSTED", new BigDecimal("123456789012345.68"), entries);
        ledger.pageSize = 1;
        assertEquals(entries, adapter.findByIdempotencyKey("paged").orElseThrow().legs());
        assertEquals(List.of(0, 1, 2, 3), ledger.pages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "missing-content", "missing-currency", "mixed-currency", "missing-account", "missing-amount",
            "negative-amount", "unknown-side", "wrong-transaction", "unbalanced", "duplicate-leg", "incomplete-pages", "wrong-page",
            "changed-total", "missing-total", "wrong-header-amount"})
    void malformedPersistedHoldFailsBeforePostingOrReversalWrites(String defect) {
        ledger.seed("broken", "PENDING", AMOUNT, pair());
        ledger.defect = defect;
        PaymentFlowException error = assertThrows(PaymentFlowException.class, () -> adapter.confirmHold("broken"));
        assertEquals(PaymentFailure.UNBALANCED_POSTING, error.failure());
        assertEquals(0, ledger.statusWrites);
        assertEquals(0, ledger.transactionWrites);
        assertEquals(0, ledger.legWrites);
    }

    @Test
    void malformedPostedLookupDoesNotReturnAValidPosting() {
        ledger.seed("broken", "POSTED", AMOUNT, pair());
        ledger.defect = "empty";
        assertEquals(PaymentFailure.UNBALANCED_POSTING,
                assertThrows(PaymentFlowException.class, () -> adapter.findByIdempotencyKey("broken")).failure());
    }

    @Test
    void emptyReversalFailsDeliberatelyBeforeCreatingATransaction() {
        LedgerPosting incomplete = new LedgerPosting(UUID.randomUUID(), "original", "EUR", List.of());
        assertEquals(PaymentFailure.UNBALANCED_POSTING,
                assertThrows(PaymentFlowException.class, () -> adapter.reverse(incomplete, "reverse-empty")).failure());
        assertEquals(0, ledger.transactionWrites);
    }

    @Test
    void releasingAHoldRemainsIdempotent() {
        adapter.hold("release", pair());
        adapter.releaseHold("release");
        adapter.releaseHold("release");
        assertEquals(1, ledger.statusWrites);
        assertTrue(adapter.findByIdempotencyKey("release").isEmpty());
    }

    @Test
    void newWritesUseOneAtomicCommandWithoutLegacyFinancialWrites() {
        LedgerPosting posting = adapter.post("atomic", pair());
        assertEquals(pair(), posting.legs());
        assertEquals(posting, new SdkLedgerAdapter(ledger.baseUrl()).post("atomic", pair()));
        assertEquals(0, ledger.legacyWrites);
        assertEquals(2, ledger.atomicCreates);
        assertEquals(1, ledger.transactionWrites);
        assertEquals(2, ledger.legWrites);
    }

    private static List<LedgerLeg> pair() {
        return List.of(new LedgerLeg(DEBIT, SPACE, EntrySide.DEBIT, AMOUNT, "EUR"),
                new LedgerLeg(CREDIT, null, EntrySide.CREDIT, AMOUNT, "EUR"));
    }

    private static final class LedgerHttpFixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        private final HttpServer server;
        private final Map<String, Map<String, Object>> transactions = new HashMap<>();
        private final Map<String, List<Map<String, Object>>> legs = new HashMap<>();
        private final List<Integer> pages = new CopyOnWriteArrayList<>();
        private volatile int transactionWrites;
        private volatile int legWrites;
        private volatile int statusWrites;
        private volatile int legacyWrites;
        private volatile int atomicCreates;
        private final Map<String, Map<String, Object>> claims = new HashMap<>();
        private int pageSize = 20;
        private String defect = "";

        private LedgerHttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/transactions", this::handle);
            server.createContext("/api/v1/ledger-postings", this::atomic);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private Map<String, Object> transaction(String key) {
            return transactions.values().stream().filter(row -> key.equals(row.get("externalReference"))).findFirst().orElseThrow();
        }

        private void seed(String key, String status, BigDecimal amount, List<LedgerLeg> entries) {
            String id = UUID.randomUUID().toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transactionId", id);
            row.put("externalReference", key);
            row.put("transactionStatus", status);
            row.put("totalAmount", amount);
            row.put("currency", "EUR");
            transactions.put(id, row);
            List<Map<String, Object>> stored = new ArrayList<>();
            for (LedgerLeg entry : entries) {
                Map<String, Object> leg = new LinkedHashMap<>();
                leg.put("transactionLegId", UUID.randomUUID().toString());
                leg.put("transactionId", id);
                leg.put("accountId", entry.accountId().toString());
                leg.put("accountSpaceId", entry.accountSpaceId() == null ? null : entry.accountSpaceId().toString());
                leg.put("legType", entry.side().name());
                leg.put("amount", entry.amount());
                leg.put("currency", entry.currency());
                stored.add(leg);
            }
            legs.put(id, stored);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath().substring("/api/v1/transactions".length());
                String method = exchange.getRequestMethod();
                if (path.startsWith("/by-reference/")) {
                    String key = path.substring("/by-reference/".length());
                    Map<String, Object> row = transactions.values().stream()
                            .filter(candidate -> key.equals(candidate.get("externalReference"))).findFirst().orElse(null);
                    if (row == null) {
                        respond(exchange, 404, Map.of());
                    } else {
                        Map<String, Object> response = new LinkedHashMap<>(row);
                        if (defect.equals("wrong-header-amount")) response.put("totalAmount", BigDecimal.ONE);
                        respond(exchange, 200, response);
                    }
                } else if (path.isEmpty() && method.equals("POST")) {
                    legacyWrites++;
                    Map<String, Object> row = read(exchange);
                    String id = UUID.randomUUID().toString();
                    row.put("transactionId", id);
                    transactions.put(id, row);
                    legs.put(id, new ArrayList<>());
                    transactionWrites++;
                    respond(exchange, 200, row);
                } else {
                    String id = path.split("/")[1];
                    if (path.endsWith("/status")) {
                        legacyWrites++;
                        transactions.get(id).put("transactionStatus", query(exchange).get("newStatus"));
                        statusWrites++;
                        respond(exchange, 200, transactions.get(id));
                    } else if (path.endsWith("/legs") && method.equals("POST")) {
                        legacyWrites++;
                        Map<String, Object> leg = read(exchange);
                        leg.put("transactionLegId", UUID.randomUUID().toString());
                        legs.get(id).add(leg);
                        legWrites++;
                        respond(exchange, 200, leg);
                    } else if (path.endsWith("/legs") && method.equals("GET")) {
                        list(exchange, id);
                    } else {
                        respond(exchange, 404, Map.of());
                    }
                }
            } catch (Exception failure) {
                respond(exchange, 500, Map.of("fixtureError", failure.toString()));
            } finally {
                exchange.close();
            }
        }

        @SuppressWarnings("unchecked")
        private void atomic(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath().substring("/api/v1/ledger-postings".length());
                if (path.isEmpty()) {
                    atomicCreates++;
                    Map<String, Object> request = read(exchange);
                    String key = (String) request.get("idempotencyKey");
                    Map<String, Object> existing = transactions.values().stream().filter(row -> key.equals(row.get("externalReference"))).findFirst().orElse(null);
                    if (existing != null) {
                        if (!request.equals(claims.get(key))) {
                            respond(exchange, 409, Map.of());
                        } else {
                            respond(exchange, 200, aggregate(existing, true));
                        }
                        return;
                    }
                    claims.put(key, request);
                    Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) request.get("transaction"));
                    String id = UUID.randomUUID().toString();
                    row.put("transactionId", id);
                    row.put("externalReference", key);
                    transactions.put(id, row);
                    List<Map<String, Object>> stored = new ArrayList<>();
                    for (Map<String, Object> entry : (List<Map<String, Object>>) request.get("legs")) {
                        Map<String, Object> leg = new LinkedHashMap<>(entry);
                        leg.put("transactionId", id);
                        leg.put("transactionLegId", UUID.randomUUID().toString());
                        stored.add(leg);
                    }
                    legs.put(id, stored);
                    transactionWrites++;
                    legWrites += stored.size();
                    respond(exchange, 201, aggregate(row, false));
                    return;
                }
                String id = path.split("/")[1];
                Map<String, Object> original = transactions.get(id);
                if (original == null) {
                    respond(exchange, 404, Map.of());
                } else if (path.endsWith("/reversal")) {
                    String key = (String) read(exchange).get("idempotencyKey");
                    Map<String, Object> existing = transactions.values().stream().filter(row -> key.equals(row.get("externalReference"))).findFirst().orElse(null);
                    if (existing != null) {
                        respond(exchange, id.equals(existing.get("relatedTransactionId")) ? 200 : 409, aggregate(existing, true));
                        return;
                    }
                    List<LedgerLeg> opposite = legs.get(id).stream().map(leg -> new LedgerLeg(
                            UUID.fromString((String) leg.get("accountId")), leg.get("accountSpaceId") == null ? null : UUID.fromString((String) leg.get("accountSpaceId")),
                            EntrySide.valueOf((String) leg.get("legType")).opposite(), (BigDecimal) leg.get("amount"), (String) leg.get("currency"))).toList();
                    seed(key, "POSTED", (BigDecimal) original.get("totalAmount"), opposite);
                    Map<String, Object> reversed = transaction(key);
                    reversed.put("relationType", "REVERSAL");
                    reversed.put("relatedTransactionId", id);
                    transactionWrites++;
                    legWrites += opposite.size();
                    respond(exchange, 201, aggregate(reversed, false));
                } else {
                    String target = path.endsWith("/confirm") ? "POSTED" : "REVERSED";
                    if (!target.equals(original.get("transactionStatus"))) {
                        boolean compensated = transactions.values().stream().anyMatch(row -> id.equals(row.get("relatedTransactionId")) && "REVERSAL".equals(row.get("relationType")));
                        if (!"PENDING".equals(original.get("transactionStatus"))) {
                            respond(exchange, compensated && target.equals("REVERSED") ? 200 : 409, aggregate(original, true));
                            return;
                        }
                        original.put("transactionStatus", target);
                        statusWrites++;
                    }
                    respond(exchange, 200, aggregate(original, false));
                }
            } catch (Exception failure) {
                respond(exchange, 500, Map.of("fixtureError", failure.toString()));
            } finally {
                exchange.close();
            }
        }

        private Map<String, Object> aggregate(Map<String, Object> row, boolean replayed) {
            return Map.of("transaction", row, "legs", legs.get(row.get("transactionId")), "replayed", replayed);
        }

        private void list(HttpExchange exchange, String id) throws IOException {
            int page = Integer.parseInt(query(exchange).getOrDefault("page", "0"));
            pages.add(page);
            List<Map<String, Object>> stored = new ArrayList<>();
            for (Map<String, Object> leg : legs.get(id)) stored.add(new LinkedHashMap<>(leg));
            if (defect.equals("empty")) stored.clear();
            if (!stored.isEmpty()) {
                Map<String, Object> first = stored.getFirst();
                switch (defect) {
                    case "missing-currency" -> first.remove("currency");
                    case "mixed-currency" -> first.put("currency", "USD");
                    case "missing-account" -> first.remove("accountId");
                    case "missing-amount" -> first.remove("amount");
                    case "negative-amount" -> first.put("amount", new BigDecimal("-25.00"));
                    case "unknown-side" -> first.put("legType", "OTHER");
                    case "wrong-transaction" -> first.put("transactionId", UUID.randomUUID().toString());
                    case "unbalanced" -> first.put("amount", BigDecimal.ONE);
                    case "duplicate-leg" -> stored.get(1).put("transactionLegId", first.get("transactionLegId"));
                    default -> { }
                }
            }
            int limit = defect.equals("changed-total") ? 1 : pageSize;
            int from = Math.min(page * limit, stored.size());
            int to = Math.min(from + limit, stored.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", stored.subList(from, to));
            result.put("totalElements", stored.size());
            result.put("totalPages", (stored.size() + limit - 1) / limit);
            result.put("currentPage", page);
            switch (defect) {
                case "missing-content" -> result.remove("content");
                case "incomplete-pages" -> result.put("totalElements", 3);
                case "wrong-page" -> result.put("currentPage", page + 1);
                case "changed-total" -> { if (page > 0) result.put("totalElements", 3); }
                case "missing-total" -> result.remove("totalElements");
                default -> { }
            }
            respond(exchange, 200, result);
        }

        private Map<String, Object> read(HttpExchange exchange) throws IOException {
            return json.readValue(exchange.getRequestBody(), new TypeReference<>() { });
        }

        private Map<String, String> query(HttpExchange exchange) {
            Map<String, String> result = new HashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) {
                for (String part : raw.split("&")) {
                    String[] entry = part.split("=", 2);
                    result.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
                }
            }
            return result;
        }

        private void respond(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
