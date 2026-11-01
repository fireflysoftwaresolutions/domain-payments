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

package io.fireflysoftware.domain.payments.web.managed;

import static org.assertj.core.api.Assertions.*;

import io.fireflysoftware.domain.payments.core.managed.ManagedPaymentContext;

import org.fireflyframework.cqrs.query.DefaultQueryBus.QueryProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManagedPaymentTransportErrorsTest {
    @Test
    void onlyExactKnownNativeQueryWrapperCanPreserveSanitizedDenial() {
        var original = ManagedPaymentContext.fail(403, "synthetic-private-reason");
        var result =
                ManagedPaymentsController.queryFailure(
                        new QueryProcessingException("private-query-detail", original));
        assertThat(result)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> {
                            assertThat(e.getStatusCode().value()).isEqualTo(403);
                            assertThat(e.getCause()).isNull();
                            assertThat(e.toString()).doesNotContain("private");
                        });
        var arbitrary = new IllegalStateException("arbitrary", original);
        assertThat(ManagedPaymentsController.queryFailure(arbitrary)).isSameAs(arbitrary);
    }

    @Test
    void unknownQueryFailureRetainsStandardInternalErrorInsteadOfRootCauseGuess() {
        var unknown =
                new QueryProcessingException(
                        "query failure", new IllegalStateException("unrecognized"));
        assertThat(ManagedPaymentsController.queryFailure(unknown)).isSameAs(unknown);
    }
}
