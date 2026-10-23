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

import io.fireflysoftware.domain.payments.managed.v1.ManagedPaymentContracts.*;

import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import org.fireflyframework.cqrs.context.ExecutionContext;

import reactor.core.publisher.Mono;

@CommandHandlerComponent(retries = 0)
public final class DecideManagedPaymentHandler
        extends CommandHandler<DecideManagedPaymentCommand, View> {
    private final ManagedPaymentFlow flow;

    public DecideManagedPaymentHandler(ManagedPaymentFlow flow) {
        this.flow = flow;
    }

    @Override
    protected Mono<View> doHandle(DecideManagedPaymentCommand c) {
        return Mono.error(
                ManagedPaymentContext.fail(401, "NATIVE_PAYMENT_EXECUTION_CONTEXT_REQUIRED"));
    }

    @Override
    protected Mono<View> doHandle(DecideManagedPaymentCommand c, ExecutionContext context) {
        return flow.decide(c.tenant(), c.paymentId(), c.input(), context);
    }
}
