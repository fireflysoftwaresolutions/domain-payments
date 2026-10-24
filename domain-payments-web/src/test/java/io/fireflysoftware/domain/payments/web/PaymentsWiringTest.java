package io.fireflysoftware.domain.payments.web;

import io.fireflysoftware.domain.payments.sca.ChallengeDeliveryPort;
import io.fireflysoftware.domain.payments.sca.SandboxChallengeDeliveryAdapter;
import io.fireflysoftware.domain.payments.sca.ScaChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentsWiringTest {

    private final ApplicationContextRunner sandbox = new ApplicationContextRunner()
            .withUserConfiguration(PaymentsWiring.class);

    private ApplicationContextRunner live() {
        return sandbox.withPropertyValues("firefly.payments.mode=live",
                        "firefly.payments.ledger-base-url=http://127.0.0.1:1",
                        "firefly.payments.accounts-base-url=http://127.0.0.1:1",
                        "firefly.payments.payments-core-base-url=http://127.0.0.1:1",
                        "firefly.payments.sca-base-url=http://127.0.0.1:1")
                .withBean(io.fireflysoftware.rails.adapter.ports.PaymentRailPort.class,
                        () -> mock(io.fireflysoftware.rails.adapter.ports.PaymentRailPort.class))
                .withBean(io.fireflysoftware.psps.adapter.ports.PaymentPort.class,
                        () -> mock(io.fireflysoftware.psps.adapter.ports.PaymentPort.class))
                .withBean(io.fireflysoftware.psps.adapter.ports.PayoutPort.class,
                        () -> mock(io.fireflysoftware.psps.adapter.ports.PayoutPort.class))
                .withBean(io.fireflysoftware.psps.adapter.ports.RefundPort.class,
                        () -> mock(io.fireflysoftware.psps.adapter.ports.RefundPort.class));
    }

    @Test
    void defaultSandboxNeedsNoScaEndpointAndValidatesItsOwnChallenge() {
        sandbox.run(context -> {
            assertThat(context).hasNotFailed();
            ScaChallengeService service = context.getBean(ScaChallengeService.class);
            var issued = service.create(UUID.randomUUID(), UUID.randomUUID());
            assertThat(issued.challengeValue()).matches("[0-9]{6}");
            assertThat(service.validate(issued.operationId(), issued.challengeId(), issued.challengeValue()).name())
                    .isEqualTo("VALIDATED");
            service.expire(issued.operationId(), issued.challengeId());
            assertThat(service.validate(issued.operationId(), issued.challengeId(), issued.challengeValue()).name())
                    .isEqualTo("FAILED");
        });
    }

    @Test
    void explicitSandboxIgnoresUnreachableLiveScaEndpoint() {
        sandbox.withPropertyValues("firefly.payments.mode=sandbox",
                "firefly.payments.sca-base-url=http://127.0.0.1:1").run(context -> {
            assertThat(context).hasNotFailed();
            var issued = context.getBean(ScaChallengeService.class).create(UUID.randomUUID(), UUID.randomUUID());
            assertThat(issued.challengeId()).isNotNull();
        });
    }

    @Test
    void liveRequiresExplicitChallengeDelivery() {
        live().run(context -> assertThat(context).hasFailed());
    }

    @Test
    void liveRejectsBuiltInSandboxDelivery() {
        live().withBean(ChallengeDeliveryPort.class, SandboxChallengeDeliveryAdapter::new)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void liveRejectsAopProxyAroundBuiltInSandboxDelivery() {
        ProxyFactory proxy = new ProxyFactory(new SandboxChallengeDeliveryAdapter());
        proxy.setInterfaces(ChallengeDeliveryPort.class);
        live().withBean(ChallengeDeliveryPort.class, () -> (ChallengeDeliveryPort) proxy.getProxy())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void liveAcceptsAnExplicitDeliveryPortWithoutCallingProvidersAtStartup() {
        live().withBean(ChallengeDeliveryPort.class, () -> (challengeId, code) -> {
            throw new AssertionError("Startup must not deliver a challenge");
        }).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScaChallengeService.class);
            assertThat(context).hasBean("liveSca").doesNotHaveBean("sandboxSca");
        });
    }

    @Test
    void suppliedYamlDefinesTheActualCoreAccountsProperty() throws Exception {
        var properties = new YamlPropertySourceLoader().load("payments", new ClassPathResource("application.yaml"));
        assertThat(properties).hasSize(1);
        assertThat(properties.getFirst().getProperty("firefly.payments.accounts-base-url"))
                .isEqualTo("${ACCOUNTS_CORE_URL:http://localhost:8080}");
        assertThat(properties.getFirst().getProperty("firefly.payments.accounts-domain-base-url")).isNull();
    }
}
