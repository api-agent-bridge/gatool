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
import java.util.Arrays;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.inprocess.GAToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A custom scalar in a result, as an agent meets it over MCP.
 *
 * <p>
 * The operator describes a scalar once, under {@code gatool.inputs.scalar-schemas}, and
 * the output schema reuses the type and the format of that fragment. Three scalars cover
 * the cases that decide what is reused. {@code Currency} has a fragment whose enum is
 * narrower than what the API returns, so the enum has to stay on the input side.
 * {@code Weight} is accepted as a number and returned as a quoted string, which the
 * reused type refuses. {@code Money} travels the same two ways and carries an entry under
 * {@code gatool.results.scalar-schemas}, which is the setting for that case.
 *
 * <p>
 * The MCP Java SDK validates a successful result against the output schema before it
 * answers, so each call here is judged by the check a caller's result goes through, and
 * the structured content is validated once more with the SDK's validator where the call
 * succeeds.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "spring.ai.mcp.server.protocol=STATELESS",
		"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
		"gatool.results.publish-output-schema=true",
		"gatool.api.schema.location=classpath:gatool/result-scalars/parcels.graphqls",
		"gatool.mcp.operations.locations=classpath:gatool/result-scalars/",
		"gatool.in-process.operations.locations=classpath:gatool/result-scalars/",
		"gatool.inputs.scalar-schemas.Currency.type=string", "gatool.inputs.scalar-schemas.Currency.enum[0]=EUR",
		"gatool.inputs.scalar-schemas.Currency.enum[1]=USD", "gatool.inputs.scalar-schemas.Weight.type=number",
		"gatool.inputs.scalar-schemas.Money.type=number", "gatool.results.scalar-schemas.Money.type=string" })
class McpResultScalarSchemasOverHttpTests {

	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AtomicReference<String> RESPONSE = new AtomicReference<>("");

	// Started before the context, because the property source below reads its port.
	private static final HttpServer API = startApi();

	@LocalServerPort
	private int port;

	@Autowired
	private GAToolCallbacks inProcessTools;

	@DynamicPropertySource
	static void api(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", () -> "http://127.0.0.1:" + API.getAddress().getPort() + "/graphql");
	}

	@AfterAll
	static void stopApi() {
		API.stop(0);
	}

	@Test
	void callTool_aValueOutsideTheEnumOfTheInputFragment_shouldSucceedAndConformToTheOutputSchema() {
		// The fragment lists the two currencies a model may ask for, and the API holds
		// more. The enum published for the result would refuse GBP after the API
		// answered.
		RESPONSE.set("{\"data\":{\"parcel\":{\"currency\":\"GBP\"}}}");
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = tool(client, "parcelCurrency");

			McpSchema.CallToolResult result = client.callTool(
					McpSchema.CallToolRequest.builder("parcelCurrency").arguments(Map.of("currency", "EUR")).build());

			assertThat(JSON.valueToTree(tool.inputSchema()).at("/properties/currency/anyOf").toString())
				.isEqualTo("[{\"type\":\"string\",\"enum\":[\"EUR\",\"USD\"]},{\"type\":\"null\"}]");
			assertThat(scalarIn(tool, "currency").toString())
				.isEqualTo("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(result.structuredContent())
				.isEqualTo(Map.of("data", Map.of("parcel", Map.of("currency", "GBP"))));
			assertConforms(tool, result);
		}
	}

