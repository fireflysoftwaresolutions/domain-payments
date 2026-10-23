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

import io.fireflysoftware.domain.payments.managed.v1.ManagedPaymentContracts.View;

import org.fireflyframework.cqrs.query.Query;

import java.util.UUID;

public record GetManagedPaymentQuery(String tenant, UUID paymentId, String queryId)
        implements Query<View> {
    public GetManagedPaymentQuery(String tenant, UUID paymentId) {
        this(tenant, paymentId, UUID.randomUUID().toString());
    }

    @Override
    public String getQueryId() {
        return queryId;
    }

    @Override
    public Class<View> getResultType() {
        return View.class;
    }

    @Override
    public boolean isCacheable() {
        return false;
    }
}
