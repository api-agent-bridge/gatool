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

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Calls the MCP server the way an agent does: one client, over HTTP, against a running
 * application, with the movie API in a second application.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpToolEndToEndTests {

	private static final String MOVIE_SCHEMA = "classpath:io/gatool/fixtures/movies/movies.graphqls";

	@LocalServerPort
	private int port;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class));

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_topRatedMovies_shouldReturnTheMovieCatalogueAsJsonText() {
		try (McpSyncClient client = mcpClient()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

			assertThat(result.isError()).isFalse();
			assertThat(result.content()).singleElement().satisfies((content) -> {
				String text = ((McpSchema.TextContent) content).text();
				assertThat(text).startsWith("{\"data\":{\"topRatedMovies\":[")
					.containsSubsequence("\"title\":\"Signal from Kepler\"", "\"title\":\"The Last Lighthouse\"")
					.doesNotContain("extensions");
			});
		}
	}

	@Test
	void callTool_firstTwo_shouldReturnTheTwoHighestRatedMovies() {
		try (McpSyncClient client = mcpClient()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 2)).build());

			assertThat(result.isError()).isFalse();
			assertThat(textOf(result)).contains("Signal from Kepler", "The Last Lighthouse")
				.doesNotContain("Midnight Recipe");
		}
	}

	@Test
	void listTools_carryingACursor_shouldAnswerWithTheWholeList() {
		// MCP Java SDK 2.0.0 implements pagination in its schema and in its client and
		// leaves the server half out, so both server classes build the result from the
		// whole tool list and leave the request's cursor unread. This test is what fails
		// when the SDK starts paging.
		try (McpSyncClient client = mcpClient()) {
			client.initialize();

			McpSchema.ListToolsResult result = client.listTools("a-cursor-the-server-never-issued");

			assertThat(result.tools()).extracting(McpSchema.Tool::name).containsExactly("topRatedMovies");
			// A null next cursor says the whole list arrived, so a paging client stops.
			assertThat(result.nextCursor()).isNull();
		}
	}

	@Test
	void startup_withoutApiUrl_shouldStopStartupAndNameTheProperty() {
		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + MOVIE_SCHEMA)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.url"));
	}

	@Test
	void startup_withoutSchemaLocation_shouldStopStartupAndNameTheProperty() {
		this.contextRunner.withPropertyValues("gatool.api.url=http://localhost:1/graphql")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.schema.location"));
	}

	@Test
	void startup_operationFilesWithProblems_shouldReportEveryProblemInOneFailure() {
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
					"gatool.api.schema.location=" + MOVIE_SCHEMA,
					"gatool.mcp.operations.locations=classpath*:gatool/broken/")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.hasMessageContaining("TwoOperations.graphql")
				.hasMessageContaining("UnknownField.graphql"));
	}

	@Test
	void startup_mutationOperationFile_shouldPublishAWriteTool() {
		// A mutation is a trusted document the way a query is, reviewed in the same pull
		// request, so it becomes a tool. What tells a client it writes is the annotation
		// set, which comes from the operation type.
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
					"gatool.api.schema.location=" + MOVIE_SCHEMA,
					"gatool.mcp.operations.locations=classpath*:gatool/mutation/")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).singleElement().satisfies((tool) -> {
					assertThat(tool.name()).isEqualTo("addReview");
					assertThat(tool.readOnly()).isFalse();
				});
			});
	}

	@Test
	void call_apiOnAClosedPort_shouldThrowCarryingTheStartersMessageWithoutAnExceptionName() {
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
					"gatool.api.schema.location=" + MOVIE_SCHEMA)
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

				assertThatExceptionOfType(ToolCallFailedException.class).isThrownBy(() -> tool.call(Map.of()))
					.withMessageContaining("topRatedMovies")
					.withMessageNotContaining("Exception")
					.withMessageNotContaining("at org.springframework");
			});
	}

	private static String textOf(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.collect(Collectors.joining());
	}

	private McpSyncClient mcpClient() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
