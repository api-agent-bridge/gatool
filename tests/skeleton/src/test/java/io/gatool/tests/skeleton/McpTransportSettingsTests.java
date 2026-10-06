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

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport settings a startup refuses: a session cap below one, an idle timeout at
 * zero, and a body cap at zero bytes.
 */
class McpTransportSettingsTests {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStreamableHttpWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_sessionCapBelowOne_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "gatool.mcp.sessions.max-count=0")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.mcp.sessions.max-count");
			});
	}

	@Test
	void startup_idleTimeoutAtZero_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "gatool.mcp.sessions.idle-timeout=0s")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.mcp.sessions.idle-timeout");
			});
	}

	@Test
	void startup_keepAliveAtOrAboveTheIdleTimeout_shouldStopNamingBothProperties() {
		// GATool's idle default is 30 minutes, so a keep-alive of one hour, valid on
		// plain Spring AI, would stop startup with the SDK's own assertion,
		// "sessionIdleTimeout must be greater than keepAliveInterval", which does not
		// name either property.
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE",
					"spring.ai.mcp.server.streamable-http.keep-alive-interval=1h")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("spring.ai.mcp.server.streamable-http.keep-alive-interval")
					.hasMessageContaining("gatool.mcp.sessions.idle-timeout");
			});
	}

	@Test
	void startup_keepAliveBelowTheIdleTimeout_shouldStart() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE",
					"spring.ai.mcp.server.streamable-http.keep-alive-interval=1m")
			.run((context) -> assertThat(context).hasNotFailed());
	}

	@Test
	void startup_bodyCapAtZeroBytes_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS",
					"gatool.mcp.transport.max-request-body-size=0B")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(InvalidConfigurationPropertyValueException.class)
					.hasMessageContaining("gatool.mcp.transport.max-request-body-size");
			});
	}

}
