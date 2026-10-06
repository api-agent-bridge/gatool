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

import java.util.Map;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code executeGraphql} in validate-only mode, served by a real MCP server.
 *
 * <p>
 * The API URL points at a closed port. A call that reached it would fail with the
 * transport error, so a result carrying the document proves the tool did not call the
 * API. Mutations are switched on as well, so the test shows that the mode holds a
 * mutation back too.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step",
				"gatool.dev.experimental.dynamic-operations.validate-only=true",
				"gatool.dev.experimental.dynamic-operations.allow-mutations=true" })
class DynamicValidateOnlyOverHttpTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	private int port;

	@Test
	void listTools_validateOnly_shouldDescribeExecuteGraphqlAsReturningTheOperationAndReadOnly() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.Tool executeGraphql = DynamicToolCalls.tool(client, "executeGraphql");

			// Mutations are on, and the tool still declares itself read-only, because in
			// this mode it does not call the API.
			assertThat(executeGraphql.description())
				.startsWith("Checks a GraphQL document against the schema and returns it with its variables "
						+ "for a person to run, without calling the API.")
				.doesNotContain("Queries only");
			assertThat(executeGraphql.annotations().readOnlyHint()).isTrue();
			assertThat(executeGraphql.annotations().destructiveHint()).isFalse();
		}
	}

	@Test
	void executeGraphql_aValidQueryWithVariables_shouldReturnTheDocumentAndTheVariablesAsJson() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();
			String document = "query Top($first: Int) { topRatedMovies(first: $first) { title } }";

			McpSchema.CallToolResult result = client.callTool(DynamicToolCalls.call("executeGraphql",
					Map.of("document", document, "variables", Map.of("first", 2))));

			assertThat(result.isError()).isFalse();
			JsonNode written = JSON.readTree(DynamicToolCalls.text(result));
			assertThat(written.path("document").asString()).isEqualTo(document);
			assertThat(written.path("variables").path("first").asInt()).isEqualTo(2);
			assertThat(written.size()).isEqualTo(2);
		}
	}

	@Test
	void executeGraphql_aValidQueryWithoutVariables_shouldReturnAnEmptyVariablesObject() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ topRatedMovies { title } }");

			assertThat(result.isError()).isFalse();
			JsonNode written = JSON.readTree(DynamicToolCalls.text(result));
			assertThat(written.path("variables").isObject()).isTrue();
			assertThat(written.path("variables").isEmpty()).isTrue();
		}
	}

	@Test
	void executeGraphql_aMutationWithMutationsOn_shouldReturnItAsWrittenWithoutRunningIt() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();
			String document = "mutation Add { addReview(input: {movieId: \"movie-1\", score: 9}) { review { id } } }";

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, document);

			assertThat(result.isError()).isFalse();
			assertThat(JSON.readTree(DynamicToolCalls.text(result)).path("document").asString()).isEqualTo(document);
		}
	}

	@Test
	void executeGraphql_anInvalidDocument_shouldStillBeRefusedWithTheErrors() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ topRatedMovies { tagline } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).startsWith("That document was refused:").contains("tagline");
		}
	}

	@Test
	void executeGraphql_aDocumentOverALimit_shouldStillBeRefused() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();
			StringBuilder document = new StringBuilder("{");
			for (int index = 0; index < 31; index++) {
				document.append(" f").append(index).append(": topRatedMovies { id }");
			}
			document.append(" }");

			// The size checks run before the mode decides what to return, so a document
			// over a limit is refused either way.
			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, document.toString());

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).contains("it uses 31 aliases, and the limit is 30");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
