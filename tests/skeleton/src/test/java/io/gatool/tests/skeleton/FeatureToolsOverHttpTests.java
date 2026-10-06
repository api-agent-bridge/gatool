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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
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
 * Every shape the movies fixture carries, served by a real MCP server, called by a real
 * client and answered by the real movie API: a mutation, a OneOf input, an ID argument, a
 * union, an interface, custom scalars in and out, an input object with a field default
 * and a list, a deprecated argument, the {@code @gatool} directive, and an anonymous
 * operation named after its file.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "spring.ai.mcp.server.protocol=STATELESS",
		"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
		"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
		"gatool.mcp.operations.locations=classpath:gatool/features/,classpath:gatool/mutation/,classpath:gatool/mcp/",
		"gatool.results.publish-output-schema=true" })
class FeatureToolsOverHttpTests {

	// The validator the MCP Java SDK uses for structured content, so the assertion is
	// the check a client makes.
	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void listTools_everyOperationFile_shouldPublishTheNamesTheStrategyAndTheDirectiveGive() {
		try (McpSyncClient client = client()) {
			client.initialize();

			List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

			assertThat(names).containsExactlyInAnyOrder("movieByLookup", "nodeById", "searchMovies", "movies_top_rated",
					"topRatedAnonymous", "recentReviews", "oldReviews", "addReview", "topRatedMovies");
		}
	}

