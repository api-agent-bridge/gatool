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

package io.gatool.boot.internal.search;

import java.net.ConnectException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.internal.execution.GraphQlResultWriter;
import io.gatool.boot.internal.execution.ToolCallRunner;
import io.gatool.core.internal.schema.SdlSchemaFactory;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;
import io.gatool.core.naming.ToolNamingStrategy;
import io.gatool.core.search.CorpusFormat;
import io.gatool.core.search.SchemaCorpus;
import io.gatool.core.search.SchemaSearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The three tools of the dynamic layer, called directly.
 *
 * <p>
 * Each case here is what a model would do next: search for a field, read a type it named,
 * send an operation and read the refusal. The awkward inputs matter as much as the
 * working ones, because a model that gets an exception reads a tool failure where it
 * needs a sentence telling it what to try.
 */
class DynamicOperationToolsTests {

	private static final String SDL = """
			type Movie { id: ID! title: String! rating: Float }
			input ReviewInput { movieId: ID! score: Int! }
			type Query {
			  \"""Finds the highest rated films.\"""
			  topRatedMovies(first: Int = 10): [Movie!]!
			}
			type Mutation { addReview(input: ReviewInput!): Movie }
			type Subscription {
			  "Sends every review that someone adds to the movie."
			  reviewAdded(movieId: ID!): Movie!
			}
			""";

	// A schema that exposes its query root again under a field, the way GitHub's API
	// does with Query.relay, so validation accepts __schema and __type below the root.
	private static final String SDL_WITH_THE_QUERY_ROOT_UNDER_A_FIELD = """
			type Movie { id: ID! title: String! }
			type Query {
			  topRatedMovies: [Movie!]!
			  relay: Query!
			}
			""";

	private static final int BUDGET = 500;

	private static final int RANKED_HITS = 50;

	private final GraphQLSchema schema = SdlSchemaFactory.schemaFrom(SDL, "dynamic-tools-test.graphqls");

	private final GraphQlResultWriter writer = new GraphQlResultWriter(JsonMapper.builder().build());

	@Test
	void of_theThreeTools_shouldCarryTheNamesTheStrategyGives() {
		assertThat(tools(false)).extracting(GATool::name)
			.containsExactly("searchSchema", "introspectType", "executeGraphql");
		assertThat(toolsNamed(ToolNamingStrategy.snakeCase())).extracting(GATool::name)
			.containsExactly("search_schema", "introspect_type", "execute_graphql");
	}

	@Test
	void of_theTwoReadingTools_shouldSayTheyOnlyRead() {
		assertThat(tools(false)).filteredOn((tool) -> !tool.name().equals("executeGraphql"))
			.allSatisfy((tool) -> assertThat(tool.readOnly()).isTrue());
	}

	@Test
	void of_executeGraphql_shouldSayItWritesOnlyWhenMutationsAreAllowed() {
		// The hint is what a client reads to decide whether to ask a human first, so it
		// follows the switch.
		assertThat(tool(tools(false), "executeGraphql").readOnly()).isTrue();
		assertThat(tool(tools(true), "executeGraphql").readOnly()).isFalse();
	}

	@Test
	void of_everyTool_shouldPublishTheSameSchemaShapeAsACuratedOne() {
		assertThat(tools(false)).allSatisfy((tool) -> assertThat(tool.inputSchema())
			.startsWith("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"")
			.contains("\"additionalProperties\":false"));
	}

	@Test
	void searchSchema_aQuestion_shouldAnswerWithCoordinatesAndTheirDeclarations() {
		ToolCallOutcome outcome = call("searchSchema", Map.of("question", "highest rated films"));

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).contains("Query.topRatedMovies:").contains("topRatedMovies(first: Int): [Movie!]!");
	}

	@Test
	void searchSchema_aBudget_shouldCutTheListTheRankingFound() {
		String question = "movie title rating films";

		long tight = countCoordinates(
				callWith(luceneSearch(false), 20, false, "searchSchema", Map.of("question", question)));
		long unbounded = countCoordinates(
				callWith(luceneSearch(false), 0, false, "searchSchema", Map.of("question", question)));

		// The ranking finds the same hits either way, and the budget is what decides how
		// many of them a model reads.
		assertThat(tight).isPositive().isLessThan(unbounded);
	}

