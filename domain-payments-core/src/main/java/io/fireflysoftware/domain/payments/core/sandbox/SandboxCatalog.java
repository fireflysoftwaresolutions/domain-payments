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

import io.fireflysoftware.domain.configuration.parameters.BusinessParameter;
import io.fireflysoftware.domain.configuration.parameters.BusinessParameterPort;
import io.fireflysoftware.domain.configuration.parameters.SandboxBusinessParameterAdapter;
import io.fireflysoftware.domain.configuration.parameters.TransferRoute;
import io.fireflysoftware.domain.payments.MasterData;
import io.fireflysoftware.domain.payments.MovementRoute;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentScheme;
import io.fireflysoftware.domain.payments.PaymentsCatalog;
import io.fireflysoftware.domain.payments.PaymentsCatalog.ClearingAccounts;
import io.fireflysoftware.domain.payments.PaymentsCatalog.CurrencyPair;
import io.fireflysoftware.domain.payments.PaymentsCatalog.SchemeRouting;
import io.fireflysoftware.domain.payments.PspInstrument;
import io.fireflysoftware.domain.payments.RailKind;
import io.fireflysoftware.domain.referencedata.catalog.CatalogCode;
import io.fireflysoftware.domain.referencedata.catalog.CatalogEntry;
import io.fireflysoftware.domain.referencedata.catalog.ExecutionChannel;
import io.fireflysoftware.domain.referencedata.catalog.ReferenceCatalogPort;
import io.fireflysoftware.domain.referencedata.catalog.SandboxReferenceCatalogAdapter;
import io.fireflysoftware.domain.referencedata.catalog.TransferTypeCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the sandbox payments catalog by calling {@link BusinessParameterPort} and {@link ReferenceCatalogPort}.
 * Account identifiers and transfer routes are not copied here.
 */
public final class SandboxCatalog {

    private SandboxCatalog() {
    }

    public static PaymentsCatalog standard() {
        return from(new SandboxBusinessParameterAdapter(), new SandboxReferenceCatalogAdapter());
    }

    public static MasterData masters() {
        return masters(new SandboxReferenceCatalogAdapter());
    }

    public static ClearingAccounts clearing() {
        return standard().clearingAccounts();
    }

    public static PaymentsCatalog from(BusinessParameterPort parameters, ReferenceCatalogPort catalogs) {
        UUID outbound = account(parameters, "payments.clearing.outbound.account-id");
        UUID inbound = account(parameters, "payments.clearing.inbound.account-id");
        UUID nostro = account(parameters, "payments.nostro.account-id");
        List<CatalogEntry> transferTypes = catalogs.list(CatalogCode.TRANSFER_TYPE).collectList().block();
        List<String> currencies = catalogs.list(CatalogCode.CURRENCY).map(CatalogEntry::code).collectList().block();
        List<String> spaceTypes = catalogs.list(CatalogCode.ACCOUNT_SPACE_TYPE).map(CatalogEntry::code).collectList().block();
        boolean domesticSepa = catalogs.find(CatalogCode.RAIL_SCHEME, "SEPA_CREDIT_TRANSFER").blockOptional().isPresent();
        List<SchemeRouting> routes = new ArrayList<>();
        if (transferTypes != null) {
            for (CatalogEntry entry : transferTypes) {
                routes.addAll(routesFor(parameters, entry, domesticSepa));
            }
        }
        routes.add(routing(PaymentScheme.PSP_PAYOUT,
                movement(parameters, "payments.transfer-route.OUTBOUND_PSP"),
                null, PspInstrument.PAYOUT, false));
        routes.add(routing(PaymentScheme.PSP_INBOUND,
                movement(parameters, "payments.transfer-route.INBOUND_PSP"),
                null, PspInstrument.CARD, false));
        List<CurrencyPair> pairs = new ArrayList<>();
        if (currencies != null) {
            for (String currency : currencies) {
                pairs.add(new CurrencyPair(currency, currency));
            }
        }
        return new PaymentsCatalog(List.copyOf(routes), List.copyOf(pairs),
                spaceTypes == null ? List.of() : List.copyOf(spaceTypes),
                new ClearingAccounts(outbound, inbound, null, nostro));
    }

