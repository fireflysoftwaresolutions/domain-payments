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

/**
 * How a configured scheme is executed. The catalog maps each {@link PaymentScheme} onto one route.
 */
public enum MovementRoute {
    INTERNAL_BOOK,
    OUTBOUND_RAIL,
    OUTBOUND_PSP,
    INBOUND_RAIL,
    INBOUND_PSP;

    public boolean inbound() {
        return this == INBOUND_RAIL || this == INBOUND_PSP;
    }

    public boolean outbound() {
        return this == OUTBOUND_RAIL || this == OUTBOUND_PSP;
    }
}
