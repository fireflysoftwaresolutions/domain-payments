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

import io.fireflysoftware.domain.payments.EntrySide;
import io.fireflysoftware.domain.payments.LedgerLeg;
import io.fireflysoftware.domain.payments.LedgerPosting;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.core.ports.LedgerPort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SandboxLedgerAdapter implements LedgerPort {

    private final ConcurrentHashMap<String, LedgerPosting> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<LedgerPosting> findByIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(byKey.get(idempotencyKey));
    }

    @Override
    public LedgerPosting post(String idempotencyKey, List<LedgerLeg> legs) {
        LedgerPosting existing = byKey.get(idempotencyKey);
        if (existing != null) {
            return existing;
        }
        validate(legs);
        String currency = legs.get(0).currency();
        LedgerPosting posting = new LedgerPosting(UUID.randomUUID(), idempotencyKey, currency, List.copyOf(legs));
        LedgerPosting raced = byKey.putIfAbsent(idempotencyKey, posting);
        return raced == null ? posting : raced;
    }

    @Override
    public LedgerPosting reverse(LedgerPosting original, String reversalKey) {
        LedgerPosting existing = byKey.get(reversalKey);
        if (existing != null) {
            return existing;
        }
        List<LedgerLeg> flipped = new ArrayList<>();
        for (LedgerLeg leg : original.legs()) {
            flipped.add(new LedgerLeg(leg.accountId(), leg.accountSpaceId(), leg.side().opposite(), leg.amount(), leg.currency()));
        }
        return post(reversalKey, flipped);
    }

    public List<LedgerPosting> postings() {
        return List.copyOf(byKey.values());
    }

    private static void validate(List<LedgerLeg> legs) {
        if (legs == null || legs.size() < 2) {
            throw new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "A ledger posting needs a debit and a credit.");
        }
        String currency = legs.get(0).currency();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LedgerLeg leg : legs) {
            if (leg.currency() == null || !leg.currency().equals(currency)) {
                throw new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "Both sides of a posting use the same currency.");
            }
            if (leg.side() == EntrySide.DEBIT) {
                debit = debit.add(leg.amount());
            } else {
                credit = credit.add(leg.amount());
            }
        }
        if (debit.compareTo(credit) != 0 || debit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentFlowException(PaymentFailure.UNBALANCED_POSTING, "Debits and credits must balance and be positive.");
        }
    }
}
