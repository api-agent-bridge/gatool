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
import java.util.concurrent.CopyOnWriteArrayList;

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
 * A tool marked elicit over stateful Streamable HTTP behind GATool's resource server. The
 * client sends the user's answer as a POST whose body is a JSON-RPC response, and that
 * POST passes GATool's security chain like any other request of the session.
 *
 * <p>
 * A response body is without a method, so the compliance filter records an empty method
 * name, the tool name read from the request is null, and the scope check asks for the
 * baseline scope alone. The other elicitation tests run with authentication off, so this
 * test is the one that sends the answer through the chain. The client carries a token
 * with the baseline scope on every request, the answer included, and an accept that the
 * chain let through runs the operation once. The request timeout is set to 5 seconds so
 * that a refused answer would fail this test after 5 seconds, the call reading the
 * timeout sentence, well under the client's 20 seconds.
 */
@SpringBootTest(classes = McpElicitAuthenticatedOverHttpTests.ElicitApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.operations.locations=classpath:gatool/elicit/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "spring.ai.mcp.server.request-timeout=5s" })
class McpElicitAuthenticatedOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_userAcceptsUnderABaselineToken_shouldPassTheAnswerThroughTheChainAndRunTheOperationOnce() {
		String token = LocalIssuer.get().tokenFor("elicit-caller", "gatool-skeleton", List.of("mcp:tools"));
		List<String> answers = new CopyOnWriteArrayList<>();
		int before = MoviesApiServer.graphQlCalls();
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port)
				.httpRequestCustomizer((builder, method, uri, body, context) -> {
					builder.header("Authorization", "Bearer " + token);
					if (body != null && body.contains("\"result\"") && body.contains("\"action\":\"accept\"")) {
						answers.add(body);
					}
				})
				.build())
			.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
			.elicitation((request) -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of(), null))
			.build()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).as(text(result)).isFalse();
			assertThat(text(result)).contains("Signal from Kepler");
		}
		assertThat(answers).as("the answers the client posted with the bearer token").hasSize(1);
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before + 1);
	}

	private static String text(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	@SpringBootApplication
	static class ElicitApplication {

	}

}
