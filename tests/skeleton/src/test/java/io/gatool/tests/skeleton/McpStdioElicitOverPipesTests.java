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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit on a stdio server, end to end over pipes.
 *
 * <p>
 * The server runs as a second JVM with {@code gatool.mcp.stdio.elicitation} on, launched
 * by {@link StdioServerLauncher} as {@link McpOverStdioTests} launches it. Under that
 * property GATool's stdio customizer backs off, so the SDK's default thread pool runs the
 * tool call, and the answer the client writes to the server's standard input reaches the
 * handler waiting for it. This class is the end-to-end proof that the property makes
 * elicitation work over stdio.
 */
class McpStdioElicitOverPipesTests {

	@Test
	void callTool_userAccepts_shouldRunTheOperationOnce() {
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
		assertThat(request.message()).startsWith("Read the top rated movies?");
	}

	@Test
	void callTool_userDeclines_shouldAnswerTheDeclinedSentenceAndLeaveTheApiUncalled() {
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = client(
				(request) -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, Map.of(), null))) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("The user declined the call to topRatedMovies");
		}
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
	}

	private static McpSyncClient client(Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> handler) {
		return StdioServerLauncher
			.client("--gatool.mcp.operations.locations=classpath:gatool/elicit/",
					"--gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
					"--gatool.mcp.stdio.elicitation=true")
			.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
			.elicitation(handler)
			.build();
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

}
