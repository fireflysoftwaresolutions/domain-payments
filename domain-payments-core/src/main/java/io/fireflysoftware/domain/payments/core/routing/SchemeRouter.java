/*
 * Copyright 2025 Firefly Software Foundation
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

package io.fireflysoftware.domain.payments.core.routing;

import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;

/**
 * Resolves a scheme through the configured catalog. There is no scheme ladder here.
 */
public final class SchemeRouter {

    public SchemeRouting routing(PaymentScheme scheme, PaymentsCatalog catalog) {
        if (scheme == null || catalog == null || catalog.routes() == null) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED, "Payment routing is not configured.");
        }
        return catalog.routes().stream()
                .filter(route -> route.scheme() == scheme)
                .findFirst()
                .orElseThrow(() -> new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                        "No configured route for scheme " + scheme + "."));
    }
}
