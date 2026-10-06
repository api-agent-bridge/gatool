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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The output schema, and the promise it makes.
 *
 * <p>
 * MCP makes {@code outputSchema} optional and binding: a server that publishes one MUST
 * return structured results that conform to it. These tests take the schema GATool
 * publishes and validate real results against it with the same JSON Schema library the
 * MCP Java SDK validates tool input with, including the responses that are easy to
 * forget: a GraphQL error with partial data, and a field the model switched off with
 * {@code @skip}.
 */
class OutputSchemaTests {

	private static final String SDL = """
			type Query {
			  movie: Movie
			  movies: [Movie!]!
			  search(text: String!): [Result!]!
			  pay(cardId: ID!): PayResult!
			  node(id: ID!): Node
			  orphans: [Orphan!]!
			}

			type Movie implements Node {
			  id: ID!
			  title: String!
			  rating: Float
			  genre: Genre!
			  director: Person
			}

			type Person {
			  name: String!
			  born: Int
			}

			union Result = Movie | Person

			enum Genre { ACTION COMEDY }

			"The errors-as-data pattern: one member says it worked, the others say why not."
			union PayResult = Payment | InsufficientFunds | CardDeclined

			type Payment { id: ID!  amountCents: Int! }

			type InsufficientFunds { message: String!  shortfallCents: Int! }

			type CardDeclined { message: String!  code: String! }

			interface Node { id: ID! }

			type Studio implements Node { id: ID!  name: String! }

			"An interface no object type implements, which is legal SDL."
			interface Orphan { id: ID! }
			""";

	// The validator the MCP Java SDK itself uses for structured content, so the
	// assertion is the check a client makes.
	private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AtomicReference<String> RESPONSE = new AtomicReference<>("");

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@BeforeAll
	static void startApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopApi() {
		server.stop(0);
	}

	@Test
	void outputSchema_propertyOff_shouldPublishNone(@TempDir Path folder) throws Exception {
		write(folder, MOVIES_OPERATION);

		assertThat(toolFrom(folder, false).outputSchema()).isNull();
	}

	@Test
	void outputSchema_propertyOn_shouldDescribeTheEnvelope(@TempDir Path folder) throws Exception {
		write(folder, MOVIES_OPERATION);

		String schema = toolFrom(folder, true).outputSchema();

		assertThat(schema).isNotNull();
		assertThat(JSON.readTree(schema).path("properties").propertyNames()).containsExactly("data", "errors");
	}