	@Test
	void searchSchema_aBudgetSmallerThanTheBestHit_shouldNameTheMatchItsCostAndTheProperty() {
		// The walk admits a hit only when it fits. Answering that nothing matched would
		// send the model rephrasing a question that had matched.
		String answer = callWith(luceneSearch(false), 1, false, "searchSchema",
				Map.of("question", "movie title rating"));

		assertThat(answer).matches("The best match, [A-Za-z]+\\.[A-Za-z]+, costs [0-9]+ tokens, and "
				+ "gatool\\.dev\\.experimental\\.dynamic-operations\\.search-token-budget allows 1\\. "
				+ "Read [A-Za-z]+ with introspectType, or raise the property\\.");
	}

	@Test
	void searchSchema_theMutationRootWhileMutationsAreOff_shouldStayOutOfTheResults() {
		// The switch reaches the corpus, so the model cannot be offered a coordinate
		// executeGraphql would refuse.
		assertThat(callWith(luceneSearch(false), 0, false, "searchSchema", Map.of("question", "addReview mutation")))
			.doesNotContain("Mutation.addReview");
		assertThat(callWith(luceneSearch(true), 0, true, "searchSchema", Map.of("question", "addReview mutation")))
			.contains("Mutation.addReview:");
	}

	@Test
	void introspectType_theMutationRootWhileMutationsAreOff_shouldRefuseNamingTheSwitch() {
		ToolCallOutcome root = call("introspectType", Map.of("name", "Mutation"));
		ToolCallOutcome input = call("introspectType", Map.of("name", "ReviewInput"));

		assertThat(root.isError()).isTrue();
		assertThat(root.text()).contains("mutation root").contains("allow-mutations").doesNotContain("addReview");
		// The input type only the mutation reaches goes with it, and the refusal says
		// what reaches types here.
		assertThat(input.isError()).isTrue();
		assertThat(input.text()).contains("no query reaches it").doesNotContain("or mutation");
	}

