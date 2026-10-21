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
 * Payment product the customer asked for. Which port runs it comes from configuration, not from this name.
 */
public enum PaymentScheme {
    INTERNAL_TRANSFER,
    SPACE_TRANSFER,
    DOMESTIC_OUTBOUND,
    INTERNATIONAL_OUTBOUND,
    PSP_PAYOUT,
    RAIL_INBOUND,
    PSP_INBOUND
}
