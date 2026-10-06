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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every transport has to answer the same way.
 *
 * <p>
 * GATool ships two transports, and a defect can show on one of them alone, such as a
 * stdio client corrupted by console logging or a stdio handshake agreeing to a revision
 * the server refuses. Each transport runs these same assertions: one set of behaviours,
 * one subclass per transport, so a rule that holds on one holds on both.
 */
abstract class McpTransportTestBase {

	/**
	 * Returns a client already connected to a server of this transport.
	 * @return the client, which the test closes
	 */
	abstract McpSyncClient client();

	@Test
	void initialize_anyTransport_shouldAgreeOnTheRevisionGAToolServes() {
		try (McpSyncClient client = client()) {
			McpSchema.InitializeResult result = client.initialize();

			assertThat(result.protocolVersion()).isEqualTo("2025-11-25");
			assertThat(result.capabilities().tools()).isNotNull();
		}
	}

	@Test
	void listTools_anyTransport_shouldPublishTheToolWithItsSchema() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.ListToolsResult tools = client.listTools();

			assertThat(tools.tools()).singleElement().satisfies((tool) -> {
				assertThat(tool.name()).isEqualTo("topRatedMovies");
				assertThat(tool.description()).isEqualTo("Returns the highest-rated movies, best first.");
				assertThat(tool.title()).isEqualTo("topRatedMovies");
				// The $id is the SHA-256 of the text the in-process test asserts for this
				// operation, so the two tests pin one text between them.
				assertThat(tool.inputSchema())
					.isEqualTo(Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "$id",
							"urn:gatool:schema:sha256:"
									+ "f84a592c7199341ea91053b385f9a87f4ffb9d92f4264c48ab0d08f6d71e9bc5",
							"type", "object", "properties",
							Map.of("first",
									Map.of("anyOf", List.of(Map.of("type", "integer"), Map.of("type", "null")),
											"description", "How many movies to return.", "default", 10)),
							"required", List.of(), "additionalProperties", false));
			});
		}
	}

	@Test
	void listTools_anyTransport_shouldCarryTheReadOnlyHints() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.Tool tool = client.listTools().tools().getFirst();

			// The hints a query settles, and those alone. The GraphQL specification
			// requires a query field to be free of side effects, so the operation reads
			// alone, leaves the data as it was, and answers the same way each time. The
			// open world hint depends on what sits behind the API, so an operation file
			// declares it and it stays absent here.
			assertThat(tool.annotations().readOnlyHint()).isTrue();
			assertThat(tool.annotations().destructiveHint()).isFalse();
			assertThat(tool.annotations().idempotentHint()).isTrue();
			assertThat(tool.annotations().openWorldHint()).isNull();
		}
	}

	@Test
	void listTools_aToolWithoutATitle_shouldLeaveTheAnnotationsTitleOut() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.Tool tool = client.listTools().tools().getFirst();

			// A client showing the hint renders an annotations.title of the empty string
			// as a blank label. The tool's own title falls back to its name, and the
			// annotations hint stays absent until the operation file sets one.
			assertThat(tool.annotations().title()).isNull();
		}
	}

	@Test
	void listTools_outputSchemaOff_shouldPublishNone() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.Tool tool = client.listTools().tools().getFirst();

			// MCP binds a server to a schema it publishes, so a tool publishes one only
			// where the property or the operation file asked for it.
			assertThat(tool.outputSchema()).isNull();
		}
	}

	@Test
	void callTool_argumentOutsideTheSchema_shouldAnswerWithAToolError() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(
					McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", "three")).build());

			// The SDK validates against the schema GATool published, so the model reads a
			// tool error it can correct.
			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("topRatedMovies", "input validation failed", "/first");
		}
	}

	@Test
	void callTool_argumentNameOutsideTheSchema_shouldBeRefusedBeforeTheApiSeesIt() {
		try (McpSyncClient client = client()) {
			client.initialize();
			int before = MoviesApiServer.graphQlCalls();
			client.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 2)).build());
			int afterAValidCall = MoviesApiServer.graphQlCalls();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("frist", 2)).build());

			// The input schema closes with additionalProperties false, and the SDK
			// validates against it ahead of GATool's handler, so a misspelt name comes
			// back as a tool error that names it and the API's count of calls stays where
			// the valid call left it. The in-process side forwards the same arguments to
			// the API, which InProcessToolEndToEndTests holds.
			assertThat(afterAValidCall).as("calls the API counted after a valid call").isGreaterThan(before);
			assertThat(result.isError()).isTrue();
			assertThat(text(result)).contains("topRatedMovies", "input validation failed", "frist");
			assertThat(MoviesApiServer.graphQlCalls()).as("calls the API counted after the refused call")
				.isEqualTo(afterAValidCall);
		}
	}

	@Test
	void callTool_validArguments_shouldReachTheApi() {
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 2)).build());

			// The widened ID property stops validation from refusing a call GraphQL
			// would take. This tool does not take an ID argument, so the assertion
			// here is the ordinary one: a valid call reaches the API and comes back.
			assertThat(result.isError()).isFalse();
			assertThat(text(result)).contains("data");
		}
	}

	static String text(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.findFirst()
			.orElse("");
	}

}
