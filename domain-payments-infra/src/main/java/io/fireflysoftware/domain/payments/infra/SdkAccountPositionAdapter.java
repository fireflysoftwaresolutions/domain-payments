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

package io.fireflysoftware.domain.payments.infra;

import io.fireflysoftware.core.banking.accounts.sdk.api.AccountBalancesApi;
import io.fireflysoftware.core.banking.accounts.sdk.api.AccountSpacesApi;
import io.fireflysoftware.core.banking.accounts.sdk.invoker.ApiClient;
import io.fireflysoftware.core.banking.accounts.sdk.model.AccountBalanceDTO;
import io.fireflysoftware.domain.payments.PaymentFailure;
import io.fireflysoftware.domain.payments.PaymentFlowException;
import io.fireflysoftware.domain.payments.PositionKind;
import io.fireflysoftware.domain.payments.PositionMovement;
import io.fireflysoftware.domain.payments.core.ports.AccountPositionPort;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Updates the customer position through {@code core-banking-accounts-sdk}.
 * Space movement calls {@code AccountSpacesApi#transferBetweenSpaces}.
 * Selected when {@code firefly.payments.mode=live}. The sandbox adapter stays the default.
 */
public final class SdkAccountPositionAdapter implements AccountPositionPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final AccountBalancesApi balances;
    private final AccountSpacesApi spaces;
    private final java.util.concurrent.ConcurrentHashMap<String, PositionMovement> applied = new java.util.concurrent.ConcurrentHashMap<>();

    public SdkAccountPositionAdapter(String baseUrl) {
        ApiClient client = new ApiClient();
        client.setBasePath(strip(baseUrl));
        this(new AccountBalancesApi(client), new AccountSpacesApi(client));
    }

    SdkAccountPositionAdapter(AccountBalancesApi balances, AccountSpacesApi spaces) {
        this.balances = balances;
        this.spaces = spaces;
    }

    @Override
    public void apply(PositionMovement movement) {
        if (applied.putIfAbsent(movement.idempotencyKey(), movement) != null) {
            return;
        }
        if (movement.kind() == PositionKind.SPACE_TRANSFER) {
            transfer(movement.fromSpaceId(), movement.toSpaceId(), movement.amount(), movement.idempotencyKey());
            return;
        }
        if (movement.kind() == PositionKind.ACCOUNT_TRANSFER) {
            postBalance(movement.fromAccountId(), movement.amount().negate(), movement.idempotencyKey());
            postBalance(movement.toAccountId(), movement.amount(), movement.idempotencyKey());
            return;
        }
        if (movement.kind() == PositionKind.CUSTOMER_DEBIT) {
            postBalance(movement.fromAccountId(), movement.amount().negate(), movement.idempotencyKey());
            return;
        }
        postBalance(movement.toAccountId(), movement.amount(), movement.idempotencyKey());
    }

    @Override
    public void reverse(String originalKey, String reversalKey) {
        if (applied.containsKey(reversalKey)) {
            return;
        }
        PositionMovement original = applied.get(originalKey);
        if (original == null) {
            return;
        }
        BigDecimal opposite = original.amount().negate();
        if (original.kind() == PositionKind.ACCOUNT_TRANSFER) {
            postBalance(original.fromAccountId(), original.amount(), reversalKey);
            postBalance(original.toAccountId(), opposite, reversalKey);
        } else if (original.kind() == PositionKind.CUSTOMER_DEBIT) {
            postBalance(original.fromAccountId(), original.amount(), reversalKey);
        } else if (original.kind() == PositionKind.CUSTOMER_CREDIT) {
            postBalance(original.toAccountId(), opposite, reversalKey);
        } else {
            transfer(original.toSpaceId(), original.fromSpaceId(), original.amount(), reversalKey);
        }
        applied.put(reversalKey, original);
    }

    private void transfer(UUID fromSpaceId, UUID toSpaceId, BigDecimal amount, String key) {
        Boolean moved = call(spaces.transferBetweenSpaces(fromSpaceId, toSpaceId, amount, key));
        if (Boolean.FALSE.equals(moved)) {
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED, "Accounts refused the space transfer.");
        }
    }

    private void postBalance(UUID accountId, BigDecimal amount, String key) {
        AccountBalanceDTO body = new AccountBalanceDTO()
                .accountId(accountId)
                .balanceType(AccountBalanceDTO.BalanceTypeEnum.AVAILABLE)
                .balanceAmount(amount)
                .asOfDatetime(LocalDateTime.now().withNano(0));
        call(balances.createBalance(accountId, body, key));
    }

    private static <T> T call(Mono<T> call) {
        try {
            return call.block(TIMEOUT);
        } catch (WebClientResponseException ex) {
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED,
                    "Accounts returned HTTP " + ex.getStatusCode().value() + ".");
        } catch (PaymentFlowException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED, "The accounts call failed: " + ex.getMessage());
        }
    }

    private static String strip(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PaymentFlowException(PaymentFailure.POSITION_FAILED, "Accounts base URL is required.");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
