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
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
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
 * Errors an API wrote in a shape other than the specified one, as an agent meets them
 * over MCP.
 *
 * <p>
 * The GraphQL specification gives {@code errors} one shape: a list of objects that each
 * carry a {@code message}. An API or a gateway in front of it writes others, such as an
 * entry without a {@code message}, an entry that is a plain string, or one object where
 * the list belongs. Spring for GraphQL's response casts the member to a list of maps, and
 * most of these shapes fail that cast with a {@code ClassCastException}. The published
 * output schema accepts each of them, so the errors the API wrote conform to the schema
 * that describes them.
 *
 * <p>
 * Each answer is a tool error, which the MCP Java SDK passes through as it is, so the
 * test validates the structured content itself, the way a client does that checks every
 * result.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.results.publish-output-schema=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpErrorsOfAnotherShapeOverHttpTests {

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

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
	@ValueSource(strings = { "{\"data\":null,\"errors\":[{\"code\":\"UNAUTHENTICATED\"}]}",
			"{\"data\":null,\"errors\":[\"Rate limit exceeded\"]}", "{\"data\":null,\"errors\":[429]}",
			"{\"data\":null,\"errors\":[null]}", "{\"data\":null,\"errors\":{\"message\":\"Rate limit exceeded\"}}",
			"{\"data\":null,\"errors\":\"Rate limit exceeded\"}",
			"{\"data\":{\"topRatedMovies\":[]},\"errors\":[\"Rate limit exceeded\"]}",
			"{\"data\":{\"topRatedMovies\":[]},\"errors\":\"Rate limit exceeded\"}" })
	void callTool_errorsInAShapeOtherThanTheSpecified_shouldReturnThemAsTheApiWroteThemAndFlagTheError(String answer) {
		RESPONSE.set(answer);
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = client.listTools()
				.tools()
				.stream()
				.filter((each) -> each.name().equals("topRatedMovies"))
				.findFirst()
				.orElseThrow();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).as(firstText(result)).isTrue();
			assertThat(firstText(result)).isEqualTo(answer);
			assertThat(result.structuredContent()).as("the structured content").isNotNull();
			JsonNode structured = JSON.valueToTree(result.structuredContent());
			assertThat(structured).isEqualTo(JSON.readTree(answer));
			JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(),
					result.structuredContent());
			assertThat(validation.valid()).as(String.valueOf(validation.errorMessage())).isTrue();
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
