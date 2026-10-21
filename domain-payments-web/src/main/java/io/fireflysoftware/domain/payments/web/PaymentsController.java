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

package io.fireflysoftware.domain.payments.web;

import io.fireflysoftware.domain.payments.MovementCommand;
import io.fireflysoftware.domain.payments.MovementResult;
import io.fireflysoftware.domain.payments.core.tcc.PaymentTccHub;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentsController {

    private final PaymentTccHub hub;

    public PaymentsController(PaymentTccHub hub) {
        this.hub = hub;
    }

    @PostMapping("/tries")
    @ResponseStatus(HttpStatus.CREATED)
    public MovementResult tryPayment(@RequestBody MovementCommand command) {
        return hub.tryPayment(command);
    }

    @PostMapping("/{paymentId}/confirmations")
    public MovementResult confirm(@PathVariable UUID paymentId, @RequestBody Confirmation confirmation) {
        return hub.confirm(paymentId, confirmation.response());
    }

    @PostMapping("/{paymentId}/cancellations")
    public MovementResult cancel(@PathVariable UUID paymentId) {
        return hub.cancel(paymentId);
    }

    @GetMapping("/{paymentId}")
    public MovementResult status(@PathVariable UUID paymentId) {
        return hub.status(paymentId);
    }

    public record Confirmation(String response) {
    }
}
