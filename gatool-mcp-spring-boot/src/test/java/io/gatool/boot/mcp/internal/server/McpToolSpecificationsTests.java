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

package io.gatool.boot.mcp.internal.server;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.mcp.internal.transport.RequestThread;
import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolSpecificationsTests {

	private static final String EMPTY_VARIABLES_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

	// The input schemas the writer produces for $prId: ID! and for $prID: ID!. The hash
	// codes of the two names differ by 32, each name sits once under properties and once
	// under required, and the two differences cancel in the sum Map.hashCode() makes.
	private static final String PR_ID_SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
			+ "\"type\":\"object\",\"properties\":{\"prId\":{\"anyOf\":[{\"type\":\"string\"},"
			+ "{\"type\":\"integer\"}]}},\"required\":[\"prId\"],\"additionalProperties\":false}";

	private static final String PR_ID_UPPER_CASE_SCHEMA = PR_ID_SCHEMA.replace("prId", "prID");

	// A description outside ASCII, so the digest depends on the bytes the text is read
	// as.
	private static final String DIRECTOR_SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
			+ "\"type\":\"object\",\"properties\":{\"director\":{\"type\":\"string\","
			+ "\"description\":\"The name of a director, such as Mira Kovač.\"}},"
			+ "\"required\":[\"director\"],\"additionalProperties\":false}";

	private static final String MOVIES_OUTPUT_SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
			+ "\"type\":\"object\",\"properties\":{\"data\":{\"anyOf\":[{\"type\":\"object\"},"
			+ "{\"type\":\"null\"}]}},\"required\":[]}";

	private static final String FAILURE_MESSAGE = "The tool topRatedMovies could not reach the GraphQL API. "
			+ "The call did not run, and a later call may succeed.";

	@Test
	void call_toolAnsweringWithAnOutcome_shouldCopyTheTextAndTheErrorFlag() {
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{\"topRatedMovies\":[]}}", true));

		McpSchema.CallToolResult result = McpToolSpecifications.call(tool, Map.of());

		assertThat(result.isError()).isTrue();
		assertThat(result.content()).singleElement()
			.satisfies((content) -> assertThat(((McpSchema.TextContent) content).text())
				.isEqualTo("{\"data\":{\"topRatedMovies\":[]}}"));
	}

	@Test
	void call_aPartialGraphQlResponse_shouldCarryTheStructuredEnvelopeBesideTheErrorFlag() {
		// An API that answers with data and errors together gives a response that
		// conforms to the published output schema, and MCP asks a server publishing an
		// output schema for structured results that conform to it. The flag says the
		// call carries errors, and the structured half says what came back with them.
		Map<String, Object> envelope = Map.of("data", Map.of("topRatedMovies", List.of()), "errors",
				List.of(Map.of("message", "field failed")));
		GATool tool = tool((arguments) -> new ToolCallOutcome(
				"{\"data\":{\"topRatedMovies\":[]}," + "\"errors\":[{\"message\":\"field failed\"}]}", true, envelope));

		McpSchema.CallToolResult result = McpToolSpecifications.call(tool, Map.of());

		assertThat(result.isError()).isTrue();
		assertThat(result.structuredContent()).isEqualTo(envelope);
	}

	@Test
	void call_toolRaisingToolCallFailed_shouldReturnAnErrorResultWithTheStartersMessage() {
		GATool tool = tool((arguments) -> {
			throw new ToolCallFailedException(FAILURE_MESSAGE, new IllegalStateException("Connection refused"));
		});

		McpSchema.CallToolResult result = McpToolSpecifications.call(tool, Map.of());

		assertThat(result.isError()).isTrue();
		assertThat(result.content()).singleElement().satisfies((content) -> {
			String text = ((McpSchema.TextContent) content).text();
			assertThat(text).isEqualTo(FAILURE_MESSAGE);
			assertThat(text).doesNotContain("IllegalStateException");
		});
	}

	@Test
	void specification_toolWithAnImageResponseMimeType_shouldAnswerWithTheSelectedFieldAsImageContent() {
		// A tool built from an operation file answers with a GraphQL envelope, so the
		// image lives in the one field the operation selects. Handing the envelope to
		// ImageContent would produce data beginning with {"data": which a client cannot
		// decode.
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{\"poster\":\"iVBORw0KGgo=\"}}", false));

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool, jsonMapper(), policy(Map.of("topRatedMovies", "image/png")))
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

		assertThat(result.isError()).isFalse();
		assertThat(result.content()).singleElement().satisfies((content) -> {
			McpSchema.ImageContent image = (McpSchema.ImageContent) content;
			assertThat(image.mimeType()).isEqualTo("image/png");
			assertThat(image.data()).isEqualTo("iVBORw0KGgo=");
			assertThat(Base64.getDecoder().decode(image.data())).isNotEmpty();
		});
	}

	@Test
	void specification_aMimeTypeWhoseTypeIsNotImage_shouldAnswerWithText() {
		// startsWith("image") accepts any value opening with those letters, so
		// imagex/thing would turn a JSON result into an image block that a client cannot
		// decode. The check reads the parsed type, the way Spring AI's own converter
		// reads the same property.
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{\"poster\":\"iVBORw0KGgo=\"}}", false));

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool, jsonMapper(), policy(Map.of("topRatedMovies", "imagex/thing")))
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

		assertThat(result.content()).singleElement().isInstanceOf(McpSchema.TextContent.class);
	}

	@Test
	void specification_imageMimeTypeAndAnOperationSelectingTwoFields_shouldAnswerWithText() {
		GATool tool = tool((arguments) -> new ToolCallOutcome(
				"{\"data\":{\"poster\":\"iVBORw0KGgo=\",\"title\":\"Arrival\"}}", false));

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool, jsonMapper(), policy(Map.of("topRatedMovies", "image/png")))
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

		// GATool cannot tell which field holds the image, so the model reads the response
		// it would have read anyway.
		assertThat(result.content()).singleElement()
			.satisfies((content) -> assertThat(((McpSchema.TextContent) content).text()).contains("Arrival"));
	}

	@Test
	void specification_imageMimeTypeAndAFieldThatIsNotBase64_shouldAnswerWithText() {
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{\"poster\":\"not an image\"}}", false));

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool, jsonMapper(), policy(Map.of("topRatedMovies", "image/png")))
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

		assertThat(result.content()).singleElement()
			.satisfies((content) -> assertThat(((McpSchema.TextContent) content).text()).contains("not an image"));
	}

	@Test
	void specification_toolWithoutAResponseMimeType_shouldAnswerWithText() {
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{}}", false));

		McpSchema.CallToolResult result = McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of()))
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

		assertThat(result.content()).singleElement()
			.satisfies((content) -> assertThat(((McpSchema.TextContent) content).text()).isEqualTo("{\"data\":{}}"));
	}

	@Test
	void call_onTheRequestThreadUnderTheRequestThreadRule_shouldRunTheTool() {
		// The rule is what the compliance filter marks each request thread for, and a
		// marked thread reaches the tool.
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{\"topRatedMovies\":[]}}", false));
		RequestThread.bind();
		try {
			McpSchema.CallToolResult result = McpToolSpecifications
				.specification(tool, jsonMapper(), policy(Map.of()).requiringTheRequestThread(true))
				.callHandler()
				.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

			assertThat(result.isError()).isFalse();
			assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
				.isEqualTo("{\"data\":{\"topRatedMovies\":[]}}");
		}
		finally {
			RequestThread.unbind();
		}
	}

	@Test
	void call_offTheRequestThreadUnderTheRequestThreadRule_shouldAnswerTheRefusalWithoutRunningTheTool() {
		GATool tool = tool((arguments) -> new ToolCallOutcome("{\"data\":{\"topRatedMovies\":[]}}", false));

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool, jsonMapper(), policy(Map.of()).requiringTheRequestThread(true))
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());

		assertThat(result.isError()).isTrue();
		assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
			.startsWith("The tool topRatedMovies was not called.")
			.contains("a thread that does not carry the caller");
	}

	private static McpCallSettings policy(Map<String, String> mimeTypes) {
		return new McpCallSettings((caller, toolName) -> RateLimitDecision.allow(), () -> "10.0.0.1",
				ObservationRegistry.NOOP, false, mimeTypes);
	}

	private static JsonMapper jsonMapper() {
		return JsonMapper.builder().build();
	}

	@Test
	void specification_aBlankTitle_shouldFallBackToTheToolName() {
		// @gatool(title: "") reaches here as the empty string, and a null check alone
		// would publish it, where a client showing a title renders a blank label. Spring
		// AI falls back to the tool name for a hand-written tool whose author leaves the
		// title unset, and a blank one says as little as an unset one.
		GATool blankTitle = GATool.builder()
			.name("topRatedMovies")
			.title("")
			.description("Returns the highest-rated movies, best first.")
			.inputSchema(EMPTY_VARIABLES_SCHEMA)
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.Tool published = McpToolSpecifications
			.specification(blankTitle, jsonMapper(),
					new McpCallSettings((caller, name) -> RateLimitDecision.allow(), () -> "10.0.0.1",
							ObservationRegistry.NOOP, false, Map.of()))
			.tool();

		assertThat(published.title()).isEqualTo("topRatedMovies");
	}

	@Test
	void specification_aDecimalDefaultOnACustomScalar_shouldKeepEveryDigitInTheRecord() {
		// The SDK's own builder reads the schema text with the application's mapper,
		// which turns a JSON float into a double, so the record would carry
		// 1.2345678901234567E9 for this default and the transport would write that to
		// every client.
		GATool tool = GATool.builder()
			.name("amounts")
			.inputSchema("{\"type\":\"object\",\"properties\":{\"d\":{\"default\":1234567890.123456789012345678},"
					+ "\"n\":{\"default\":9223372036854775808}},\"additionalProperties\":false}")
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.Tool published = McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of())).tool();

		@SuppressWarnings("unchecked")
		Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) published.inputSchema()
			.get("properties");
		assertThat(properties.get("d")).containsEntry("default", new BigDecimal("1234567890.123456789012345678"));
		assertThat(properties.get("n")).containsEntry("default", new BigInteger("9223372036854775808"));
	}

	@Test
	void specification_anInputAndAnOutputSchema_shouldCarryTheSha256OfTheirTextAsTheId() {
		// The MCP Java SDK keeps a compiled schema under its $id, and under
		// Map.hashCode()
		// for a schema without one, which two different schemas can share. The id is the
		// SHA-256 of the text the writer produced, read as UTF-8, in lowercase hex.
		GATool tool = GATool.builder()
			.name("moviesOfADirector")
			.inputSchema(DIRECTOR_SCHEMA)
			.readOnly(true)
			.outputSchema(MOVIES_OUTPUT_SCHEMA)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.Tool published = McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of())).tool();

		assertThat(published.inputSchema()).containsEntry("$id",
				"urn:gatool:schema:sha256:ea8871c1a359b1c8efccc24ea7c90f8aaf8f6297046cf061e1f1d339fed34bdb");
		assertThat(published.outputSchema()).containsEntry("$id",
				"urn:gatool:schema:sha256:a54fbf68023f8531529342b5921a176214b73de2d6d032a3db50f7cb8f3cae68");
	}

	@Test
	void specification_anInputAndAnOutputSchema_shouldGrowByTheCharactersTheAdapterStates() {
		// The startup listing and the warning about a large tool count this number for
		// each schema an MCP tool publishes, so it has to be what tools/list carries
		// beyond the writer's text: the keyword, its value and the comma after it.
		GATool tool = GATool.builder()
			.name("pullRequestTitle")
			.inputSchema(PR_ID_SCHEMA)
			.readOnly(true)
			.outputSchema(MOVIES_OUTPUT_SCHEMA)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.Tool published = McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of())).tool();

		assertThat(McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA).isEqualTo(98);
		assertThat(jsonMapper().writeValueAsString(published.inputSchema()))
			.hasSize(PR_ID_SCHEMA.length() + McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA);
		assertThat(jsonMapper().writeValueAsString(published.outputSchema()))
			.hasSize(MOVIES_OUTPUT_SCHEMA.length() + McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA);
	}

	@Test
	void charactersAddedToEachSchema_shouldMatchWhatTheBuildCheckCounts() {
		// gatool-core holds its own copy of this number, because it depends on
		// graphql-java, Jackson and Commons Lang alone, so the two ends are pinned
		// separately.
		// OperationFileCheckTests.mcpSchemaIdCharacters_shouldMatchWhatTheMcpAdapterAdds
		// asserts the copy in the core, and this one asserts the adapter's own constant.
		assertThat(McpToolSpecifications.CHARACTERS_ADDED_TO_EACH_SCHEMA).isEqualTo(98);
	}

	@Test
	void specification_aSchemaDeclaringItsDialect_shouldCarryTheIdRightAfterIt() {
		GATool tool = GATool.builder()
			.name("pullRequestTitle")
			.inputSchema(PR_ID_SCHEMA)
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.Tool published = McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of())).tool();

		assertThat(published.inputSchema().keySet()).containsExactly("$schema", "$id", "type", "properties", "required",
				"additionalProperties");
	}

	@Test
	void specification_aSchemaWithoutADialect_shouldCarryTheIdFirst() {
		McpSchema.Tool published = McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{}", false)), jsonMapper(), policy(Map.of()))
			.tool();

		assertThat(published.inputSchema().keySet()).containsExactly("$id", "type", "properties",
				"additionalProperties");
	}

	@Test
	void specification_twoSchemasSharingAHashCode_shouldCarryTwoIdsAndValidateTheirOwnArguments() {
		Map<String, Object> first = publishInputSchema(PR_ID_SCHEMA);
		Map<String, Object> second = publishInputSchema(PR_ID_UPPER_CASE_SCHEMA);
		// The collision this test is about, stated on the two schemas as the writer
		// produced them.
		assertThat(withoutId(first)).hasSameHashCodeAs(withoutId(second));
		// One validator for both schemas, as the SDK holds one for every tool of the JVM.
		JsonSchemaValidator validator = new DefaultJsonSchemaValidator();

		JsonSchemaValidator.ValidationResponse firstCall = validator.validate(first, Map.of("prId", "PR_1"));
		JsonSchemaValidator.ValidationResponse secondCall = validator.validate(second, Map.of("prID", "PR_1"));
		JsonSchemaValidator.ValidationResponse misspelt = validator.validate(second, Map.of("prId", "PR_1"));

		assertThat(firstCall.valid()).as(String.valueOf(firstCall.errorMessage())).isTrue();
		assertThat(secondCall.valid()).as(String.valueOf(secondCall.errorMessage())).isTrue();
		assertThat(misspelt.valid()).isFalse();
		assertThat(first).doesNotContainEntry("$id", second.get("$id"));
	}

	@Test
	void specification_twoToolsWithTheSameSchemaText_shouldCarryTheSameId() {
		// One compiled schema is the right one for both tools, so one key is what they
		// should share.
		assertThat(publishInputSchema(PR_ID_SCHEMA).get("$id")).isNotNull()
			.isEqualTo(publishInputSchema(PR_ID_SCHEMA).get("$id"));
	}

	@Test
	void specification_aSchemaCarryingTheId_shouldPassTheMetaSchemaCheckTheSdkRunsAtStartup() {
		GATool tool = GATool.builder()
			.name("moviesOfADirector")
			.inputSchema(DIRECTOR_SCHEMA)
			.readOnly(true)
			.outputSchema(MOVIES_OUTPUT_SCHEMA)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();
		JsonSchemaValidator validator = new DefaultJsonSchemaValidator();

		McpSchema.Tool published = McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of())).tool();

		// The SDK refuses to build a server around a tool whose schema fails the 2020-12
		// meta-schema, so an id the meta-schema refused would stop startup.
		assertThat(published.inputSchema()).containsKey("$id");
		assertThat(published.outputSchema()).containsKey("$id");
		JsonSchemaValidator.ValidationResponse input = validator.validateSchema(published.inputSchema());
		JsonSchemaValidator.ValidationResponse output = validator.validateSchema(published.outputSchema());
		assertThat(input.valid()).as(String.valueOf(input.errorMessage())).isTrue();
		assertThat(output.valid()).as(String.valueOf(output.errorMessage())).isTrue();
	}

	private static Map<String, Object> publishInputSchema(String inputSchema) {
		GATool tool = GATool.builder()
			.name("pullRequestTitle")
			.inputSchema(inputSchema)
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();
		return McpToolSpecifications.specification(tool, jsonMapper(), policy(Map.of())).tool().inputSchema();
	}

	private static Map<String, Object> withoutId(Map<String, Object> schema) {
		Map<String, Object> written = new LinkedHashMap<>(schema);
		written.remove("$id");
		return written;
	}

	@Test
	void specification_aToolWithoutATitle_shouldLeaveTheAnnotationsTitleOut() {
		// An annotations.title of the empty string renders as a blank label in a client
		// showing the hint. The record serialises an absent member as absent, so a tool
		// without a title leaves it out.
		McpSchema.ToolAnnotations annotations = McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{}", false)), jsonMapper(), policy(Map.of()))
			.tool()
			.annotations();

		assertThat(annotations.title()).isNull();
	}

	@Test
	void specification_aToolWithATitle_shouldPublishItInTheAnnotationsAsWell() {
		GATool titled = GATool.builder()
			.name("topRatedMovies")
			.title("Top rated movies")
			.description("Returns the highest-rated movies, best first.")
			.inputSchema(EMPTY_VARIABLES_SCHEMA)
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.Tool published = McpToolSpecifications.specification(titled, jsonMapper(), policy(Map.of())).tool();

		assertThat(published.title()).isEqualTo("Top rated movies");
		assertThat(published.annotations().title()).isEqualTo("Top rated movies");
	}

	@Test
	void call_aWriteToolThatFailsOutright_shouldSayTheWriteMayHaveBeenApplied() {
		// The fallback for a failure this class could not turn into a tool error must not
		// tell a mutation that a later call may succeed. That is the sentence the runner
		// is careful to avoid, because a write whose answer was lost may already have
		// been applied, and a model reading it would send the write twice.
		GATool writeTool = GATool.builder()
			.name("addReview")
			.description("Adds a review to a movie.")
			.inputSchema(EMPTY_VARIABLES_SCHEMA)
			.readOnly(false)
			.callHandler((arguments) -> {
				throw new IllegalStateException("the tool broke in a way run() does not catch");
			})
			.build();

		McpSchema.CallToolResult result = McpToolSpecifications.call(writeTool, Map.of());

		assertThat(result.isError()).isTrue();
		assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("This tool writes")
			.doesNotContain("A later call may succeed.");
	}

	@Test
	void call_aReadToolThatFailsOutright_shouldSayALaterCallMaySucceed() {
		GATool readTool = tool((arguments) -> {
			throw new IllegalStateException("the tool broke in a way run() does not catch");
		});

		McpSchema.CallToolResult result = McpToolSpecifications.call(readTool, Map.of());

		assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("A later call may succeed.");
	}

	@Test
	void specification_aWriteTool_shouldPublishTheHintsAClientAsksAHumanOn() {
		// A client reads these three to decide whether to ask a person before a write,
		// and every test beside this one publishes a read-only tool, so this one asserts
		// the write side of each hint.
		GATool writeTool = GATool.builder()
			.name("addReview")
			.description("Adds a review to a movie.")
			.inputSchema(EMPTY_VARIABLES_SCHEMA)
			.readOnly(false)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		McpSchema.ToolAnnotations annotations = McpToolSpecifications
			.specification(writeTool, jsonMapper(), policy(Map.of()))
			.tool()
			.annotations();

		assertThat(annotations.readOnlyHint()).isFalse();
		assertThat(annotations.destructiveHint()).isTrue();
		assertThat(annotations.idempotentHint()).isFalse();
	}

	private static GATool tool(Function<Map<String, Object>, ToolCallOutcome> handler) {
		return GATool.builder()
			.name("topRatedMovies")
			.description("Returns the highest-rated movies, best first.")
			.inputSchema(EMPTY_VARIABLES_SCHEMA)
			.readOnly(true)
			.callHandler(handler)
			.build();
	}

}
