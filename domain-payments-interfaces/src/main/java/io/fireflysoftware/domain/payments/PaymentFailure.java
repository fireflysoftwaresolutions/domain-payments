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

public enum PaymentFailure {
    VALIDATION,
    LIMIT_EXCEEDED,
    CURRENCY_NOT_IN_MASTER,
    CURRENCY_PAIR_NOT_CONFIGURED,
    SPACE_TYPE_NOT_CONFIGURED,
    ROUTE_NOT_CONFIGURED,
    UNBALANCED_POSTING,
    POSITION_FAILED,
    SYNC_REJECTED,
    RECORD_FAILED,
    COMPENSATED,
    NOT_FOUND
}
