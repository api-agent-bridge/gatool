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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit over stateful Streamable HTTP: the server asks through the MCP SDK
 * client's elicitation handler, accept runs the operation once, and every other answer
 * leaves the API uncalled.
 */
@SpringBootTest(classes = McpElicitOverHttpTests.ElicitApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.mcp.operations.locations=classpath:gatool/elicit/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpElicitOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void apiUrl(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_userAccepts_shouldShowTheQuestionWithTheArgumentsAndRunTheOperationOnce() {
		AtomicReference<McpSchema.@Nullable ElicitFormRequest> asked = new AtomicReference<>();
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = client((request) -> {
			asked.set(request);
			return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of(), null);
		})) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).as(text(result)).isFalse();
			assertThat(text(result)).contains("Signal from Kepler");
		}
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before + 1);
		McpSchema.ElicitFormRequest request = asked.get();
		assertThat(request).isNotNull();
		assertThat(request.message()).isEqualTo("Read the top rated movies?\n\n{\"first\":1}");
		assertThat(request.requestedSchema()).isEqualTo(Map.of("type", "object", "properties", Map.of()));
	}

	@Test
	void callTool_userDeclines_shouldAnswerTheDeclinedSentenceAndLeaveTheApiUncalled() {
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = client(
				(request) -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null, null))) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).isEqualTo("The user declined the call to topRatedMovies, so the API was not "
					+ "called. Ask the user what to do next.");
		}
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
	}

	@Test
	void callTool_userCancels_shouldAnswerTheCancelledSentenceAndLeaveTheApiUncalled() {
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = client(
				(request) -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, null, null))) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).isEqualTo("The user dismissed the confirmation for topRatedMovies without "
					+ "answering, so the API was not called. Ask the user whether to try again.");
		}
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
	}

	@Test
	void callTool_clientWithoutTheElicitationCapability_shouldAnswerUnsupportedAndLeaveTheApiUncalled() {
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).build())
			.build()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).isEqualTo("The tool topRatedMovies asks the user before it runs, and this "
					+ "client did not declare the form elicitation capability, so the API was not called. Connect "
					+ "with a client that supports MCP form elicitation.");
		}
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
	}

	private McpSyncClient client(Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> handler) {
		return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).build())
			.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
			.elicitation(handler)
			.build();
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	@SpringBootApplication
	static class ElicitApplication {

	}

}
