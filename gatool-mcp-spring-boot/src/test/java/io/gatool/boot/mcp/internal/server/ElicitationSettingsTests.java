/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatool.boot.mcp.internal.server;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ElicitationSettingsTests {

	@Test
	void inWords_oneSecond_shouldBeSingular() {
		assertThat(ElicitationSettings.inWords(Duration.ofSeconds(1))).isEqualTo("1 second");
	}

	@Test
	void inWords_twentySeconds_shouldBePlural() {
		assertThat(ElicitationSettings.inWords(Duration.ofSeconds(20))).isEqualTo("20 seconds");
	}

	@Test
	void inWords_oneAndAHalfSeconds_shouldKeepTheWholeSeconds() {
		assertThat(ElicitationSettings.inWords(Duration.ofMillis(1_500))).isEqualTo("1 second");
	}

	@Test
	void inWords_underOneSecond_shouldNameTheMilliseconds() {
		assertThat(ElicitationSettings.inWords(Duration.ofMillis(500))).isEqualTo("500 milliseconds");
		assertThat(ElicitationSettings.inWords(Duration.ofMillis(1))).isEqualTo("1 millisecond");
	}

	@Test
	void inWords_fiveMinutes_shouldBeTheSeconds() {
		assertThat(ElicitationSettings.inWords(Duration.ofMinutes(5))).isEqualTo("300 seconds");
	}

	// The constant is plain so that this package loads without the Spring AI
	// auto-configure jar. McpCallPoliciesTests compares it with Spring AI's own default.
	@Test
	void defaultRequestTimeout_shouldBeTwentySeconds() {
		assertThat(ElicitationSettings.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(20));
		assertThat(ElicitationSettings.off().requestTimeout()).isEqualTo(Duration.ofSeconds(20));
	}

}
