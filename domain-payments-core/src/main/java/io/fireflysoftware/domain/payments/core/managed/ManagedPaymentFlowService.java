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

package io.fireflysoftware.domain.payments.core.managed;

import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;

import org.fireflyframework.cqrs.context.ExecutionContext;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ManagedPaymentFlowService {
    Mono<Acceptance> create(String tenant, Create input, ExecutionContext context);

    Mono<View> decide(String tenant, UUID payment, Decision input, ExecutionContext context);

    Mono<View> recover(String tenant, UUID payment, Recovery input, ExecutionContext context);

    Mono<View> read(String tenant, UUID payment, ExecutionContext context);

    Mono<HistoryPage> history(
            String tenant, UUID payment, long afterVersion, int limit, ExecutionContext context);
}
