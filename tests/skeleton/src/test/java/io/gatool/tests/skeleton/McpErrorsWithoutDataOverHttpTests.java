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
 * A response holding errors and a null {@code data}, as an agent meets it over MCP.
 *
 * <p>
 * An API that refuses the whole operation answers {@code "data": null} beside its errors.
 * The in-process test validates that envelope against the published schema inside the
 * process. This one drives it through a real {@code tools/call}, because the MCP Java SDK
 * passes an error result through without validating it, and the wire is where a client
 * reads the flag, the text and the structured half.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.results.publish-output-schema=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpErrorsWithoutDataOverHttpTests {

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	private static final String RESPONSE = "{\"data\":null,\"errors\":[{\"message\":\"the query is not allowed\"}]}";

	// Started before the context, because the property source below reads its port.
	private static final HttpServer API = startRefusingApi();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void refusingApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", () -> "http://127.0.0.1:" + API.getAddress().getPort() + "/graphql");
	}

	@AfterAll
	static void stopRefusingApi() {
		API.stop(0);
	}

	@Test
	void callTool_apiAnswersErrorsWithoutData_shouldFlagTheErrorAndCarryTheEnvelopeAsStructuredContent() {
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = client.listTools()
				.tools()
				.stream()
				.filter((each) -> each.name().equals("topRatedMovies"))
				.findFirst()
				.orElseThrow();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 2)).build());

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("\"data\":null").contains("the query is not allowed");
			// The SDK types this member as Object, so the map assertion needs the cast.
			@SuppressWarnings("unchecked")
			Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
			assertThat(structured).containsEntry("data", null)
				.containsEntry("errors", List.of(Map.of("message", "the query is not allowed")));
			// The schema says data is nullable, so the envelope conforms, which is what
			// MCP asks of a server that publishes an output schema.
			JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(), structured);
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

	private static HttpServer startRefusingApi() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/graphql", (exchange) -> {
				byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
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
