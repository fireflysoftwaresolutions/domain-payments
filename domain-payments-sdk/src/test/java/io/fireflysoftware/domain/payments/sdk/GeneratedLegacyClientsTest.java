/*
 * Copyright 2026 Firefly Software Foundation
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

package io.fireflysoftware.domain.payments.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GeneratedLegacyClientsTest {
    @ParameterizedTest
    @ValueSource(strings = {"PaymentsApi", "PaymentRecordsApi"})
    void legacyClientsComeEntirelyFromTheOpenApiGenerator(String name) throws Exception {
        String relative = "io/fireflysoftware/domain/payments/sdk/api/" + name + ".java";
        assertThat(Path.of("src/main/java").resolve(relative)).doesNotExist();
        assertThat(Path.of("target/generated-sources/src/gen/java/main").resolve(relative))
                .isRegularFile();
        Class<?> client = Class.forName("io.fireflysoftware.domain.payments.sdk.api." + name);
        assertThat(client.getSuperclass()).isEqualTo(Object.class);
        assertThat(client.isAnnotationPresent(Deprecated.class)).isFalse();
    }
}
