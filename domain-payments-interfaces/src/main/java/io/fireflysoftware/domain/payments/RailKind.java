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
 * Account-transfer rails {@code library-rails} actually exposes as {@code RailType}.
 * Card collection is a PSP instrument, so card-network, wallet, and crypto rail types are not listed here.
 * {@code internationalWire} selects the library's {@code WIRE_TRANSFER} operation, which is the international path.
 */
public enum RailKind {
    ACH(false),
    SWIFT(true),
    SEPA(false),
    CHIPS(false),
    FPS(false),
    FEDWIRE(false),
    INTERAC(false),
    RTP(false),
    TARGET2(false),
    BACS(false),
    PIX(false),
    UPI(false),
    ZELLE(false);

    private final boolean internationalWire;

    RailKind(boolean internationalWire) {
        this.internationalWire = internationalWire;
    }

    public boolean internationalWire() {
        return internationalWire;
    }
}