    public static MasterData masters(ReferenceCatalogPort catalogs) {
        List<String> currencies = catalogs.list(CatalogCode.CURRENCY).map(CatalogEntry::code).collectList().block();
        return new MasterData(currencies == null ? List.of() : List.copyOf(currencies));
    }

    private static List<SchemeRouting> routesFor(BusinessParameterPort parameters, CatalogEntry entry, boolean domesticSepa) {
        ExecutionChannel channel = ExecutionChannel.valueOf(entry.attributes().get("executionChannel"));
        TransferTypeCode type = TransferTypeCode.valueOf(entry.code());
        RailKind rail = railFor(type, channel, domesticSepa);
        PspInstrument instrument = channel == ExecutionChannel.LIBRARY_PSPS ? PspInstrument.CARD : null;
        return switch (type) {
            case INTERNAL -> List.of(
                    routing(PaymentScheme.INTERNAL_TRANSFER,
                            movement(parameters, "payments.transfer-type.INTERNAL.route"), null, null, false),
                    routing(PaymentScheme.SPACE_TRANSFER,
                            movement(parameters, "payments.transfer-type.INTERNAL.route"), null, null,
                            Boolean.parseBoolean(entry.attributes().get("accountSpaceMovement"))));
            case DOMESTIC -> List.of(
                    routing(PaymentScheme.DOMESTIC_OUTBOUND,
                            movement(parameters, "payments.transfer-type.DOMESTIC.outbound.route"), rail, instrument, false),
                    routing(PaymentScheme.RAIL_INBOUND,
                            movement(parameters, "payments.transfer-type.DOMESTIC.inbound.route"), rail, instrument, false));
            case INTERNATIONAL -> {
                MovementRoute outbound = movement(parameters, "payments.transfer-type.INTERNATIONAL.outbound.route");
                MovementRoute inbound = movement(parameters, "payments.transfer-type.INTERNATIONAL.inbound.route");
                if (channel == ExecutionChannel.LIBRARY_RAILS && (outbound == MovementRoute.OUTBOUND_PSP || inbound == MovementRoute.INBOUND_PSP)) {
                    throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                            "An international credit transfer uses library-rails, not a PSP route.");
                }
                yield List.of(routing(PaymentScheme.INTERNATIONAL_OUTBOUND, outbound, rail, instrument, false));
            }
        };
    }

    private static RailKind railFor(TransferTypeCode type, ExecutionChannel channel, boolean domesticSepa) {
        if (channel != ExecutionChannel.LIBRARY_RAILS) {
            return null;
        }
        if (type == TransferTypeCode.INTERNATIONAL) {
            return RailKind.SWIFT;
        }
        return domesticSepa ? RailKind.SEPA : null;
    }

    private static SchemeRouting routing(PaymentScheme scheme, MovementRoute route, RailKind rail,
                                         PspInstrument instrument, boolean spaceMovement) {
        return new SchemeRouting(scheme, route, rail, instrument, spaceMovement, null);
    }

    private static MovementRoute movement(BusinessParameterPort parameters, String key) {
        return switch (TransferRoute.valueOf(required(parameters, key))) {
            case INTERNAL -> MovementRoute.INTERNAL_BOOK;
            case OUTBOUND_RAIL -> MovementRoute.OUTBOUND_RAIL;
            case OUTBOUND_PSP -> MovementRoute.OUTBOUND_PSP;
            case INBOUND_RAIL -> MovementRoute.INBOUND_RAIL;
            case INBOUND_PSP -> MovementRoute.INBOUND_PSP;
        };
    }

    private static UUID account(BusinessParameterPort parameters, String key) {
        return UUID.fromString(required(parameters, key));
    }

    private static String required(BusinessParameterPort parameters, String key) {
        BusinessParameter parameter = parameters.findByKey(key).block();
        if (parameter == null || parameter.value() == null || parameter.value().isBlank()) {
            throw new PaymentFlowException(PaymentFailure.ROUTE_NOT_CONFIGURED,
                    "Business parameter " + key + " is not available.");
        }
        return parameter.value();
    }
}
