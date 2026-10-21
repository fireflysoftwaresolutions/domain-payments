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

package io.fireflysoftware.domain.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Routing, limits, currency pairs, account-space types, and clearing accounts.
 * Loaded from {@code PaymentConfigurationPort}. The saga does not own these values.
 */
public record PaymentsCatalog(
        List<SchemeRouting> routes,
        List<CurrencyPair> currencyPairs,
        List<String> spaceTypes,
        ClearingAccounts clearingAccounts
) {

    public record SchemeRouting(
            PaymentScheme scheme,
            MovementRoute route,
            RailKind railKind,
            PspInstrument pspInstrument,
            boolean spaceMovement,
            BigDecimal maxAmount
    ) {
    }

    public record CurrencyPair(String base, String quote) {
    }

    public record ClearingAccounts(
            UUID outboundClearingAccountId,
            UUID inboundClearingAccountId,
            UUID suspenseAccountId,
            UUID nostroAccountId
    ) {
    }
}
