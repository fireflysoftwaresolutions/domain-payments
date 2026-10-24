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

import io.fireflysoftware.domain.payments.managed.ManagedPaymentContracts.View;

import org.fireflyframework.cqrs.annotations.QueryHandlerComponent;
import org.fireflyframework.cqrs.context.ExecutionContext;
import org.fireflyframework.cqrs.query.QueryHandler;

import reactor.core.publisher.Mono;

@QueryHandlerComponent(cacheable = false)
public final class GetManagedPaymentHandler extends QueryHandler<GetManagedPaymentQuery, View> {
    private final ManagedPaymentFlowService flow;

    public GetManagedPaymentHandler(ManagedPaymentFlowService flow) {
        this.flow = flow;
    }

    @Override
    protected Mono<View> doHandle(GetManagedPaymentQuery q) {
        return Mono.error(
                ManagedPaymentContext.fail(401, "NATIVE_PAYMENT_EXECUTION_CONTEXT_REQUIRED"));
    }

    @Override
    protected Mono<View> doHandle(GetManagedPaymentQuery q, ExecutionContext context) {
        return flow.read(q.tenant(), q.paymentId(), context);
    }
}