	@Test
	void searchSchema_aQuestionMatchingNothing_shouldSayWhatToTryInstead() {
		ToolCallOutcome outcome = call("searchSchema", Map.of("question", "kubernetes ingress"));

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).contains("Nothing in this schema matched").contains("kubernetes ingress");
	}

	@Test
	void searchSchema_noQuestion_shouldAskForOne() {
		assertThat(call("searchSchema", Map.of()).text()).contains("Ask for something");
	}

	@Test
	void introspectType_aTypeTheSchemaHas_shouldWriteItsDeclaration() {
		// ReviewInput is reached through the mutation alone, so this reads it with
		// mutations on.
		ToolCallOutcome outcome = tool(tools(true), "introspectType").call(Map.of("name", "ReviewInput"));

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).isEqualTo("""
				input ReviewInput {
				  movieId: ID!
				  score: Int!
				}""");
	}

	@Test
	void introspectType_aNameWithSpaceAroundIt_shouldReadTheTypeItNames() {
		// A model writes the name as an argument, and answering one with a trailing space
		// as a type the schema does not declare would send it searching for a type it had
		// just seen.
		ToolCallOutcome outcome = call("introspectType", Map.of("name", " Movie "));

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).startsWith("type Movie {");
	}

	@Test
	void introspectType_itsDescription_shouldNameTheRootTypes() {
		// A model at its first call has to start somewhere, and the root type names are
		// the one thing it may write before a tool result has shown them.
		assertThat(tool(tools(false), "introspectType").description()).contains("The query root is Query.")
			.doesNotContain("mutation root");
		assertThat(tool(tools(true), "introspectType").description())
			.contains("The query root is Query and the mutation root is Mutation.");
	}

	@Test
	void searchSchema_underSnakeCase_shouldNameTheReadingToolAsTheToolListDoes() {
		// A hint that named introspectType whatever the strategy would give a model under
		// snake-case a tool name the list lacks.
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false), runner(),
				ToolNamingStrategy.snakeCase(), new DynamicOperationSettings(1, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(0, 0, 0)));

		String answer = tool(tools, "search_schema").call(Map.of("question", "movie title rating")).text();

		assertThat(answer).contains("with introspect_type").doesNotContain("introspectType");
	}

	@Test
	void introspectType_underSnakeCaseAndPastTheCharacterLimit_shouldNameItselfAsTheToolListDoes() {
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false),
				ToolCallRunner.builder((request) -> {
					throw new IllegalStateException("introspectType answers from the schema alone");
				}, this.writer).maxCharacters(40).build(), ToolNamingStrategy.snakeCase(), new DynamicOperationSettings(
						BUDGET, RANKED_HITS, false, false, false, new DynamicOperationSettings.Limits(0, 0, 0)));

		ToolCallOutcome outcome = tool(tools, "introspect_type").call(Map.of("name", "Movie"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).startsWith("The answer of introspect_type is");
	}

	@Test
	void executeGraphql_anApiOutOfReach_shouldNameTheToolInsteadOfTheOperation() {
		// Named after the model's operation, the failure would read "The tool Top could
		// not reach", and "The tool could not reach" for an anonymous document.
		GATool execute = tool(DynamicOperationTools.of(this.schema, luceneSearch(false), failingRunner(),
				ToolNamingStrategy.camelCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(0, 0, 0))),
				"executeGraphql");

		assertThatExceptionOfType(ToolCallFailedException.class)
			.isThrownBy(() -> execute.call(Map.of("document", "query Top { topRatedMovies { id } }")))
			.withMessageStartingWith("The tool executeGraphql could not reach the GraphQL API");
		assertThatExceptionOfType(ToolCallFailedException.class)
			.isThrownBy(() -> execute.call(Map.of("document", "{ topRatedMovies { id } }")))
			.withMessageStartingWith("The tool executeGraphql could not reach the GraphQL API");
	}

	@Test
	void executeGraphql_anApiOutOfReachUnderSnakeCase_shouldNameTheToolAsTheToolListDoes() {
		GATool execute = tool(DynamicOperationTools.of(this.schema, luceneSearch(false), failingRunner(),
				ToolNamingStrategy.snakeCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(0, 0, 0))),
				"execute_graphql");

		assertThatExceptionOfType(ToolCallFailedException.class)
			.isThrownBy(() -> execute.call(Map.of("document", "query Top { topRatedMovies { id } }")))
			.withMessageStartingWith("The tool execute_graphql could not reach the GraphQL API");
	}

	@Test
	void executeGraphql_variablesThatAreNotAnObject_shouldRefuseAndSayWhatToSend() {
		// Replacing a list in the variables slot with an empty object would run the
		// operation with its variables missing, and the model would read an answer to a
		// question it did not ask.
		ToolCallOutcome outcome = call("executeGraphql", Map.of("document",
				"query Top($first: Int) { topRatedMovies(first: $first) { id } }", "variables", List.of(3)));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("variables").contains("JSON object").contains("by name");
	}

	@Test
	void introspectType_aTypeTheSchemaLacks_shouldSendTheModelBackToSearch() {
		ToolCallOutcome outcome = call("introspectType", Map.of("name", "Studio"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("does not declare a type named 'Studio'")
			.contains("Search for what you need");
	}

	@Test
	void executeGraphql_aValidQuery_shouldRunItAndAnswerWithTheEnvelope() {
		ToolCallOutcome outcome = call("executeGraphql",
				Map.of("document", "query Top { topRatedMovies { id title } }"));

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).contains("\"data\"").contains("Arrival");
	}

	@Test
	void executeGraphql_anAnonymousOperation_shouldRunIt() {
		// GraphQL asks for an operation name once a document holds several, and this tool
		// takes one, so the model may leave it out.
		assertThat(call("executeGraphql", Map.of("document", "{ topRatedMovies { id } }")).isError()).isFalse();
	}

	@Test
	void executeGraphql_aFieldTheSchemaLacks_shouldRefuseWithTheErrorsAndTheFix() {
		ToolCallOutcome outcome = call("executeGraphql", Map.of("document", "{ topRatedMovies { director } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("That document was refused")
			.contains("director")
			.contains("Read every type the errors name");
	}

	@Test
	void executeGraphql_aDocumentThatDoesNotParse_shouldRefuseWithTheSyntaxError() {
		ToolCallOutcome outcome = call("executeGraphql", Map.of("document", "{ topRatedMovies { id "));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("That document was refused");
	}

	@Test
	void executeGraphql_aDocumentPastTheFieldLimit_shouldNameThatLimitOnce() {
		// A refusal that printed the ceiling twice would read "more than 3 fields,
		// counting every fragment where it is spread, and the limit is 3".
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false),
				ToolCallRunner.builder((request) -> {
					throw new IllegalStateException("a refused document does not reach the API");
				}, this.writer).maxCharacters(60000).build(), ToolNamingStrategy.camelCase(),
				new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(0, 3, 0)));

		ToolCallOutcome outcome = tool(tools, "executeGraphql")
			.call(Map.of("document", "{ topRatedMovies { id title rating } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("it selects more fields than the limit of 3");
		assertThat(outcome.text().split("3")).hasSize(2);
	}

	@Test
	void executeGraphql_onAJvmDefaultingToGerman_shouldRefuseInEnglish() {
		// The refusal wraps graphql-java's messages in English sentences of GATool's own,
		// and graphql-java translates them, so the model would read two languages in one
		// answer and one of them unannounced.
		Locale wasDefault = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			ToolCallOutcome invalid = call("executeGraphql", Map.of("document", "{ topRatedMovies { director } }"));
			ToolCallOutcome unparsed = call("executeGraphql", Map.of("document", "{ topRatedMovies { id "));

			assertThat(invalid.text()).contains("Validation error").doesNotContain("Validierungsfehler");
			assertThat(unparsed.text()).contains("Invalid syntax").doesNotContain("Ungültige Syntax");
		}
		finally {
			Locale.setDefault(wasDefault);
		}
	}

	@Test
	void executeGraphql_aMutationWhileTheSwitchIsOff_shouldRefuseAndNameTheSwitch() {
		ToolCallOutcome outcome = call("executeGraphql",
				Map.of("document", "mutation Add { addReview(input: {movieId: \"m1\", score: 9}) { id } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("runs queries here")
			.contains("gatool.dev.experimental.dynamic-operations.allow-mutations")
			.contains("nobody reviewed");
	}

	@Test
	void executeGraphql_aMutationWhileTheSwitchIsOn_shouldRunIt() {
		String text = callWith(luceneSearch(false), BUDGET, true, "executeGraphql",
				Map.of("document", "mutation Add { addReview(input: {movieId: \"m1\", score: 9}) { id } }"));

		assertThat(text).contains("\"data\"");
	}

	@Test
	void executeGraphql_aSubscription_shouldRefuseAndSayWhy() {
		// graphql-java validates a subscription against a schema that declares one, and a
		// classification that read every non-mutation as a query would run the document.
		// In embedded mode the model would then read {"data":{"upstreamPublisher":{...}}}
		// with the error flag clear: graphql-java's publisher serialized as a Java bean.
		ToolCallOutcome outcome = call("executeGraphql",
				Map.of("document", "subscription Watch { reviewAdded(movieId: \"m1\") { id } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("subscription").contains("single result");
	}

	@Test
	void executeGraphql_aSubscriptionWhileMutationsAreOn_shouldStillRefuse() {
		// The switch turns writing on. It does not apply to an operation that answers
		// over a stream: the refusal above stands beside the mutation gate, so it holds
		// with the switch on.
		String text = callWith(luceneSearch(false), BUDGET, true, "executeGraphql",
				Map.of("document", "subscription Watch { reviewAdded(movieId: \"m1\") { id } }"));

		assertThat(text).contains("single result").doesNotContain("upstreamPublisher");
	}

	@Test
	void executeGraphql_aDocumentUsingDefer_shouldRefuseAndSayWhy() {
		// The same rule, broken the other way. The trusted document path refuses @defer
		// and @stream in a file, and the dynamic path repeats the check.
		ToolCallOutcome outcome = call("executeGraphql",
				Map.of("document", "query M { topRatedMovies { id ... on Movie @defer { title } } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("@defer").contains("single result");
	}

	@Test
	void searchSchema_aSchemaWithASubscriptionRoot_shouldLeaveItsFieldsOutOfTheResults() {
		// Every coordinate a search returns is one the model is invited to write an
		// operation against, and no tool GATool publishes can reach a subscription field.
		String text = call("searchSchema", Map.of("question", "reviews someone adds")).text();

		assertThat(text).doesNotContain("Subscription.reviewAdded");
	}

	@Test
	void introspectType_theSubscriptionRoot_shouldRefuseAndSayTheTypeIsOutOfReach() {
		// The type is in the schema, so telling the model it does not exist would send it
		// searching for a name it can already see.
		ToolCallOutcome outcome = call("introspectType", Map.of("name", "Subscription"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("single result").doesNotContain("reviewAdded").doesNotContain("no type");
	}

	@Test
	void executeGraphql_severalOperationsInOneDocument_shouldRefuse() {
		ToolCallOutcome outcome = call("executeGraphql",
				Map.of("document", "query A { topRatedMovies { id } } query B { topRatedMovies { title } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("Send one operation");
	}

	@Test
	void executeGraphql_noDocument_shouldAskForOne() {
		assertThat(call("executeGraphql", Map.of()).text()).contains("Send a GraphQL operation");
	}

	@Test
	void executeGraphql_validateOnly_shouldReturnTheDocumentWithoutCallingTheApi() {
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false), runner(),
				ToolNamingStrategy.camelCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, true,
						new DynamicOperationSettings.Limits(0, 0, 0)));
		GATool execute = tool(tools, "executeGraphql");

		ToolCallOutcome outcome = execute
			.call(Map.of("document", "query Top($first: Int) { topRatedMovies(first: $first) { id title } }",
					"variables", Map.of("first", 3)));

		// The document comes back as written beside its variables, and the envelope
		// stays out, so a test or a benchmark reads what the model wrote. The tool
		// reads as one that only reads.
		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).contains("query Top($first: Int) { topRatedMovies(first: $first) { id title } }")
			.contains("\"first\":3")
			.doesNotContain("\"data\"");
		assertThat(execute.description()).contains("for a person to run");
		assertThat(execute.readOnly()).isTrue();
	}

	@Test
	void executeGraphql_validateOnly_shouldStillRefuseAnInvalidDocument() {
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false), runner(),
				ToolNamingStrategy.camelCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, true,
						new DynamicOperationSettings.Limits(0, 0, 0)));

		ToolCallOutcome outcome = tool(tools, "executeGraphql")
			.call(Map.of("document", "{ topRatedMovies { director } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("director");
	}

	@Test
	void executeGraphql_introspection_shouldRefuseAndPointToTheSchemaTools() {
		ToolCallOutcome outcome = call("executeGraphql", Map.of("document", "{ __schema { types { name } } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("__schema").contains("search the schema");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{ relay { __schema { types { name } } } }",
			"{ relay { relay { __type(name: \"Movie\") { name } } } }",
			"{ relay { ... on Query { __schema { types { name } } } } }",
			"{ relay { ...Types } } fragment Types on Query { __schema { types { name } } }",
			"{ relay { s: __schema { types { name } } } }", "{ relay { t: __type(name: \"Movie\") { name } } }" })
	void executeGraphql_introspectionBelowTheRoot_shouldRefuseAndLeaveTheApiUncalled(String document) {
		// A guard that read the root selections alone would let a document that reaches
		// the query root again through a field carry its introspection to the API.
		AtomicInteger callsToTheApi = new AtomicInteger();
		GATool execute = executeOverTheNestedQueryRoot(callsToTheApi);

		ToolCallOutcome outcome = execute.call(Map.of("document", document));

		assertThat(outcome.isError()).as("the refusal of " + document).isTrue();
		assertThat(outcome.text()).startsWith("That document selects __")
			.contains("introspection runs through the schema tools here");
		assertThat(callsToTheApi).hasValue(0);
	}

	@Test
	void executeGraphql_introspectionAtTheRootAndBelowIt_shouldNameEachFieldOnce() {
		GATool execute = executeOverTheNestedQueryRoot(new AtomicInteger());

		ToolCallOutcome outcome = execute.call(Map.of("document", "{ __schema { types { name } } "
				+ "relay { __schema { types { name } } __type(name: \"Movie\") { name } } }"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).startsWith("That document selects __schema and __type, and introspection");
	}

	@Test
	void executeGraphql_typenameBelowTheRootOfANestedQueryRoot_shouldRun() {
		AtomicInteger callsToTheApi = new AtomicInteger();
		GATool execute = executeOverTheNestedQueryRoot(callsToTheApi);

		ToolCallOutcome outcome = execute
			.call(Map.of("document", "{ __typename relay { __typename topRatedMovies { __typename id } } }"));

		assertThat(outcome.isError()).isFalse();
		assertThat(callsToTheApi).hasValue(1);
	}

	@Test
	void executeGraphql_typenameInASelection_shouldRun() {
		assertThat(call("executeGraphql", Map.of("document", "{ topRatedMovies { __typename id } }")).isError())
			.isFalse();
	}

	@Test
	void executeGraphql_deeperThanTheLimit_shouldRefuseNamingTheDepthAndTheLimit() {
		// A root field is depth one, so this document nests two levels.
		ToolCallOutcome outcome = callLimited(1, 0, 0, "query Top { topRatedMovies { id } }");

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("2 levels deep").contains("limit is 1");
	}

	@Test
	void executeGraphql_moreFieldsThanTheLimit_shouldRefuseCountingSpreadsWhereTheyAreSpread() {
		// The fragment holds two fields and is spread twice, so the API resolves four
		// fields under the root: five in all, and the walk stops once it passes four.
		ToolCallOutcome outcome = callLimited(0, 4, 0,
				"query Top { topRatedMovies { ...Card ...Card } } fragment Card on Movie { id title }");

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("more fields than the limit of 4");
	}

	@Test
	void executeGraphql_moreAliasesThanTheLimit_shouldRefuse() {
		ToolCallOutcome outcome = callLimited(0, 0, 1,
				"query Top { a: topRatedMovies { id } b: topRatedMovies { id } }");

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("2 aliases").contains("limit is 1");
	}

	@Test
	void executeGraphql_fragmentsThatDoubleThirtyFiveTimes_shouldRefuseWithoutWalkingEveryLeaf() {
		// Each fragment spreads the next one twice, so the expanded document holds
		// more leaves than a thread walks in a lifetime, while the text is two
		// kilobytes and passes validation. The walk stops at the ceiling.
		StringBuilder document = new StringBuilder("{ ...F1 } ");
		for (int index = 1; index < 35; index++) {
			document.append("fragment F")
				.append(index)
				.append(" on Query { ...F")
				.append(index + 1)
				.append(" ...F")
				.append(index + 1)
				.append(" } ");
		}
		document.append("fragment F35 on Query { __typename }");

		long start = System.nanoTime();
		ToolCallOutcome outcome = callLimited(0, 0, 0, document.toString());

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("more fields than the limit of 100000");
		assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(5));
	}

	@Test
	void executeGraphql_withinEveryLimit_shouldRun() {
		assertThat(callLimited(2, 5, 1, "query Top { a: topRatedMovies { id title } }").isError()).isFalse();
	}

	@Test
	void executeGraphql_aDocumentOfMoreThan15000Tokens_shouldRefuseItAtTheLimitOfARequest() {
		// An operation file is read without the limits graphql-java puts on a request,
		// because the operator wrote it. A model wrote this document at run time, so it
		// is a request from a caller the server does not know, and the limits stay on
		// it. Every other limit of this tool is off here, so the parser is what refuses.
		StringBuilder document = new StringBuilder("{ topRatedMovies {");
		for (int number = 1; number <= 6000; number++) {
			document.append(" a").append(number).append(": id");
		}
		document.append(" } }");

		ToolCallOutcome outcome = call("executeGraphql", Map.of("document", document.toString()));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("That document was refused")
			.contains("More than 15,000 'grammar' tokens have been presented");
	}

	@Test
	void executeGraphql_aDocumentOfMoreThan1048576Characters_shouldRefuseItAtTheLimitOfARequest() {
		String document = "# " + "x".repeat(1_048_576) + "\n{ topRatedMovies { id } }";

		ToolCallOutcome outcome = call("executeGraphql", Map.of("document", document));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("That document was refused")
			.contains("More than 1,048,576 characters have been presented");
	}

	private ToolCallOutcome callLimited(int maxDepth, int maxFields, int maxAliases, String document) {
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false), runner(),
				ToolNamingStrategy.camelCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(maxDepth, maxFields, maxAliases)));
		return tool(tools, "executeGraphql").call(Map.of("document", document));
	}

	private ToolCallOutcome call(String name, Map<String, Object> arguments) {
		return tool(tools(false), name).call(Map.copyOf(arguments));
	}

	@Test
	void introspectType_aResultPastTheCharacterLimit_shouldRefuseNamingTheProperty() {
		// gatool.results.max-characters is documented as the largest result a tool
		// returns. searchSchema has a token budget of its own, and without this limit a
		// wide type would make introspectType answer with whatever it came to.
		List<GATool> tools = DynamicOperationTools.of(this.schema, luceneSearch(false),
				ToolCallRunner.builder((request) -> {
					throw new IllegalStateException("introspectType does not call the API");
				}, this.writer).maxCharacters(40).build(), ToolNamingStrategy.camelCase(), new DynamicOperationSettings(
						BUDGET, RANKED_HITS, false, false, false, new DynamicOperationSettings.Limits(0, 0, 0)));

		ToolCallOutcome outcome = tool(tools, "introspectType").call(Map.of("name", "Movie"));

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("gatool.results.max-characters");
	}

	private String callWith(SchemaSearch search, int budget, boolean allowMutations, String name,
			Map<String, Object> arguments) {
		List<GATool> tools = DynamicOperationTools.of(this.schema, search, runner(), ToolNamingStrategy.camelCase(),
				new DynamicOperationSettings(budget, RANKED_HITS, allowMutations, false, false,
						new DynamicOperationSettings.Limits(0, 0, 0)));
		return tool(tools, name).call(Map.copyOf(arguments)).text();
	}

	private List<GATool> tools(boolean allowMutations) {
		return DynamicOperationTools.of(this.schema, luceneSearch(allowMutations), runner(),
				ToolNamingStrategy.camelCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, allowMutations, false,
						false, new DynamicOperationSettings.Limits(0, 0, 0)));
	}

	private List<GATool> toolsNamed(ToolNamingStrategy naming) {
		return DynamicOperationTools.of(this.schema, luceneSearch(false), runner(), naming,
				new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(0, 0, 0)));
	}

	private SchemaSearch luceneSearch(boolean allowMutations) {
		return new LuceneSchemaSearch(SchemaCorpus.of(this.schema, CorpusFormat.SDL, false, allowMutations));
	}

	// executeGraphql over the schema that exposes its query root under a field, with
	// an API that counts the calls it receives.
	private GATool executeOverTheNestedQueryRoot(AtomicInteger callsToTheApi) {
		GraphQLSchema nested = SdlSchemaFactory.schemaFrom(SDL_WITH_THE_QUERY_ROOT_UNDER_A_FIELD,
				"dynamic-tools-nested-root-test.graphqls");
		GraphQlExecutor executor = (request) -> {
			callsToTheApi.incrementAndGet();
			return new DefaultExecutionGraphQlResponse(ExecutionInput.newExecutionInput(request.document()).build(),
					result());
		};
		return tool(DynamicOperationTools.of(nested,
				new LuceneSchemaSearch(SchemaCorpus.of(nested, CorpusFormat.SDL, false, false)),
				ToolCallRunner.builder(executor, this.writer).maxCharacters(60000).build(),
				ToolNamingStrategy.camelCase(), new DynamicOperationSettings(BUDGET, RANKED_HITS, false, false, false,
						new DynamicOperationSettings.Limits(0, 0, 0))),
				"executeGraphql");
	}

	// A runner whose API is down, for the message a model reads then.
	private ToolCallRunner failingRunner() {
		return ToolCallRunner.builder((request) -> {
			throw new ResourceAccessException("I/O error", new ConnectException("Connection refused"));
		}, this.writer).maxCharacters(60000).build();
	}

	private ToolCallRunner runner() {
		GraphQlExecutor executor = (request) -> new DefaultExecutionGraphQlResponse(
				ExecutionInput.newExecutionInput(request.document()).build(), result());
		return ToolCallRunner.builder(executor, this.writer).maxCharacters(60000).build();
	}

	private static ExecutionResult result() {
		return ExecutionResultImpl.newExecutionResult()
			.data(Map.of("topRatedMovies", List.of(Map.of("id", "m1", "title", "Arrival"))))
			.build();
	}

	// A hit is written as "Type.field:" and then its declaration, so the lines ending in
	// a colon are the hits.
	private static long countCoordinates(String text) {
		return text.lines().filter((line) -> line.endsWith(":")).count();
	}

	private static GATool tool(List<GATool> tools, String name) {
		return tools.stream().filter((tool) -> tool.name().equals(name)).findFirst().orElseThrow();
	}

}