	@Test
	void callTool_aQuotedStringForAScalarTheInputFragmentCallsANumber_shouldReachTheCallerAsAnOutputSchemaError() {
		// The scalar is accepted as a number and returned as a quoted string, and
		// gatool.results.scalar-schemas is left without an entry for it. The reused type
		// refuses the result, and this is what the caller reads until the operator adds
		// the entry.
		RESPONSE.set("{\"data\":{\"parcel\":{\"weight\":\"12.5\"}}}");
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = tool(client, "parcelWeight");

			McpSchema.CallToolResult result = client.callTool(
					McpSchema.CallToolRequest.builder("parcelWeight").arguments(Map.of("heavierThan", 10)).build());

			assertThat(scalarIn(tool, "weight").toString())
				.isEqualTo("{\"anyOf\":[{\"type\":\"number\"},{\"type\":\"null\"}]}");
			// The SDK's message takes the place of the result, so the caller reads which
			// value failed and loses the value itself.
			assertThat(result.isError()).as(firstText(result)).isTrue();
			assertThat(firstText(result)).startsWith("Tool (parcelWeight) output validation failed")
				.contains("/data/parcel/weight: string found, number expected")
				.doesNotContain("12.5");
			assertThat(result.structuredContent()).isNull();
		}
	}

	@Test
	void callTool_aQuotedStringForAScalarWithAnEntryInTheResultsMap_shouldSucceedAndConformToTheOutputSchema() {
		RESPONSE.set("{\"data\":{\"parcel\":{\"price\":\"12.50\"}}}");
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			McpSchema.Tool tool = tool(client, "parcelPrice");

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("parcelPrice").arguments(Map.of("above", 10)).build());

			// The argument stays a number, because the results map is read for results
			// alone.
			assertThat(JSON.valueToTree(tool.inputSchema()).at("/properties/above/anyOf").toString())
				.isEqualTo("[{\"type\":\"number\"},{\"type\":\"null\"}]");
			assertThat(scalarIn(tool, "price").toString())
				.isEqualTo("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(result.structuredContent())
				.isEqualTo(Map.of("data", Map.of("parcel", Map.of("price", "12.50"))));
			assertConforms(tool, result);
		}
	}

	@Test
	void call_theSameResultsInProcess_shouldReturnTheTextTheApiWrote() {
		// An in-process tool carries text, and an output schema travels over MCP alone,
		// so the result the MCP side reports as an error arrives here as the API wrote
		// it.
		ToolCallback weight = inProcessTool("parcelWeight");
		ToolCallback currency = inProcessTool("parcelCurrency");

		RESPONSE.set("{\"data\":{\"parcel\":{\"weight\":\"12.5\"}}}");
		String weightText = weight.call("{\"heavierThan\": 10}");
		RESPONSE.set("{\"data\":{\"parcel\":{\"currency\":\"GBP\"}}}");
		String currencyText = currency.call("{\"currency\": \"EUR\"}");

		assertThat(weightText).isEqualTo("{\"data\":{\"parcel\":{\"weight\":\"12.5\"}}}");
		assertThat(currencyText).isEqualTo("{\"data\":{\"parcel\":{\"currency\":\"GBP\"}}}");
	}

	private static void assertConforms(McpSchema.Tool tool, McpSchema.CallToolResult result) {
		JsonSchemaValidator.ValidationResponse validation = VALIDATOR.validate(tool.outputSchema(),
				result.structuredContent());
		assertThat(validation.valid()).as(String.valueOf(validation.errorMessage())).isTrue();
	}

	// The schema the tool publishes for one field of the parcel.
	private static JsonNode scalarIn(McpSchema.Tool tool, String field) {
		return JSON.valueToTree(tool.outputSchema())
			.at("/properties/data/anyOf/0/properties/parcel/anyOf/0/properties/" + field);
	}

	private static McpSchema.Tool tool(McpSyncClient client, String name) {
		return client.listTools().tools().stream().filter((tool) -> tool.name().equals(name)).findFirst().orElseThrow();
	}

	private ToolCallback inProcessTool(String name) {
		return Arrays.stream(this.inProcessTools.toolCallbackProvider().getToolCallbacks())
			.filter((callback) -> callback.getToolDefinition().name().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private McpSyncClient mcpClient() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	private static String firstText(McpSchema.CallToolResult result) {
		List<McpSchema.Content> content = result.content();
		return content.isEmpty() ? "" : ((McpSchema.TextContent) content.getFirst()).text();
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
