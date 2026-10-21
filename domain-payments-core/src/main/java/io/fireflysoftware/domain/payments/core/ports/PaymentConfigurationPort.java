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

package io.fireflysoftware.domain.payments.core.ports;

import io.fireflysoftware.domain.payments.PaymentsCatalog;

/**
 * Scheme routing, limits, currency pairs, account-space types, and clearing accounts.
 * The configuration domain has not published a port yet, so this is the hub's port.
 * The live adapter reads core-common-config-mgmt channel parameters.
 */
public interface PaymentConfigurationPort {

    PaymentsCatalog catalog();
}