	@Test
	void listTools_everyTool_shouldNameBothSchemasWithAnIdTheMetaSchemaAccepts() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The MCP Java SDK keeps a compiled schema under its $id, and under
			// Map.hashCode() for a schema without one, which two tools can share. Every
			// shape the fixture carries is listed here, so each of them is named.
			assertThat(client.listTools().tools()).isNotEmpty().allSatisfy((tool) -> {
				assertThat(tool.inputSchema().get("$id")).asString().matches("urn:gatool:schema:sha256:[0-9a-f]{64}");
				assertThat(tool.outputSchema().get("$id")).asString().matches("urn:gatool:schema:sha256:[0-9a-f]{64}");
				assertThat(VALIDATOR.validateSchema(tool.inputSchema()).valid()).isTrue();
				assertThat(VALIDATOR.validateSchema(tool.outputSchema()).valid()).isTrue();
			});
		}
	}

	@Test
	void listTools_directive_shouldSetTheTitleAndTheOpenWorldHint() {
		McpSchema.Tool tool = tool("movies_top_rated");

		assertThat(tool.title()).isEqualTo("Top rated movies");
		assertThat(tool.annotations().openWorldHint()).isFalse();
		assertThat(tool.annotations().readOnlyHint()).isTrue();
	}

	@Test
	void listTools_mutation_shouldCarryTheWriteHintsAndRequireItsInput() {
		McpSchema.Tool tool = tool("addReview");

		assertThat(tool.annotations().readOnlyHint()).isFalse();
		assertThat(tool.annotations().destructiveHint()).isTrue();
		assertThat(tool.annotations().idempotentHint()).isFalse();
		assertThat(required(tool)).containsExactly("input");
		Map<String, Object> input = property(tool, "input");
		assertThat(input).containsEntry("type", "object");
		assertThat(asMap(input.get("properties"))).containsKeys("movieId", "score", "comment");
	}

	@Test
	void listTools_idArgument_shouldAcceptAStringOrAnInteger() {
		McpSchema.Tool tool = tool("nodeById");

		assertThat(required(tool)).containsExactly("id");
		assertThat(property(tool, "id").get("anyOf"))
			.isEqualTo(List.of(Map.of("type", "string"), Map.of("type", "integer")));
	}

	@Test
	void listTools_oneOfInput_shouldPublishOneBranchPerField() {
		Map<String, Object> by = property(tool("movieByLookup"), "by");

		// One branch per field, each requiring that field alone.
		List<?> branches = (List<?>) by.get("anyOf");
		assertThat(branches).hasSize(2);
		assertThat(branches).extracting((branch) -> asMap(branch).get("required"))
			.containsExactlyInAnyOrder((Object) List.of("id"), List.of("title"));
	}

	@Test
	void listTools_customScalarsAndInputObject_shouldPublishTheFormatTheDefaultAndTheList() {
		McpSchema.Tool tool = tool("recentReviews");

		// DateTime carries the @specifiedBy page, so its JSON form is known.
		assertThat(nullableBranch(property(tool, "since"))).containsEntry("type", "string")
			.containsEntry("format", "date-time");
		// CountryCode is unmapped, so it accepts any JSON value, null included, and
		// the schema's own sentence describes it.
		Map<String, Object> country = property(tool, "country");
		assertThat(country).doesNotContainKeys("type", "anyOf");
		assertThat(country.get("description")).asString().contains("ISO 3166-1");
		Map<String, Object> filter = nullableBranch(property(tool, "filter"));
		Map<String, Object> fields = asMap(filter.get("properties"));
		assertThat(asMap(fields.get("minScore"))).containsEntry("default", 1);
		assertThat(nullableBranch(asMap(fields.get("genres")))).containsEntry("type", "array");
	}

	@Test
	void startup_customScalarWithoutSpecifiedByAndADeprecatedArgument_shouldWarnNamingBoth(CapturedOutput output) {
		assertThat(output.getAll())
			.contains("declares custom scalars that GATool describes to the model as any JSON value: CountryCode")
			.contains("used by recentReviews")
			.contains("OldReviews.graphql")
			.contains("the argument limit of reviews (Use first.)");
	}

	@Test
	void callTool_mutation_shouldStoreTheReviewAndReturnIt() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("addReview",
					Map.of("input", Map.of("movieId", "movie-2", "score", 8, "comment", "Bleak and lovely."))));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("\"score\":8").contains("Bleak and lovely.");
			assertThat(VALIDATOR.validate(tool("addReview").outputSchema(), result.structuredContent()).valid())
				.isTrue();
		}
	}

	@Test
	void callTool_oneOfByTitle_shouldReachTheApi() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(call("movieByLookup", Map.of("by", Map.of("title", "Iron Harbor"))));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("\"id\":\"movie-4\"");
		}
	}

	@Test
	void callTool_oneOfWithoutAField_shouldBeRefusedBeforeTheApi() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The published schema requires exactly one field, and the SDK validates the
			// arguments against it, so the call is refused as a tool error.
			McpSchema.CallToolResult result = client.callTool(call("movieByLookup", Map.of("by", Map.of())));

			assertThat(result.isError()).isTrue();
		}
	}

	@Test
	void callTool_aMovieWhoseRatingFails_shouldReturnThePartialResultAsAToolError() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The third movie's community rating fails, and the API answers with data and
			// an error, which is a tool error by default.
			McpSchema.CallToolResult result = client
				.callTool(call("movieByLookup", Map.of("by", Map.of("id", "movie-3"))));

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("Midnight Recipe").contains("\"errors\"");
		}
	}

	@Test
	void callTool_idAsAString_shouldReturnTheMovieWithItsReviewsAndTheirInstants() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("nodeById", Map.of("id", "movie-1")));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("\"__typename\":\"Movie\"").contains("2026-01-10T18:30:00");
			assertThat(VALIDATOR.validate(tool("nodeById").outputSchema(), result.structuredContent()).valid())
				.isTrue();
		}
	}

	@Test
	void callTool_idAsAnInteger_shouldReachTheApiRatherThanTheValidator() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("nodeById", Map.of("id", 1)));

			// The API answers null for an unknown id, which is a success.
			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("\"node\":null");
		}
	}

	@Test
	void callTool_union_shouldReturnBothMembersWithStructuredContentThatConforms() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("searchMovies", Map.of("text", "a")));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("\"__typename\":\"Movie\"").contains("\"__typename\":\"Person\"");
			assertThat(VALIDATOR.validate(tool("searchMovies").outputSchema(), result.structuredContent()).valid())
				.isTrue();
		}
	}

	@Test
	void callTool_customScalarsInputObjectAndList_shouldReachTheApi() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(call("recentReviews", Map.of("since", "2026-01-01T00:00:00Z", "country", "SI", "filter",
						Map.of("minScore", 8, "genres", List.of("SCIFI")))));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("\"score\":9").doesNotContain("\"score\":7");
		}
	}

	@Test
	void callTool_customScalarValueTheApiRefuses_shouldComeBackAsAToolError() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("recentReviews", Map.of("since", "yesterday")));

			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).contains("yesterday");
		}
	}

	@Test
	void callTool_nullForAVariableWithADefault_shouldLetTheDefaultApply() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// A null for a defaulted variable is dropped, so the document default of 10
			// applies and the API returns every rated movie.
			McpSchema.CallToolResult result = client
				.callTool(call("topRatedMovies", Collections.singletonMap("first", null)));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("Iron Harbor");
		}
	}

	@Test
	void callTool_anonymousOperationNamedAfterItsFile_shouldRun() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("topRatedAnonymous", Map.of()));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("Signal from Kepler").doesNotContain("Midnight Recipe");
		}
	}

	@Test
	void callTool_directiveCarryingOperation_shouldReachTheApiWithoutTheDirective() {
		try (McpSyncClient client = client()) {
			client.initialize();

			// The API answers UnknownDirective to @gatool, so a successful call proves
			// the document was sent without it.
			McpSchema.CallToolResult result = client.callTool(call("movies_top_rated", Map.of("first", 1)));

			assertThat(result.isError()).isFalse();
			assertThat(firstText(result)).contains("Signal from Kepler");
		}
	}

	private McpSchema.Tool tool(String name) {
		try (McpSyncClient client = client()) {
			client.initialize();
			return client.listTools()
				.tools()
				.stream()
				.filter((tool) -> tool.name().equals(name))
				.findFirst()
				.orElseThrow();
		}
	}

	// The SDK hands the input schema over as a map, the way it travels on the wire.
	private static Map<String, Object> property(McpSchema.Tool tool, String name) {
		return (asMap(asMap(tool.inputSchema()).get("properties")).get(name) instanceof Map<?, ?> map) ? asMap(map)
				: Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> required(McpSchema.Tool tool) {
		return (List<Object>) asMap(tool.inputSchema()).get("required");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}

	// A nullable position is anyOf [type, null], and the first branch is the type.
	@SuppressWarnings("unchecked")
	private static Map<String, Object> nullableBranch(Map<String, Object> property) {
		return (Map<String, Object>) ((List<?>) property.get("anyOf")).getFirst();
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