	@Test
	void outputSchema_directiveTrueWhileThePropertyIsOff_shouldPublishForThatToolAlone(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Returns every movie.
				query Movies @gatool(outputSchema: true) {
				  movies { id title genre }
				}
				""");

		assertThat(toolFrom(folder, false).outputSchema()).isNotNull();
	}

	@Test
	void outputSchema_directiveFalseWhileThePropertyIsOn_shouldHoldThatToolBack(@TempDir Path folder) throws Exception {
		write(folder, """
				# Returns every movie.
				query Movies @gatool(outputSchema: false) {
				  movies { id title genre }
				}
				""");

		assertThat(toolFrom(folder, true).outputSchema()).isNull();
	}

	@Test
	void structuredContent_successfulCall_shouldConformToThePublishedSchema(@TempDir Path folder) throws Exception {
		write(folder, MOVIES_OPERATION);
		// Every key the operation selected is present, which is what GraphQL returns:
		// rating does not have a value, so it arrives as null.
		RESPONSE.set("{\"data\":{\"movies\":[{\"id\":\"1\",\"title\":\"Arrival\",\"genre\":\"ACTION\","
				+ "\"rating\":null}]}}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_enumValueTheSchemaHasYetToDeclare_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, MOVIES_OPERATION);
		// An API that adds an enum value answers with it before the SDL GATool holds
		// catches up, so the output schema publishes the type without the value list.
		RESPONSE.set("{\"data\":{\"movies\":[{\"id\":\"1\",\"title\":\"Arrival\",\"genre\":\"SCIFI\","
				+ "\"rating\":8.1}]}}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_graphQlErrorWithPartialData_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, MOVIES_OPERATION);
		// The shape a model meets on a bad day: some data, an error beside it, and a
		// null where the API could not answer.
		RESPONSE.set("{\"data\":{\"movies\":null},\"errors\":[{\"message\":\"rating service is down\","
				+ "\"path\":[\"movies\",0,\"rating\"],\"locations\":[{\"line\":2,\"column\":3}]}]}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_errorWithNoDataAtAll_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, MOVIES_OPERATION);
		RESPONSE.set("{\"data\":null,\"errors\":[{\"message\":\"the query is not allowed\"}]}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_errorWithANullPathAndNullLocations_shouldStillConform(@TempDir Path folder)
			throws Exception {
		write(folder, MOVIES_OPERATION);
		// The shape AWS AppSync answers a variable that fails coercion with: the members
		// it cannot fill are written as null, where most servers leave them out.
		RESPONSE.set("{\"data\":null,\"errors\":[{\"path\":null,\"locations\":null,"
				+ "\"message\":\"Variable 'genre' has an invalid value.\"}]}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_errorWithNullExtensionsAndANullMessage_shouldStillConform(@TempDir Path folder)
			throws Exception {
		write(folder, MOVIES_OPERATION);
		RESPONSE.set("{\"data\":{\"movies\":null},\"errors\":[{\"message\":null,\"extensions\":null}]}");

		assertConforms(folder);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"data\":null,\"errors\":[{\"code\":\"UNAUTHENTICATED\"}]}",
			"{\"data\":null,\"errors\":[\"Rate limit exceeded\"]}", "{\"data\":null,\"errors\":[429]}",
			"{\"data\":null,\"errors\":[null]}",
			"{\"data\":null,\"errors\":[{\"message\":{\"text\":\"Rate limit exceeded\"},\"locations\":\"line 2\","
					+ "\"path\":\"movies\"}]}",
			"{\"data\":null,\"errors\":{\"message\":\"Rate limit exceeded\"}}",
			"{\"data\":null,\"errors\":\"Rate limit exceeded\"}",
			"{\"data\":{\"movies\":[]},\"errors\":[\"Rate limit exceeded\"]}" })
	void structuredContent_errorsInAShapeOtherThanTheSpecified_shouldStillConform(String answer, @TempDir Path folder)
			throws Exception {
		write(folder, MOVIES_OPERATION);
		// The GraphQL specification gives errors one shape, and an API or a gateway in
		// front of it writes others: an entry without a message, an entry that is a
		// plain string, one object or one string where the list belongs. MCP binds the
		// server to the schema it publishes, so each of these has to conform, or the
		// caller reads a validation error in place of what the API said.
		RESPONSE.set(answer);

		assertConforms(folder);
	}

	@Test
	void structuredContent_errorWithNullMembers_shouldCarryEachNullAsTheApiWroteIt(@TempDir Path folder)
			throws Exception {
		write(folder, MOVIES_OPERATION);
		RESPONSE.set("{\"data\":null,\"errors\":[{\"message\":null,\"locations\":null,\"path\":null,"
				+ "\"extensions\":null}]}");

		this.contextRunner
			.withPropertyValues("gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql",
					"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/api.graphqls",
					"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
					"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
					"gatool.results.publish-output-schema=true")
			.run((context) -> {
				ToolCallOutcome outcome = context.getBean(GAToolCatalog.class).mcpTools().getFirst().call(Map.of());

				// The schema allows the nulls because they arrive: every member the API
				// wrote as null is still a key of the error, holding null.
				assertThat(outcome.text()).isEqualTo("{\"data\":null,\"errors\":[{\"message\":null,"
						+ "\"locations\":null,\"path\":null,\"extensions\":null}]}");
				assertThat(JSON.writeValueAsString(outcome.structuredContent())).isEqualTo(outcome.text());
			});
	}

	@Test
	void structuredContent_fieldTheModelSwitchedOff_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, """
				# Returns every movie.
				query Movies($withTitle: Boolean! = false) {
				  movies { id title @include(if: $withTitle) genre }
				}
				""");
		RESPONSE.set("{\"data\":{\"movies\":[{\"id\":\"1\",\"genre\":\"ACTION\"}]}}");

		// title is Non-Null in the schema and absent here, because the model left the
		// argument false. A required entry for it would break the MUST on this call.
		assertConforms(folder);
	}

	@Test
	void structuredContent_unionSelection_shouldStayPermissive(@TempDir Path folder) throws Exception {
		write(folder, """
				# Searches movies and people.
				query Search($text: String!) {
				  search(text: $text) {
				    ... on Movie { id title }
				    ... on Person { name }
				  }
				}
				""");
		RESPONSE.set("{\"data\":{\"search\":[{\"name\":\"Villeneuve\"}]}}");

		// One concrete type answered, so the other type's fields are absent. Naming a
		// discriminated shape would break this call.
		assertConforms(folder);
	}

	@Test
	void outputSchema_errorsAsDataUnion_shouldNameEachMemberAndItsKeys(@TempDir Path folder) throws Exception {
		write(folder, PAY_OPERATION);

		String schema = toolFrom(folder, true).outputSchema();

		// A model switches on __typename, so the branches carry the discriminator as a
		// const. InsufficientFunds and CardDeclined both carry message, and the const is
		// the only thing telling them apart.
		assertThat(schema).contains("\"const\":\"Payment\"")
			.contains("\"const\":\"InsufficientFunds\"")
			.contains("\"const\":\"CardDeclined\"")
			.contains("shortfallCents")
			.contains("another member of this type");
	}

	@Test
	void structuredContent_eachMemberOfAnErrorsAsDataUnion_shouldConform(@TempDir Path folder) throws Exception {
		write(folder, PAY_OPERATION);
		for (String response : List.of(
				"{\"data\":{\"pay\":{\"__typename\":\"Payment\",\"id\":\"p1\",\"amountCents\":2500}}}",
				"{\"data\":{\"pay\":{\"__typename\":\"InsufficientFunds\",\"message\":\"no funds\","
						+ "\"shortfallCents\":500}}}",
				"{\"data\":{\"pay\":{\"__typename\":\"CardDeclined\",\"message\":\"declined\","
						+ "\"code\":\"51\"}}}")) {
			RESPONSE.set(response);
			assertConforms(folder);
		}
	}

	@Test
	void structuredContent_unionMemberAddedAfterTheFileWasWritten_shouldStillConform(@TempDir Path folder)
			throws Exception {
		write(folder, PAY_OPERATION);
		// The API grew a member. The operation names three, so the fourth lands on the
		// open branch, carrying the position's own selections alone. Without that branch
		// a successful call would come back to the model as a tool error.
		RESPONSE.set("{\"data\":{\"pay\":{\"__typename\":\"RateLimited\"}}}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_unionFieldNulledByAnError_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, PAY_OPERATION);
		RESPONSE.set("{\"data\":{\"pay\":null},\"errors\":[{\"message\":\"the card service is down\"}]}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_nonNullFieldNulledByAnError_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, PAY_OPERATION);
		// amountCents is Int! and the API answered null beside an error. GraphQL nulls
		// the parent instead, and an API that does this is one GATool still answers for.
		RESPONSE.set("{\"data\":{\"pay\":{\"__typename\":\"Payment\",\"id\":\"p1\",\"amountCents\":null}},"
				+ "\"errors\":[{\"message\":\"the ledger is down\"}]}");

		assertConforms(folder);
	}

	@Test
	void outputSchema_unionWithoutTypename_shouldPublishBranchesWithoutTheConst(@TempDir Path folder) throws Exception {
		write(folder, """
				# Searches movies and people.
				query Search($text: String!) {
				  search(text: $text) {
				    ... on Movie { id title }
				    ... on Person { name }
				  }
				}
				""");

		String schema = toolFrom(folder, true).outputSchema();

		// The branches still name each member's keys. Nothing pins them, because a
		// model cannot switch on a response without __typename, and a const on a key
		// the API does not send would refuse every response.
		assertThat(schema).contains("title").contains("name").doesNotContain("\"const\"");
	}

	@Test
	void outputSchema_namedFragmentOnAUnionMember_shouldBranchLikeAnInlineFragment(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Searches movies and people.
				query Search($text: String!) {
				  search(text: $text) {
				    __typename
				    ...movieFields
				    ... on Person { name }
				  }
				}

				fragment movieFields on Movie {
				  id
				  title
				}
				""");

