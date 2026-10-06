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

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool scopes over stdio, where the caller is the process that started the server: the
 * environment names the scopes it holds, startup stops without them, and a tool needing
 * more answers with a tool error.
 */
class McpStdioScopesTests {

	private static final ResolvableType STATEFUL_TOOL_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpServerFeatures.SyncToolSpecification.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/")
		.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl());

	@Test
	void startup_scopedToolsWithoutTheStdioScopes_shouldStopNamingThePropertyAndTheTools() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.mcp.stdio.granted-scopes")
				.hasMessageContaining("topRatedMovies (movies:read)")
				.hasMessageContaining("movieByLookup (movies:read movies:detail)");
		});
	}

	@Test
	void callTool_needingAScopeOutsideTheStdioScopes_shouldAnswerWithAToolErrorNamingIt() {
		this.contextRunner.withPropertyValues("gatool.mcp.stdio.granted-scopes=movies:read").run((context) -> {
			McpSchema.CallToolResult result = call(context, "movieByLookup", Map.of("by", Map.of("id", "movie-1")));

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("movieByLookup needs the scopes movies:detail")
				.contains("this server was started with movies:read (gatool.mcp.stdio.granted-scopes)");
		});
	}

	@Test
	void callTool_needingAScopeUnderAnEmptyStdioList_shouldSayThatTheServerWasStartedWithoutScopes() {
		this.contextRunner.withPropertyValues("gatool.mcp.stdio.granted-scopes=").run((context) -> {
			McpSchema.CallToolResult result = call(context, "movieByLookup", Map.of("by", Map.of("id", "movie-1")));

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("movieByLookup needs the scopes movies:read movies:detail")
				.contains("this server was started without scopes (gatool.mcp.stdio.granted-scopes)");
		});
	}

	@Test
	void callTool_withinTheStdioScopes_shouldReachTheApi() {
		this.contextRunner.withPropertyValues("gatool.mcp.stdio.granted-scopes=movies:read,movies:detail")
			.run((context) -> {
				McpSchema.CallToolResult result = call(context, "topRatedMovies", Map.of("first", 1));

				assertThat(result.isError()).as(firstText(result)).isFalse();
				assertThat(firstText(result)).contains("Signal from Kepler");
			});
	}

	@Test
	void callTool_openToEveryCallerUnderAnEmptyStdioList_shouldReachTheApi() {
		// An empty list is a list, so startup passes, and a tool whose own list is empty
		// runs on it.
		this.contextRunner.withPropertyValues("gatool.mcp.stdio.granted-scopes=").run((context) -> {
			McpSchema.CallToolResult result = call(context, "searchMovies", Map.of("text", "a"));

			assertThat(result.isError()).as(firstText(result)).isFalse();
		});
	}

	@SuppressWarnings("unchecked")
	private static McpSchema.CallToolResult call(AssertableApplicationContext context, String tool,
			Map<String, Object> arguments) {
		List<McpServerFeatures.SyncToolSpecification> specifications = (List<McpServerFeatures.SyncToolSpecification>) context
			.getBeanProvider(STATEFUL_TOOL_SPECIFICATIONS)
			.getObject();
		McpServerFeatures.SyncToolSpecification specification = specifications.stream()
			.filter((candidate) -> candidate.tool().name().equals(tool))
			.findFirst()
			.orElseThrow();
		return specification.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
	}

	private static String firstText(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

}
