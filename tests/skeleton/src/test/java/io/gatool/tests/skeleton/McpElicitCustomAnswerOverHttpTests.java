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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit over stateful Streamable HTTP with a custom declined answer: the
 * model reads the custom sentence with the tool's name in place of {@code {tool}}.
 *
 * <p>
 * The context keeps Spring AI's default request timeout, so the answer arrives well
 * inside it on a loaded runner. {@link McpElicitSettingsOverHttpTests} holds the
 * one-second timeout.
 */
@SpringBootTest(classes = McpElicitCustomAnswerOverHttpTests.ElicitApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.mcp.operations.locations=classpath:gatool/elicit/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.elicitation.declined-answer=The user said no to {tool}." })
class McpElicitCustomAnswerOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void apiUrl(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_userDeclinesUnderACustomAnswer_shouldReadTheCustomSentenceAndLeaveTheApiUncalled() {
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).build())
			.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
			.elicitation((request) -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, Map.of(), null))
			.build()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
				.isEqualTo("The user said no to topRatedMovies.");
		}
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
	}

	@SpringBootApplication
	static class ElicitApplication {

	}

}
