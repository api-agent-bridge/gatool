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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data beside errors an API wrote in a shape other than the specified one, with
 * {@code gatool.results.partial-results-as-success} on.
 *
 * <p>
 * The response is then a success, and the MCP Java SDK validates the structured content
 * of a success against the published output schema. A result that fails it is replaced by
 * the SDK's own validation message, so the caller loses the data and what the API said.
 * This is the path where the schema has to accept the errors as the API wrote them.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.results.publish-output-schema=true", "gatool.results.partial-results-as-success=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpErrorsOfAnotherShapeBesideDataOverHttpTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AtomicReference<String> RESPONSE = new AtomicReference<>("");

	// Started before the context, because the property source below reads its port.
	private static final HttpServer API = startApi();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void api(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", () -> "http://127.0.0.1:" + API.getAddress().getPort() + "/graphql");
	}

	@AfterAll
	static void stopApi() {
		API.stop(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"data\":{\"topRatedMovies\":[]},\"errors\":[{\"code\":\"RATINGS_UNAVAILABLE\"}]}",
			"{\"data\":{\"topRatedMovies\":[]},\"errors\":[\"Rate limit exceeded\"]}",
			"{\"data\":{\"topRatedMovies\":[]},\"errors\":{\"message\":\"Rate limit exceeded\"}}",
			"{\"data\":{\"topRatedMovies\":[]},\"errors\":\"Rate limit exceeded\"}" })
	void callTool_dataBesideErrorsInAShapeOtherThanTheSpecified_shouldReturnBothAsTheApiWroteThem(String answer) {
		RESPONSE.set(answer);
		try (McpSyncClient client = mcpClient()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result)).isEqualTo(answer);
			JsonNode structured = JSON.valueToTree(result.structuredContent());
			assertThat(structured).isEqualTo(JSON.readTree(answer));
		}
	}

	private McpSyncClient mcpClient() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	private static String firstText(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	private static HttpServer startApi() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/graphql", (exchange) -> {
				byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
