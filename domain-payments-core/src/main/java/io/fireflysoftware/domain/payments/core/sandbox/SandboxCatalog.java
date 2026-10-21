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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic sandbox catalog. These values live here, not in the saga.
 * Account-space type names match core-banking-accounts {@code AccountSpaceTypeEnum}.
 */
public final class SandboxCatalog {

    public static final UUID OUTBOUND_CLEARING = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    public static final UUID INBOUND_CLEARING = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
    public static final UUID SUSPENSE = UUID.fromString("00000000-0000-4000-8000-0000000000c3");
    public static final UUID NOSTRO = UUID.fromString("00000000-0000-4000-8000-0000000000c4");

    private static final BigDecimal LIMIT = new BigDecimal("1000000.00");

    private SandboxCatalog() {
    }

    public static PaymentsCatalog standard() {
        return new PaymentsCatalog(routes(), pairs(), spaceTypes(), clearing());
    }

    public static MasterData masters() {
        return new MasterData(List.of("EUR", "USD", "GBP"));
    }

    public static ClearingAccounts clearing() {
        return new ClearingAccounts(OUTBOUND_CLEARING, INBOUND_CLEARING, SUSPENSE, NOSTRO);
    }

    public static List<String> spaceTypes() {
        return List.of("MAIN", "SAVINGS", "VACATION", "EMERGENCY", "GOALS", "CUSTOM");
    }

    private static List<CurrencyPair> pairs() {
        return List.of(
                new CurrencyPair("EUR", "EUR"),
                new CurrencyPair("USD", "USD"),
                new CurrencyPair("GBP", "GBP"),
                new CurrencyPair("EUR", "USD"));
    }

    private static List<SchemeRouting> routes() {
        return List.of(
                route(PaymentScheme.INTERNAL_TRANSFER, MovementRoute.INTERNAL_BOOK, null, null, false),
                route(PaymentScheme.SPACE_TRANSFER, MovementRoute.INTERNAL_BOOK, null, null, true),
                route(PaymentScheme.DOMESTIC_OUTBOUND, MovementRoute.OUTBOUND_RAIL, RailKind.SEPA, null, false),
                route(PaymentScheme.INTERNATIONAL_OUTBOUND, MovementRoute.OUTBOUND_RAIL, RailKind.SWIFT, null, false),
                route(PaymentScheme.PSP_PAYOUT, MovementRoute.OUTBOUND_PSP, null, PspInstrument.PAYOUT, false),
                route(PaymentScheme.RAIL_INBOUND, MovementRoute.INBOUND_RAIL, RailKind.SEPA, null, false),
                route(PaymentScheme.PSP_INBOUND, MovementRoute.INBOUND_PSP, null, PspInstrument.CARD, false));
    }

    private static SchemeRouting route(PaymentScheme scheme, MovementRoute movement, RailKind rail,
                                       PspInstrument instrument, boolean spaceMovement) {
        return new SchemeRouting(scheme, movement, rail, instrument, spaceMovement, LIMIT);
    }
}
