/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.domain.payments.sdk;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

class ManagedPaymentJourneysContractTest {
    @Test
    void genuineGeneratedJourneyApiUsesTypedSuccessBodiesAndNoInjectedActorParameters()
            throws Exception {
        var api =
                Class.forName(
                        "io.fireflysoftware.domain.payments.sdk.api.ManagedPaymentJourneysApi");
        for (String operation :
                List.of(
                        "createManagedPaymentJourney",
                        "getManagedPaymentJourney",
                        "decideManagedPaymentJourney",
                        "recoverManagedPaymentJourney",
                        "getManagedPaymentJourneyHistory")) {
            var methods =
                    Arrays.stream(api.getDeclaredMethods())
                            .filter(m -> m.getName().equals(operation))
                            .toList();
            assertThat(methods).hasSize(1);
            assertThat(methods.getFirst().getGenericReturnType().getTypeName())
                    .contains(
                            "io.fireflysoftware.domain.payments.sdk.model."
                                    + (operation.equals("getManagedPaymentJourneyHistory")
                                            ? "HistoryPage"
                                            : "View"));
            assertThat(Arrays.stream(methods.getFirst().getParameterTypes()).map(Class::getName))
                    .noneMatch(
                            n ->
                                    n.contains("Jwt")
                                            || n.contains("SecurityPrincipal")
                                            || n.contains("ExecutionContext"));
        }
        assertThat(Arrays.stream(api.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .noneMatch(
                        n ->
                                n.equals("claimManagedPaymentEffect")
                                        || n.equals("acknowledgeManagedPaymentEffect"));
    }

    @Test
    void generatedMoneyAndOriginalDeadlineKeepExactTypes() throws Exception {
        var create = Class.forName("io.fireflysoftware.domain.payments.sdk.model.Create");
        assertThat(create.getMethod("getAmount").getReturnType()).isEqualTo(BigDecimal.class);
        assertThat(create.getMethod("getCustomerNotAfterEpochMillis").getReturnType())
                .isEqualTo(Long.class);
        var view = Class.forName("io.fireflysoftware.domain.payments.sdk.model.View");
        assertThat(view.getMethod("getNotAfterEpochMillis").getReturnType()).isEqualTo(Long.class);
        assertThat(view.getMethod("getAmount").getReturnType()).isEqualTo(String.class);
        assertThat(view.getMethod("getLedgerState").getReturnType()).isEqualTo(String.class);
    }
}
