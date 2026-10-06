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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.gatool.boot.inprocess.GAToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The result and input settings, read by a real MCP client through a real server
 * answering from the real movie API.
 *
 * <p>
 * {@code gatool.results.max-characters} is set to 200 here, so one movie fits and four
 * movies pass the limit. {@code gatool.inputs.send-explicit-nulls} is on, so a null the
 * model sends reaches the API in place of the document's default, which the in-process
 * side shows; over MCP the server reads a null argument as absent, and one test pins
 * that. {@code gatool.results.publish-output-schema} is on as well, so the tests can tell
 * a result that carries structured content from a refusal that carries text alone. Both
 * sides read the same folder, so the in-process tool and the MCP tool are built from one
 * file.
 *
 * <p>
 * The other settings of the group have tests of their own:
 * {@link McpPartialResultsAsSuccessOverHttpTests} covers
 * {@code gatool.results.partial-results-as-success} on, and
 * {@link McpPartialResultsOverHttpTests} covers it off;
 * {@link McpOutputSchemaOverHttpTests} and {@link OutputSchemaTests} cover the output
 * schema itself; {@link ResponseSizeTests} covers {@code gatool.api.max-response-size},
 * which stops a response on its way in, where the character limit below stops a result on
 * its way out; and {@link FeatureToolsOverHttpTests} covers a null with the default
 * setting, where the document's default applies.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/results-settings/",
				"gatool.in-process.operations.locations=classpath:gatool/results-settings/",
				"gatool.results.max-characters=200", "gatool.results.publish-output-schema=true",
				"gatool.inputs.send-explicit-nulls=true" })
class ResultsAndInputsSettingsOverHttpTests {

	@LocalServerPort
	private int port;

	@Autowired
	private GAToolCallbacks tools;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_resultUnderMaxCharacters_shouldReturnTheDataWithStructuredContent() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// One movie writes as 88 characters, under the limit of 200.
			McpSchema.CallToolResult result = client.callTool(call("topRatedMovies", Map.of("first", 1)));

			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result)).isEqualTo("{\"data\":{\"topRatedMovies\":[{\"id\":\"movie-1\","
					+ "\"title\":\"Signal from Kepler\",\"rating\":8.6}]}}");
			assertThat(result.structuredContent()).isNotNull();
		}
	}

	@Test
	void callTool_resultPastMaxCharacters_shouldAnswerWithATextOnlyToolErrorNamingTheLimit() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The four rated movies write as 256 characters, past the limit of 200.
			McpSchema.CallToolResult result = client.callTool(call("topRatedMovies", Map.of("first", 10)));

			assertThat(result.isError()).isTrue();
			// The model reads which tool, how big, what the limit is and how to ask for
			// less. The data stays behind, because a cut JSON text cannot be parsed.
			assertThat(firstText(result)).contains("The result of topRatedMovies is 256 characters")
				.contains("the limit is 200")
				.contains("Ask for a smaller page with first")
				.doesNotContain("Signal from Kepler");
			// The output schema describes a GraphQL response, and this sentence is
			// GATool's own, so the result carries text alone.
			assertThat(result.structuredContent()).isNull();
		}
	}

	@Test
	void callTool_variableLeftOut_shouldLetTheDocumentDefaultApply() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("moviesOfAGenre", Map.of()));

			// The default filter selects dramas, and the catalog holds two.
			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result))
				.isEqualTo("{\"data\":{\"movies\":{\"edges\":[{\"node\":{\"genre\":\"DRAMA\"}},"
						+ "{\"node\":{\"genre\":\"DRAMA\"}}]}}}");
		}
	}

	@Test
	void call_explicitNullForAVariableWithADefaultInProcess_shouldReachTheApiAsNull() {
		ToolCallback callback = Arrays.stream(this.tools.toolCallbackProvider().getToolCallbacks())
			.filter((candidate) -> candidate.getToolDefinition().name().equals("moviesOfAGenre"))
			.findFirst()
			.orElseThrow();

		String text = callback.call("{\"filter\": null}");

		// With the setting on, the null travels to the API in place of the default,
		// and a null filter matches every movie, so every genre comes back. With
		// the setting off, GATool drops the null and the two dramas come back, as the
		// test above shows.
		assertThat(text).contains("\"ACTION\"").contains("\"COMEDY\"").contains("\"SCIFI\"").contains("\"DRAMA\"");
	}

	@Test
	void callTool_explicitNullForAVariableWithADefaultOverMcp_shouldReachTheApiAsNull() throws Exception {
		// The request carries the null on the wire as a raw body, because the SDK
		// client's argument map cannot hold one. The transport reads the body with
		// GATool's mcpServerJsonMapper, which keeps a null map value, so the null
		// reaches the API in place of the default and every genre comes back, as in
		// the in-process test above.
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"moviesOfAGenre\","
				+ "\"arguments\":{\"filter\":null}}}";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();

		String response;
		try (HttpClient client = HttpClient.newHttpClient()) {
			response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
		}

		assertThat(response).contains("\"isError\":false")
			.contains("\"ACTION\"")
			.contains("\"COMEDY\"")
			.contains("\"SCIFI\"")
			.contains("\"DRAMA\"");
	}

	private McpSyncClient client() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	private static String firstText(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
		return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