		String schema = toolFrom(folder, true).outputSchema();

		// A named fragment names its member as surely as an inline one does, and a spread
		// is ordinary style in a trusted document, so reading inline fragments alone
		// would leave the empty schema for an operation written the usual way.
		assertThat(schema).contains("\"const\":\"Movie\"").contains("title").contains("\"const\":\"Person\"");
	}

	@Test
	void structuredContent_interfaceSelection_shouldBranchAndStillAcceptAnUnnamedImplementation(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Looks up any node.
				query NodeById($id: ID!) {
				  node(id: $id) {
				    __typename
				    id
				    ... on Movie { title }
				  }
				}
				""");
		// Studio implements Node and the operation does not name a fragment for it, so
		// it answers with the interface-level selections alone.
		RESPONSE.set("{\"data\":{\"node\":{\"__typename\":\"Studio\",\"id\":\"s1\"}}}");

		assertConforms(folder);
		assertThat(toolFrom(folder, true).outputSchema()).contains("\"const\":\"Movie\"").contains("title");
	}

	@Test
	void structuredContent_typeConditionNamingAnInterface_shouldExpandToTheMembers(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Looks up any node.
				query NodeById($id: ID!) {
				  node(id: $id) {
				    __typename
				    ... on Node { id }
				    ... on Movie { title }
				  }
				}
				""");
		RESPONSE.set("{\"data\":{\"node\":{\"__typename\":\"Studio\",\"id\":\"s1\"}}}");

		// A branch keyed to the interface name would not match, because __typename
		// answers with a concrete type, so the condition expands into the members.
		assertConforms(folder);
		assertThat(toolFrom(folder, true).outputSchema()).doesNotContain("\"const\":\"Node\"");
	}

	private void assertConforms(Path folder) {
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql",
					"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/api.graphqls",
					"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
					"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
					"gatool.results.publish-output-schema=true")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
				ToolCallOutcome outcome = tool.call(Map.of());

				assertThat(outcome.structuredContent()).isNotNull();
				Map<String, Object> schema = JSON.readValue(tool.outputSchema(),
						new TypeReference<Map<String, Object>>() {
						});
				JsonSchemaValidator.ValidationResponse response = VALIDATOR.validate(schema,
						outcome.structuredContent());

				assertThat(response.valid())
					.as("the published schema has to accept every response: " + response.errorMessage())
					.isTrue();
				// The text and the structured result carry the same JSON. The structured
				// half is compared as the JSON a transport writes from it, because it is
				// read back from the text with every digit kept, as a BigDecimal or a
				// BigInteger, and Jackson's node equality tells 8.1 as a DecimalNode
				// apart from 8.1 as a DoubleNode.
				assertThat(JSON.readTree(JSON.writeValueAsString(outcome.structuredContent())))
					.isEqualTo(JSON.readTree(outcome.text()));
			});
	}

	@Test
	void structuredContent_fieldSwitchedOffThroughAFragment_shouldStillConform(@TempDir Path folder) throws Exception {
		write(folder, """
				# Returns every movie.
				query Movies($withTitle: Boolean! = false) {
				  movies { id ... @include(if: $withTitle) { title } genre }
				}
				""");
		RESPONSE.set("{\"data\":{\"movies\":[{\"id\":\"1\",\"genre\":\"ACTION\"}]}}");

		// The sibling test above puts the directive on the field. Here it sits on the
		// fragment that brings the field, which removes it from the response just the
		// same, so a required entry for title would break the MUST on this call.
		assertConforms(folder);
	}

	@Test
	void structuredContent_keySelectedTwiceWithOneCopySwitchedOff_shouldStillConform(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Returns the movie, with its rating on request.
				query Movie($x: Boolean! = false) {
				  movie { id title }
				  movie @include(if: $x) { rating }
				}
				""");
		// With the switch off, GraphQL merges the unswitched copy alone, so the response
		// lacks rating. The keys the switched copy brings therefore stay out of required,
		// or the SDK's validator would refuse a response the API answered correctly.
		RESPONSE.set("{\"data\":{\"movie\":{\"id\":\"1\",\"title\":\"Arrival\"}}}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_nestedKeySelectedTwiceWithOneCopySwitchedOff_shouldStillConform(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Returns the movie's director, with the birth year on request.
				query Movie($x: Boolean! = false) {
				  movie { director { name } director @include(if: $x) { born } }
				}
				""");
		RESPONSE.set("{\"data\":{\"movie\":{\"director\":{\"name\":\"Denis\"}}}}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_keySelectedAgainThroughASwitchedFragment_shouldStillConform(@TempDir Path folder)
			throws Exception {
		// The optional detail pattern: a base selection, and a fragment under @include
		// that selects the same object again with more fields.
		write(folder, """
				# Returns the movie, with director details on request.
				query Movie($withDetail: Boolean! = false) {
				  movie { id director { name } ...Detail @include(if: $withDetail) }
				}

				fragment Detail on Movie {
				  director { born }
				}
				""");
		RESPONSE.set("{\"data\":{\"movie\":{\"id\":\"1\",\"director\":{\"name\":\"Denis\"}}}}");

		assertConforms(folder);
	}

	@Test
	void structuredContent_listItemKeySelectedAgainUnderASkippedFragment_shouldStillConform(@TempDir Path folder)
			throws Exception {
		// A list position, and @skip with a default of true, which is the
		// brief-by-default
		// shape: the items lose the keys the skipped fragment brings.
		write(folder, """
				# Returns every movie, briefly unless asked otherwise.
				query Movies($brief: Boolean! = true) {
				  movies { id director { name } ... @skip(if: $brief) { director { born } } }
				}
				""");
		RESPONSE.set("{\"data\":{\"movies\":[{\"id\":\"1\",\"director\":{\"name\":\"Denis\"}}]}}");

		assertConforms(folder);
	}

	@Test
	void outputSchema_keySelectedDirectlyAndThroughAFragment_shouldListItOnceInRequired(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Returns every movie.
				query Movies {
				  movies { id ...MovieKeys genre }
				}

				fragment MovieKeys on Movie {
				  id
				}
				""");

		String schema = toolFrom(folder, true).outputSchema();

		// JSON Schema 2020-12 says the elements of required MUST be unique, and id
		// arrives twice here: once directly and once through the fragment.
		assertThat(schema).isNotNull().doesNotContain("\"id\",\"id\"");
	}

	@Test
	void outputSchema_interfaceNoObjectTypeImplements_shouldPublishAndLeaveThePositionOpen(@TempDir Path folder)
			throws Exception {
		write(folder, """
				# Reads an orphan.
				query Orphans {
				  orphans { id }
				}
				""");

		// No concrete type can ever answer there, so the writer is left without a member
		// to resolve the selections against, and it still writes a schema.
		assertThat(toolFrom(folder, true).outputSchema()).isNotNull();
	}

	private GATool toolFrom(Path folder, boolean outputSchema) {
		AtomicReference<GATool> tool = new AtomicReference<>();
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql",
					"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/api.graphqls",
					"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
					"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
					"gatool.results.publish-output-schema=" + outputSchema)
			.run((context) -> tool.set(context.getBean(GAToolCatalog.class).mcpTools().getFirst()));
		return tool.get();
	}

	private static final String PAY_OPERATION = """
			# Takes a payment.
			query Pay($cardId: ID!) {
			  pay(cardId: $cardId) {
			    __typename
			    ... on Payment { id amountCents }
			    ... on InsufficientFunds { message shortfallCents }
			    ... on CardDeclined { message code }
			  }
			}
			""";

	private static final String MOVIES_OPERATION = """
			# Returns every movie.
			query Movies {
			  movies { id title genre rating }
			}
			""";

	private static void write(Path folder, String operation) throws Exception {
		Files.writeString(folder.resolve("Operation.graphql"), operation);
		Files.writeString(folder.resolve("api.graphqls"), SDL);
	}

}
