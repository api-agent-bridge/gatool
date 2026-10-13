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

package io.gatool.boot.mcp.autoconfigure;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.mock.env.MockEnvironment;

import io.gatool.boot.mcp.internal.server.ElicitationSettings;

import static org.assertj.core.api.Assertions.assertThat;

class McpCallPoliciesTests {

	// The fallback comes from Spring AI's McpServerProperties, which holds 20 seconds in
	// Spring AI 2.0.1. ElicitationSettings holds the same 20 seconds as a plain constant
	// for a transport that cannot ask, so this test fails on the Spring AI release that
	// changes the default, where that constant and the README's "20 seconds by default"
	// need to follow.
	@Test
	void requestTimeout_unset_shouldBeSpringAisDefaultOfTwentySeconds() {
		Duration fallback = McpCallPolicies.requestTimeout(new MockEnvironment());

		assertThat(fallback).isEqualTo(new McpServerProperties().getRequestTimeout());
		assertThat(fallback).isEqualTo(ElicitationSettings.DEFAULT_REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(20));
	}

	@Test
	void requestTimeout_setInMinutes_shouldBindTheValue() {
		MockEnvironment environment = new MockEnvironment().withProperty("spring.ai.mcp.server.request-timeout", "5m");

		assertThat(McpCallPolicies.requestTimeout(environment)).isEqualTo(Duration.ofMinutes(5));
	}

}
