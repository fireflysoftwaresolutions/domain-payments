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

import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PaymentStatus;
import io.fireflysoftware.domain.payments.StoredPayment;
import io.fireflysoftware.domain.payments.core.ports.PaymentRecordPort;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SandboxPaymentRecordAdapter implements PaymentRecordPort {

    private final ConcurrentHashMap<UUID, StoredPayment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byReference = new ConcurrentHashMap<>();
    private final AtomicInteger failCompletes = new AtomicInteger();

    public void failNextCompletion() {
        failCompletes.incrementAndGet();
    }

    @Override
    public Optional<StoredPayment> findById(UUID paymentId) {
        return Optional.ofNullable(byId.get(paymentId));
    }

    @Override
    public Optional<StoredPayment> findByIdempotencyKey(String idempotencyKey) {
        UUID id = byKey.get(idempotencyKey);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public Optional<StoredPayment> findByThirdPartyReference(String thirdPartyReference) {
        if (thirdPartyReference == null) {
            return Optional.empty();
        }
        UUID id = byReference.get(thirdPartyReference);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public StoredPayment save(StoredPayment payment) {
        if (payment.status() == PaymentStatus.COMPLETED && failCompletes.get() > 0) {
            failCompletes.decrementAndGet();
            throw new PaymentFlowException(PaymentFailure.RECORD_FAILED, "The payments core rejected the completion write.");
        }
        byId.put(payment.paymentId(), payment);
        byKey.put(payment.idempotencyKey(), payment.paymentId());
        if (payment.thirdPartyReference() != null && !payment.thirdPartyReference().isBlank()) {
            byReference.put(payment.thirdPartyReference(), payment.paymentId());
        }
        return payment;
    }
}
