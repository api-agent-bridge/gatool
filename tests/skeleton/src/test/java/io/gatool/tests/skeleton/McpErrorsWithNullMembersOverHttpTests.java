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
import java.util.List;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An API error whose members are null, as an agent meets it over MCP.
 *
 * <p>
 * Most servers leave out the members of an error they cannot fill. AWS AppSync writes
 * them as null, so a variable that fails coercion arrives with {@code "path": null} and
 * {@code "locations": null}. The published output schema accepts null for both, so the
 * error the API wrote conforms to the schema that describes it.
 *
 * <p>
 * The MCP Java SDK validates a successful result against the output schema and passes an
 * error result through as it is, so the two tests meet the null members on the two paths.
 * The first runs with {@code gatool.results.partial-results-as-success} on, where a
 * response holding data and errors is a success: the SDK validates it, and its own
 * message takes the place of the data and of the API's error where the result fails. The
 * second answers without data, which is a tool error under either setting, so the test
 * validates the structured content itself, the way a client does that checks every
 * result.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.results.publish-output-schema=true", "gatool.results.partial-results-as-success=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpErrorsWithNullMembersOverHttpTests {

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

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

	@Test
	void callTool_anErrorWithNullMembersBesideTheData_shouldReturnTheDataAndTheErrorTheApiWrote() {
		RESPONSE.set("{\"data\":{\"topRatedMovies\":[{\"id\":\"movie-1\",\"title\":\"Signal from Kepler\","
				+ "\"rating\":null}]},\"errors\":[{\"message\":\"The rating service is down.\","
				+ "\"locations\":null,\"path\":null,\"extensions\":null}]}");
		try (McpSyncClient client = mcpClient()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result)).contains("Signal from Kepler").contains("The rating service is down.");
			assertThat(onlyError(result)).containsEntry("message", "The rating service is down.")
				.containsEntry("locations", null)
				.containsEntry("path", null)
				.containsEntry("extensions", null);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void callTool_anErrorWhoseExtensionsTheApiFilled_shouldCarryThemAsTheApiWroteThem() {
		// The README says that an error's own extensions travel with the error. The
		// API decides what they hold, and some write an exception class there.
		RESPONSE.set("{\"data\":null,\"errors\":[{\"message\":\"The rating service is down.\","
				+ "\"path\":[\"topRatedMovies\"],\"extensions\":{\"code\":\"DOWNSTREAM_SERVICE_ERROR\","
				+ "\"classification\":\"INTERNAL_ERROR\",\"exception\":"
				+ "\"java.net.ConnectException: Connection refused\"}}]}");
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = client.listTools().tools().getFirst();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("\"code\":\"DOWNSTREAM_SERVICE_ERROR\"")
				.contains("\"exception\":\"java.net.ConnectException: Connection refused\"");
			assertThat((Map<String, Object>) onlyError(result).get("extensions"))
				.containsEntry("code", "DOWNSTREAM_SERVICE_ERROR")
				.containsEntry("classification", "INTERNAL_ERROR")
				.containsEntry("exception", "java.net.ConnectException: Connection refused");
			JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(),
					result.structuredContent());
			assertThat(validation.valid()).as(String.valueOf(validation.errorMessage())).isTrue();
		}
	}

	@Test
	void callTool_aCoercionErrorWithANullPathAndNullLocations_shouldCarryStructuredContentThatConforms() {
		RESPONSE.set("{\"data\":null,\"errors\":[{\"path\":null,\"locations\":null,"
				+ "\"message\":\"Variable 'first' has an invalid value.\"}]}");
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = client.listTools().tools().getFirst();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("Variable 'first' has an invalid value.");
			assertThat(onlyError(result)).containsEntry("path", null).containsEntry("locations", null);
			JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(),
					result.structuredContent());
			assertThat(validation.valid()).as(String.valueOf(validation.errorMessage())).isTrue();
		}
	}

	// The SDK types the structured content as Object, so reading the one error needs the
	// casts.
	@SuppressWarnings("unchecked")
	private static Map<String, Object> onlyError(McpSchema.CallToolResult result) {
		Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
		assertThat(structured).containsKey("errors");
		List<Map<String, Object>> errors = (List<Map<String, Object>>) structured.get("errors");
		assertThat(errors).hasSize(1);
		return errors.getFirst();
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
