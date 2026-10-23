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

import org.fireflyframework.cqrs.command.Command;

public record RecoverManagedPaymentCommand(
        String tenant, java.util.UUID paymentId, Recovery input, String commandId)
        implements Command<View> {
    public RecoverManagedPaymentCommand(String tenant, java.util.UUID paymentId, Recovery input) {
        this(tenant, paymentId, input, java.util.UUID.randomUUID().toString());
    }

    @Override
    public String getCommandId() {
        return commandId;
    }

    @Override
    public Class<View> getResultType() {
        return View.class;
    }
}
