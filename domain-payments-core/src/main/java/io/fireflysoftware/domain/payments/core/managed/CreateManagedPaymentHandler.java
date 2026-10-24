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

import org.fireflyframework.cqrs.annotations.CommandHandlerComponent;
import org.fireflyframework.cqrs.command.CommandHandler;
import org.fireflyframework.cqrs.context.ExecutionContext;

import reactor.core.publisher.Mono;

@CommandHandlerComponent(retries = 0)
public final class CreateManagedPaymentHandler
        extends CommandHandler<CreateManagedPaymentCommand, Acceptance> {
    private final ManagedPaymentFlowService flow;

    public CreateManagedPaymentHandler(ManagedPaymentFlowService flow) {
        this.flow = flow;
    }

    @Override
    protected Mono<Acceptance> doHandle(CreateManagedPaymentCommand c) {
        return Mono.error(
                ManagedPaymentContext.fail(401, "NATIVE_PAYMENT_EXECUTION_CONTEXT_REQUIRED"));
    }

    @Override
    protected Mono<Acceptance> doHandle(CreateManagedPaymentCommand c, ExecutionContext context) {
        return flow.create(c.tenant(), c.input(), context);
    }
}
