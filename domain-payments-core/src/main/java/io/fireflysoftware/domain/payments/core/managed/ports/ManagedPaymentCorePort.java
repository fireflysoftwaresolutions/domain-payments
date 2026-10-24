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

package io.fireflysoftware.domain.payments.core.managed.ports;

import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ManagedPaymentCorePort {
    Mono<Acceptance> create(String tenant, Create input);

    Mono<View> read(String tenant, UUID payment);

    Mono<EffectClaim> claim(String tenant, UUID payment, Claim input);

    Mono<View> acknowledge(String tenant, UUID payment, Acknowledge input);

    Mono<View> failure(String tenant, UUID payment, Failure input);

    Mono<View> decide(String tenant, UUID payment, Decision input);

    Mono<View> recover(String tenant, UUID payment, Recovery input);

    Mono<RecoveryPage> queue(String tenant, UUID after, int limit);

    Flux<HistoryEvent> history(String tenant, UUID payment, long after, int limit);
}
