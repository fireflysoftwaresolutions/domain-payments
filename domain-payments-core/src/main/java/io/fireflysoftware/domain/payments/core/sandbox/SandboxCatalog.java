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

package io.fireflysoftware.domain.payments.core.sandbox;

import io.fireflysoftware.domain.payments.MasterData;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.ClearingAccounts;
import io.fireflysoftware.domain.payments.PaymentsCatalog.CurrencyPair;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;
import io.fireflysoftware.domain.payments.PspInstrument;
import io.fireflysoftware.domain.payments.RailKind;

import java.util.List;
import java.util.UUID;

/**
 * Sandbox payments catalog. It does not call another domain and it does not open a socket.
 * Live configuration and masters are read from core-common-config-mgmt and
 * core-common-reference-master-data through their SDKs.
 */
public final class SandboxCatalog {

    private static final UUID OUTBOUND = UUID.fromString("44444444-4444-4444-8444-444444444441");
    private static final UUID INBOUND = UUID.fromString("44444444-4444-4444-8444-444444444442");
    private static final UUID NOSTRO = UUID.fromString("44444444-4444-4444-8444-444444444443");

    private SandboxCatalog() {
    }

    public static PaymentsCatalog standard() {
        List<SchemeRouting> routes = List.of(
                routing(PaymentScheme.INTERNAL_TRANSFER, MovementRoute.INTERNAL_BOOK, null, null, false),
                routing(PaymentScheme.SPACE_TRANSFER, MovementRoute.INTERNAL_BOOK, null, null, true),
                routing(PaymentScheme.DOMESTIC_OUTBOUND, MovementRoute.OUTBOUND_RAIL, RailKind.SEPA, null, false),
                routing(PaymentScheme.RAIL_INBOUND, MovementRoute.INBOUND_RAIL, RailKind.SEPA, null, false),
                routing(PaymentScheme.INTERNATIONAL_OUTBOUND, MovementRoute.OUTBOUND_RAIL, RailKind.SWIFT, null, false),
                routing(PaymentScheme.PSP_PAYOUT, MovementRoute.OUTBOUND_PSP, null, PspInstrument.PAYOUT, false),
                routing(PaymentScheme.PSP_INBOUND, MovementRoute.INBOUND_PSP, null, PspInstrument.CARD, false));
        List<CurrencyPair> pairs = List.of("EUR", "USD", "GBP", "CHF", "JPY").stream()
                .map(currency -> new CurrencyPair(currency, currency))
                .toList();
        List<String> spaces = List.of("MAIN", "SAVINGS", "VACATION", "EMERGENCY", "GOALS", "CUSTOM");
        return new PaymentsCatalog(routes, pairs, spaces, new ClearingAccounts(OUTBOUND, INBOUND, null, NOSTRO));
    }

    public static MasterData masters() {
        return new MasterData(List.of("EUR", "USD", "GBP", "CHF", "JPY"));
    }

    public static ClearingAccounts clearing() {
        return standard().clearingAccounts();
    }

    private static SchemeRouting routing(PaymentScheme scheme, MovementRoute route, RailKind rail,
                                         PspInstrument instrument, boolean spaceMovement) {
        return new SchemeRouting(scheme, route, rail, instrument, spaceMovement, null);
    }
}
