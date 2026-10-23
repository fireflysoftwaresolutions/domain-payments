package io.fireflysoftware.domain.payments.infra;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentPhase;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentConfigurationAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentMasterAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRailAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPaymentRecordAdapter;
import io.fireflysoftware.domain.payments.core.sandbox.SandboxPspAdapter;
import io.fireflysoftware.domain.payments.core.tcc.PaymentTccHub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit qualification: -Dtest=LedgerAtomicRuntimeIT -Dledger.runtime.jar=/absolute/producer.jar. */
@Testcontainers
class LedgerAtomicRuntimeIT {
    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ledger_adapter_qualification").withUsername("synthetic_ledger")
            .withPassword("synthetic-disposable-password")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));

    private static Process core;
    private static int port;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        startCore();
    }

    private static void startCore() throws Exception {
        String artifact = System.getProperty("ledger.runtime.jar");
        assertThat(artifact).as("Package the exact ledger producer and pass -Dledger.runtime.jar=/absolute/path.jar").isNotBlank();
        assertThat(Files.isRegularFile(Path.of(artifact))).as(artifact).isTrue();
        ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", artifact, "--server.address=127.0.0.1", "--server.port=" + port,
                "--firefly.eda.enabled=false", "--spring.r2dbc.url=r2dbc:postgresql://" + DATABASE.getHost() + ":"
                + DATABASE.getMappedPort(5432) + "/" + DATABASE.getDatabaseName(),
                "--spring.r2dbc.username=" + DATABASE.getUsername(), "--spring.r2dbc.password=" + DATABASE.getPassword(),
                "--spring.flyway.url=" + DATABASE.getJdbcUrl(), "--spring.flyway.user=" + DATABASE.getUsername(),
                "--spring.flyway.password=" + DATABASE.getPassword());
        process.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of("target", "ledger-synthetic-core.log").toFile()));
        core = process.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && core.isAlive()) {
            try {
                var response = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return;
            } catch (java.io.IOException ignored) {
                // The owned child process has not opened its loopback listener yet.
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Disposable ledger did not become ready; inspect target/ledger-synthetic-core.log");
    }

    @AfterAll
    static void stopCore() throws Exception {
        if (core != null && core.isAlive()) {
            core.destroy();
            if (!core.waitFor(10, TimeUnit.SECONDS)) {
                core.destroyForcibly();
                core.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void aFreshCoreProcessAndAdapterConfirmTheOriginalPersistedHold() throws Exception {
        String key = UUID.randomUUID().toString();
        var entries = pair(new BigDecimal("123456789012345.670000000000000001"));
        var held = new SdkLedgerAdapter(baseUrl()).hold(key, entries);
        stopCore();
        startCore();
        var confirmed = new SdkLedgerAdapter(baseUrl()).confirmHold(key);
        assertThat(confirmed.transactionId()).isEqualTo(held.transactionId());
        assertMoney(entries, confirmed.legs());
        assertMoney(entries, new SdkLedgerAdapter(baseUrl()).confirmHold(key).legs());
        assertThat(count("SELECT count(*) FROM transaction WHERE external_reference='" + key + "' AND transaction_status='posted'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transaction_status_history WHERE transaction_id='" + held.transactionId() + "'")).isEqualTo(2);
    }

    @Test
    void concurrentSdkCallsCreateOneAggregateAndChangedIntentCannotReplayIt() throws Exception {
        String key = UUID.randomUUID().toString();
        var entries = pair(new BigDecimal("10.25"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> new SdkLedgerAdapter(baseUrl()).post(key, entries))).toList();
            var ids = new java.util.HashSet<UUID>();
            for (var request : requests) ids.add(request.get(30, TimeUnit.SECONDS).transactionId());
            assertThat(ids).hasSize(1);
        }
        assertThatThrownBy(() -> new SdkLedgerAdapter(baseUrl()).post(key, pair(new BigDecimal("99.99"))))
                .isInstanceOf(PaymentFlowException.class);
        assertThat(count("SELECT count(*) FROM transaction WHERE external_reference='" + key + "' AND total_amount=10.25")).isEqualTo(1);
    }

    @Test
    void actualTccRejectionAppendsOneAuthoritativeReversalAndPreservesOriginalMoney() throws Exception {
        String key = UUID.randomUUID().toString();
        var ledger = new SdkLedgerAdapter(baseUrl());
        var rail = new SandboxPaymentRailAdapter();
        rail.rejectOutbound(key);
        var hub = PaymentTccHub.sandbox(ledger, new SandboxPaymentRecordAdapter(), rail, new SandboxPspAdapter(),
                new SandboxPaymentConfigurationAdapter(), new SandboxPaymentMasterAdapter());
        var entries = pair(new BigDecimal("25.00"));
        var command = new MovementCommand(PaymentScheme.INTERNATIONAL_OUTBOUND, key, new BigDecimal("25.00"), "EUR",
                UUID.randomUUID(), UUID.randomUUID(), entries.getFirst().accountId(), null, null, null, null,
                UUID.randomUUID(), "Synthetic beneficiary", "DE89370400440532013000", "COBADEFFXXX", null);
        var tried = hub.tryPayment(command);
        var cancelled = hub.confirm(tried.paymentId(), tried.challengeValue());
        assertThat(cancelled.phase()).isEqualTo(PaymentPhase.CANCELLED);
        var original = new SdkLedgerAdapter(baseUrl()).findByIdempotencyKey(key).orElseThrow();
        var reversal = new SdkLedgerAdapter(baseUrl()).findByIdempotencyKey(key + ":reversal").orElseThrow();
        var replay = new SdkLedgerAdapter(baseUrl()).reverse(original, key + ":reversal");
        assertThat(replay.transactionId()).isEqualTo(reversal.transactionId());
        assertThat(count("SELECT count(*) FROM transaction WHERE transaction_id='" + original.transactionId() + "' AND transaction_status='posted'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transaction WHERE related_transaction_id='" + original.transactionId() + "' AND managed_posting AND transaction_status='posted'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM (SELECT account_id,account_space_id,sum(CASE WHEN leg_type='DEBIT' THEN amount ELSE -amount END) AS net FROM transaction_leg WHERE transaction_id IN ('" + original.transactionId() + "','" + reversal.transactionId() + "') GROUP BY account_id,account_space_id HAVING sum(CASE WHEN leg_type='DEBIT' THEN amount ELSE -amount END)<>0) unbalanced")).isZero();
    }

    @Test
    void databaseFailureAfterFirstLegLeavesNoClaimOrHeaderAndCanBeRetried() throws Exception {
        String key = UUID.randomUUID().toString();
        var entries = pair(new BigDecimal("7.12"));
        String constraint = "synthetic_" + UUID.randomUUID().toString().replace("-", "");
        sql("ALTER TABLE transaction_leg ADD CONSTRAINT " + constraint + " CHECK(account_id<>'" + entries.get(1).accountId() + "')");
        try {
            assertThatThrownBy(() -> new SdkLedgerAdapter(baseUrl()).post(key, entries)).isInstanceOf(PaymentFlowException.class);
            assertThat(count("SELECT count(*) FROM transaction WHERE external_reference='" + key + "'")).isZero();
            assertThat(count("SELECT count(*) FROM ledger_posting_request WHERE idempotency_key='" + key + "'")).isZero();
            assertThat(count("SELECT count(*) FROM transaction_leg WHERE account_id='" + entries.getFirst().accountId() + "'")).isZero();
        } finally {
            sql("ALTER TABLE transaction_leg DROP CONSTRAINT " + constraint);
        }
        assertMoney(entries, new SdkLedgerAdapter(baseUrl()).post(key, entries).legs());
    }

    private static List<LedgerLeg> pair(BigDecimal amount) {
        return List.of(new LedgerLeg(UUID.randomUUID(), UUID.randomUUID(), EntrySide.DEBIT, amount, "EUR"),
                new LedgerLeg(UUID.randomUUID(), null, EntrySide.CREDIT, amount, "EUR"));
    }

    private static void assertMoney(List<LedgerLeg> expected, List<LedgerLeg> actual) {
        assertThat(actual).hasSameSizeAs(expected);
        for (var entry : expected) {
            var saved = actual.stream().filter(leg -> leg.accountId().equals(entry.accountId()) && leg.side() == entry.side()).findFirst().orElseThrow();
            assertThat(saved.accountSpaceId()).isEqualTo(entry.accountSpaceId());
            assertThat(saved.currency()).isEqualTo(entry.currency());
            assertThat(saved.amount()).isEqualByComparingTo(entry.amount());
        }
    }

    private static long count(String query) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static void sql(String query) throws Exception {
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(query);
        }
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
