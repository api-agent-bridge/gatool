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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every refusal the three dynamic tools write, at their default settings, served by a
 * real MCP server against the real movie API.
 *
 * <p>
 * Each refusal is a tool error whose text tells the model its next move, and every one of
 * them stops before the API is called.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step" })
class DynamicOperationRefusalsOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void executeGraphql_aBlankDocument_shouldAskForOne() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "   ");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).isEqualTo("Send a GraphQL operation as the document argument.");
		}
	}

	@Test
	void executeGraphql_aDocumentThatDoesNotParse_shouldRefuseWithTheSyntaxErrorAndTheFix() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ topRatedMovies { title ");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).startsWith("That document was refused:\n  - Invalid syntax")
				.endsWith("Read every type the errors name before trying again, and write only names that have "
						+ "appeared in a tool result.");
		}
	}

	@Test
	void executeGraphql_aDocumentFailingValidation_shouldListEveryError() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client, "{ topRatedMovies { tagline budget } }");

			// Both errors arrive in one refusal, so the model fixes the document once.
			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).contains("  - Validation error")
				.contains("tagline")
				.contains("budget");
		}
	}

	@Test
	void executeGraphql_severalOperationsInOneDocument_shouldRefuse() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"query A { topRatedMovies { id } } " + "query B { topRatedMovies { title } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("Send one operation. A document holding several does not say which to run.");
		}
	}

	@Test
	void executeGraphql_aSubscription_shouldRefuseAndSayWhy() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"subscription Added { reviewAdded(movieId: \"movie-1\") { id } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("That document is a subscription, and a tool call returns a single "
						+ "result, so only a query or a mutation runs here. The fields of the subscription type answer "
						+ "over a stream this server does not open.");
		}
	}

	@Test
	void executeGraphql_aDocumentUsingDefer_shouldRefuseAndSayWhy() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ topRatedMovies(first: 1) { title ... @defer { rating } } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.startsWith("That document uses @defer, and a tool call returns a single result");
		}
	}

	@Test
	void executeGraphql_introspectionAtTheRoot_shouldRefuseAndPointToTheSchemaTools() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ __type(name: \"Movie\") { name } __schema { types { name } } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("That document selects __type and __schema, and introspection runs "
						+ "through the schema tools here: search the schema for what you need, and read one type in "
						+ "full by its name. __typename inside a selection is fine.");
		}
	}

	@Test
	void executeGraphql_typenameInsideASelection_shouldRun() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"{ topRatedMovies(first: 1) { __typename title } }");

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result)).contains("\"__typename\":\"Movie\"");
		}
	}

	@Test
	void executeGraphql_aMutationAtTheDefault_shouldRefuseNamingTheSwitchAndWhyItIsUnsafe() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = DynamicToolCalls.execute(client,
					"mutation Add { addReview(input: {movieId: \"movie-1\", score: 9}) { review { id } } }");

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("This server runs queries here, and that document is a mutation. "
						+ "gatool.dev.experimental.dynamic-operations.allow-mutations turns writing on, and it is an "
						+ "unsafe switch because the operation is one nobody reviewed.");
		}
	}

	@Test
	void introspectType_theSubscriptionRoot_shouldRefuseAndSayWhy() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Subscription")));

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("A subscription answers over a stream, and a tool call returns a "
						+ "single result, so these tools leave the Subscription type out. Look for a query that answers "
						+ "the same question.");
		}
	}

	@Test
	void introspectType_aNameTheSchemaLacks_shouldSendTheModelBackToSearch() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Film")));

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("This schema does not declare a type named 'Film'. Search for what "
						+ "you need: a coordinate names its type before the dot.");
		}
	}

	@Test
	void introspectType_anIntrospectionType_shouldAnswerAsIfTheSchemaLackedIt() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			// The specification reserves __Type for introspection, the corpus leaves it
			// out, and the refusal reads the same as for a name that does not exist.
			McpSchema.CallToolResult result = client
				.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "__Type")));

			assertThat(result.isError()).isTrue();
			assertThat(DynamicToolCalls.text(result)).startsWith("This schema does not declare a type named '__Type'.");
		}
	}

	@Test
	void introspectType_everyKindOfType_shouldWriteItsDeclaration() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			assertThat(DynamicToolCalls
				.text(client.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Genre")))))
				.isEqualTo("enum Genre {\n  ACTION\n  COMEDY\n  DRAMA\n  SCIFI\n}");
			assertThat(DynamicToolCalls
				.text(client.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "SearchResult")))))
				.isEqualTo("\"\"\"A movie or a person.\"\"\"\nunion SearchResult = Movie | Person");
			// An interface names what implements it, because its fields are selected on
			// one of those through an inline fragment.
			assertThat(DynamicToolCalls
				.text(client.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Node")))))
				.isEqualTo("\"\"\"An object with a global id.\"\"\"\ninterface Node {  # implemented by Movie, "
						+ "Person, Review\n  id: ID!\n}");
			assertThat(DynamicToolCalls
				.text(client.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "CountryCode")))))
				.isEqualTo("\"\"\"A two-letter ISO 3166-1 country code, such as SI.\"\"\"\nscalar CountryCode");
			// An object names its return types and leaves them unexpanded, and an
			// argument carries the default the schema declares.
			assertThat(DynamicToolCalls
				.text(client.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Movie")))))
				.startsWith("\"\"\"A movie in the catalog.\"\"\"\ntype Movie implements Node {\n  id: ID!\n")
				.contains("  directors: [Person!]!\n");
			assertThat(DynamicToolCalls
				.text(client.callTool(DynamicToolCalls.call("introspectType", Map.of("name", "Query")))))
				.contains("  topRatedMovies(first: Int = 10): [Movie!]!\n");
		}
	}

	@Test
	void searchSchema_aBlankQuestion_shouldAskForOne() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(DynamicToolCalls.call("searchSchema", Map.of("question", "")));

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result))
				.isEqualTo("Ask for something: this tool takes a question in plain words, such "
						+ "as \"the highest rated films\".");
		}
	}

	@Test
	void searchSchema_aNestedField_shouldSayHowToReachIt() {
		try (McpSyncClient client = DynamicToolCalls.client(this.port)) {
			client.initialize();

			// The analyzer keeps countryCode as one word, so the question spells it the
			// way the schema does.
			McpSchema.CallToolResult result = client
				.callTool(DynamicToolCalls.call("searchSchema", Map.of("question", "countryCode of a person")));

			assertThat(result.isError()).isFalse();
			assertThat(DynamicToolCalls.text(result)).contains("Person.countryCode:\nreach it from Query.");
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
