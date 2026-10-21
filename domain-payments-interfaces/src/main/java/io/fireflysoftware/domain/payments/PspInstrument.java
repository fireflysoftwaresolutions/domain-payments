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
 * Instruments {@code library-psps} supports. Names other than {@code PAYOUT} match {@code PaymentMethodType}.
 * {@code PAYOUT} is {@code PayoutPort}, the only outbound PSP operation the library exposes.
 */
public enum PspInstrument {
    CARD,
    BANK_TRANSFER,
    SEPA_DEBIT,
    ACH_DEBIT,
    IDEAL,
    BANCONTACT,
    GIROPAY,
    SOFORT,
    EPS,
    PRZELEWY24,
    ALIPAY,
    WECHAT_PAY,
    GOOGLE_PAY,
    APPLE_PAY,
    PAYPAL,
    KLARNA,
    AFTERPAY,
    AFFIRM,
    WALLET,
    CRYPTO,
    OTHER,
    PAYOUT
}
