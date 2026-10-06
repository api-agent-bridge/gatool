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

package io.gatool.tests.skeleton;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.limit.ToolRateLimiter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings that change the rate limiter at startup, and what the bound on tracked
 * pairs says while it is reached.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpRateLimitSettingsTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_callsPerMinuteBelowOne_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("gatool.mcp.rate-limit.calls-per-minute=0").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.rate-limit.calls-per-minute");
		});
	}

	@Test
	void startup_unlimitedToolCalls_shouldStartAndWarnNamingTheSwitch(CapturedOutput output) {
		this.contextRunner.withPropertyValues("gatool.mcp.security.unsafe.allow-unlimited-tool-calls=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("allow-unlimited-tool-calls");
	}

	@Test
	void startup_maxTrackedPairsBelowOne_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("gatool.mcp.rate-limit.max-tracked-pairs=0").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.rate-limit.max-tracked-pairs");
		});
	}

	@Test
	void decide_moreLiveCallersThanMaxTrackedPairs_shouldCountEachEvictionAndWarnOnceNamingTheProperty(
			CapturedOutput output) {
		this.contextRunner.withBean(SimpleMeterRegistry.class)
			.withPropertyValues("gatool.mcp.rate-limit.max-tracked-pairs=2", "gatool.mcp.rate-limit.calls-per-minute=5")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				ToolRateLimiter limiter = context.getBean(ToolRateLimiter.class);
				// Fifty callers each spend their allowance, so every bucket the bound of
				// two drops belongs to a caller inside its window, who reads a fresh
				// allowance on the next call.
				for (int caller = 0; caller < 50; caller++) {
					for (int call = 0; call < 5; call++) {
						limiter.decide("10.0.0." + caller, "topRatedMovies");
					}
				}
				assertThat(
						context.getBean(SimpleMeterRegistry.class).get("gatool.rate-limit.evictions").counter().count())
					.isGreaterThanOrEqualTo(40);
			});

		// One warning a minute, whatever the number of evictions, so a limiter over its
		// bound does not fill the log.
		long warnings = output.getAll()
			.lines()
			.filter((line) -> line.contains("WARN") && line.contains("gatool.mcp.rate-limit.max-tracked-pairs"))
			.count();
		assertThat(warnings).isEqualTo(1);
		assertThat(output.getAll()).contains("gatool.rate-limit.evictions");
	}

}
