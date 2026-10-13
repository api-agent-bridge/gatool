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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit over stateful Streamable HTTP under a one-second request timeout:
 * where the user answers after the timeout, the model reads the timeout sentence, and the
 * late accept that reaches the server afterwards leaves the API uncalled.
 *
 * <p>
 * {@link McpElicitCustomAnswerOverHttpTests} holds the custom answer under the default
 * timeout, so a loaded runner cannot turn its answer into a timeout.
 */
@SpringBootTest(classes = McpElicitSettingsOverHttpTests.ElicitApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.mcp.operations.locations=classpath:gatool/elicit/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.request-timeout=1s" })
@ExtendWith(OutputCaptureExtension.class)
class McpElicitSettingsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void apiUrl(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_userAnswersAfterTheRequestTimeout_shouldAnswerTimeoutAndLeaveTheApiUncalled(CapturedOutput output)
			throws InterruptedException {
		int before = MoviesApiServer.graphQlCalls();
		CountDownLatch answering = new CountDownLatch(1);
		try (McpSyncClient client = client((request) -> {
			try {
				Thread.sleep(2_000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			// The last statement before the handler hands its accept to the SDK.
			answering.countDown();
			return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of(), null);
		})) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(text(result)).isEqualTo("The user did not answer the confirmation for topRatedMovies within "
					+ "1 second (spring.ai.mcp.server.request-timeout), so the API was not called.");
			// The client stays open until the handler returns the late accept, and the
			// SDK then posts it to the server. The API count is read for two seconds
			// after that, while the server receives the answer.
			assertThat(answering.await(10, TimeUnit.SECONDS)).as("the handler reached its late accept").isTrue();
			long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
			while (System.nanoTime() < deadline) {
				assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(before);
				Thread.sleep(100);
			}
			// MCP Java SDK 2.0.0 logs this line at ERROR for an answer to a request that
			// already ended, which shows that the late accept reached the server.
			assertThat(output).contains("Unexpected response for unknown id");
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
