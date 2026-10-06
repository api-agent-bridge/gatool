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

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A server setting GATool cannot serve stops startup.
 *
 * <p>
 * GATool publishes tool specifications for the stateless server and for the stateful one
 * that stdio runs on, both synchronous. Spring AI also serves SSE, and it can build an
 * asynchronous server, and in either case GATool's beans go unread: the endpoint would
 * answer every request and {@code tools/list} would come back empty.
 */
class UnsupportedServerSettingsTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String API_URL = "gatool.api.url=http://localhost:1/graphql";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues(API_URL, SCHEMA, "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_sseProtocol_shouldStopAndNameTheProperty() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.server.protocol=SSE").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("spring.ai.mcp.server.protocol")
				.hasMessageContaining("STATELESS")
				.hasMessageContaining("STREAMABLE");
		});
	}

	@Test
	void startup_asyncServer_shouldStopAndSayWhichValueServesTools() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("spring.ai.mcp.server.type")
					.hasMessageContaining("zero GATool tools")
					.hasMessageContaining("SYNC");
			});
	}

	@Test
	void startup_toolCapabilityOffWhileOperationFilesMakeMcpTools_shouldStopAndNameTheProperty() {
		// Spring AI hands the tool specifications to its server only while the tool
		// capability is on. With it off the server would start without any GATool tool
		// and answer tools/list with "Missing handler", while startup lists the tools as
		// served. The mapper's auto-configuration is added so the tool list bean is
		// built.
		this.contextRunner.withConfiguration(AutoConfigurations.of(McpServerJsonMapperAutoConfiguration.class))
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS",
					"spring.ai.mcp.server.capabilities.tool=false")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("spring.ai.mcp.server.capabilities.tool")
					.hasMessageContaining("topRatedMovies")
					.hasMessageContaining("Set this property to true");
			});
	}

	@ParameterizedTest
	@CsvSource({ "spring.ai.mcp.server.protocol, 'STATELESS '", "spring.ai.mcp.server.type, 'SYNC '",
			"spring.ai.mcp.server.stdio, 'true '", "spring.ai.mcp.server.stdio, ' false'" })
	void startup_serverSettingWithWhitespaceAroundItsValue_shouldStopAndNameTheProperty(String property, String value) {
		// Spring's conditions compare the value as written, so "SYNC " would pass a check
		// that trims it and then fail to match any condition: the application would start
		// without a server and /mcp would answer 500. A .properties file keeps a trailing
		// space, which the map source stands in for here, because the runner's own
		// property values are trimmed.
		this.contextRunner
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new MapPropertySource("as-written", Map.of(property, value))))
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining(property)
					.hasMessageContaining("whitespace");
			});
	}

	@Test
	void startup_statelessAndSync_shouldPassBothGuards() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=SYNC")
			.run((context) -> {
				// The values GATool serves reach the tool beans, which then ask for
				// Spring
				// AI's own beans that this slice leaves out. A whole application on the
				// same two values starts and answers, which McpToolEndToEndTests covers.
				assertThat(context.getStartupFailure()).isNotNull().hasMessageContaining("mcpServerJsonMapper");
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageNotContaining("spring.ai.mcp.server.protocol")
					.hasMessageNotContaining("spring.ai.mcp.server.type");
			});
	}

}
