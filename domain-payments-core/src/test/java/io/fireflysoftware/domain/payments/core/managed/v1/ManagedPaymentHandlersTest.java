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

package io.fireflysoftware.domain.payments.core.managed.v1;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fireflysoftware.domain.payments.managed.v1.ManagedPaymentContracts.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

class ManagedPaymentHandlersTest {
    @Test
    void handlersRequireExplicitNativeContextBeforeFlow() {
        var flow = mock(ManagedPaymentFlow.class);
        var create = new CreateManagedPaymentHandler(flow);
        var decide = new DecideManagedPaymentHandler(flow);
        var recover = new RecoverManagedPaymentHandler(flow);
        var read = new GetManagedPaymentHandler(flow);
        var history = new GetManagedPaymentHistoryHandler(flow);
        var id = UUID.randomUUID();
        for (Mono<?> operation :
                java.util.List.of(
                        create.handle(new CreateManagedPaymentCommand("owned", null)),
                        decide.handle(new DecideManagedPaymentCommand("owned", id, null)),
                        recover.handle(new RecoverManagedPaymentCommand("owned", id, null)),
                        read.handle(new GetManagedPaymentQuery("owned", id)),
                        history.handle(new GetManagedPaymentHistoryQuery("owned", id, -1, 25)))) {
            assertThatThrownBy(() -> operation.block(Duration.ofSeconds(2)))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        }
        verifyNoInteractions(flow);
    }

    @Test
    void commandsAndQueriesHaveStableNonsecretNativeIdentityAndNoReadCache() {
        var id = UUID.randomUUID();
        var create = new CreateManagedPaymentCommand("owned", null);
        var query = new GetManagedPaymentQuery("owned", id);
        assertThat(create.getCommandId()).isEqualTo(create.getCommandId());
        assertThat(query.getQueryId()).isEqualTo(query.getQueryId());
        assertThat(query.isCacheable()).isFalse();
        assertThat(query.getCacheKey()).isNull();
        var history = new GetManagedPaymentHistoryQuery("owned", id, -1, 25);
        assertThat(history.getQueryId()).isEqualTo(history.getQueryId());
        assertThat(history.isCacheable()).isFalse();
        assertThat(history.getCacheKey()).isNull();
        assertThat(
                        GetManagedPaymentHistoryHandler.class
                                .getAnnotation(
                                        org.fireflyframework.cqrs.annotations.QueryHandlerComponent
                                                .class)
                                .cacheable())
                .isFalse();
    }
}
