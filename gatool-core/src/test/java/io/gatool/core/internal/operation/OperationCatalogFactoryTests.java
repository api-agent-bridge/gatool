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

package io.gatool.core.internal.operation;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLTypeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.gatool.core.internal.schema.SdlSchemaFactory;
import io.gatool.core.model.RequestLimits;
import io.gatool.core.naming.ToolNamingStrategy;

import static org.assertj.core.api.Assertions.assertThat;

class OperationCatalogFactoryTests {

	// The dialect keyword opens every schema the writer produces, and naming it once
	// keeps each expected value inside the line length the project keeps to.
	private static final String DIALECT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",";

	private static final String TOP_RATED_MOVIES = """
			# Returns the highest-rated movies, best first.
			query TopRatedMovies($first: Int = 10) {
			  topRatedMovies(first: $first) {
			    id
			    title
			  }
			}
			""";

	// Four characters make a token, so three hundred lines of sixty characters cost
	// about four and a half thousand tokens before any call.
	private static final String WORDY = String.join("\n", Collections.nCopies(300, "# " + "x".repeat(60)))
			+ "\nquery Wordy { topRatedMovies { id } }\n";

	private static final String WIDE = "# Returns the highest-rated movies.\nquery Wide { topRatedMovies { "
			+ aliasedIds("a", 400, "", " ") + "} }\n";

	private static final String ADD_REVIEW = """
			# Adds a review to a movie.
			mutation AddReview($input: AddReviewInput!) {
			  addReview(input: $input) {
			    review {
			      id
			      score
			    }
			  }
			}
			""";

	// A schema of its own, because every argument in the movie schema takes a built-in
	// type. The field carries a description, so the tools fall back to the root field's
	// description and the scalar warning is the only warning the catalog holds.
	private static final String COUNTRY_SCHEMA = """
			"A two-letter ISO 3166-1 country code, such as SI."
			scalar CountryCode

			type Query {
			  "Returns the movies made in a country."
			  moviesFrom(country: CountryCode): [String!]!
			}
			""";

	// A schema of its own, for one custom scalar that an argument takes and a result
	// returns, which is where the input schema and the output schema of one tool have to
	// agree about it.
	private static final String PRICES_SCHEMA = """
			scalar Currency
			scalar Money

			type Query {
			  "Returns the price of a product."
			  price(currency: Currency, atLeast: Money): Price
			}

			type Price {
			  currency: Currency
			  amount: Money
			}
			""";

	private static final String PRICE = """
			query Price($currency: Currency, $atLeast: Money) {
			  price(currency: $currency, atLeast: $atLeast) { currency amount }
			}
			""";

	// A schema of its own, because the movie schema writes its one default on a nullable
	// argument. GraphQL allows a default in two more places, and each one changes what an
	// omitted value means, so each one needs its own operation to send a null through.
	private static final String DEFAULTS_SCHEMA = """
			type Query {
			  "Returns the newest movies."
			  newest(first: Int! = 20): [String!]!
			  "Returns a page of movies."
			  paged(paging: Paging!): [String!]!
			}

			"Where a page starts and how long it runs."
			input Paging {
			  first: Int = 20
			  after: String
			}
			""";

	// A schema of its own, for the two shapes the schemas above leave out. One variable
	// fills a Non-Null argument that declares a default and a nullable argument without
	// one, and another is used only inside a member of a union.
	private static final String VOTES_SCHEMA = """
			type Query {
			  "Returns the votes."
			  votes(locales: [Locale!]! = [en]): [Vote!]!
			  "Returns the newest vote or the newest user."
			  newest: VoteOrUser
			  "Returns the votes of a window."
			  recent(window: Window, size: Int): [Vote!]!
			}

			enum Locale {
			  en
			  de
			}

			input Window {
			  size: Int! = 10
			}

			union VoteOrUser = Vote | User

			type Vote {
			  id: ID!
			  publishedBy(locales: [Locale!]): User
			  stage(current: Boolean! = false): String
			}

			type User {
			  name: String
			}
			""";

	// A schema of its own, because the argument of a directive is a place a variable's
	// value lands as well, and a directive the schema declares can give its argument a
	// default, which the two built-in conditions leave out.
	private static final String DIRECTIVES_SCHEMA = """
			directive @audience(
			  "Who the answer is written for."
			  status: String = "public"
			  note: String
			) on QUERY | FIELD

			directive @cache(ttl: Int! = 60) on FIELD

			directive @window(size: Int) on FIELD

			type Query {
			  "Returns the newest movies."
			  newest(first: Int = 20, brief: Boolean! = false): [Movie!]!
			}

			type Movie {
			  id: ID!
			  title: String
			}
			""";

	private final OperationCatalogFactory builder = OperationCatalogFactory
		.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
		.build();

	private final OperationCatalogFactory defaults = OperationCatalogFactory
		.builder(SdlSchemaFactory.schemaFrom(DEFAULTS_SCHEMA, "file:/schema/defaults.graphqls"),
				ToolNamingStrategy.camelCase())
		.build();

	private final OperationCatalogFactory votes = OperationCatalogFactory
		.builder(SdlSchemaFactory.schemaFrom(VOTES_SCHEMA, "file:/schema/votes.graphqls"),
				ToolNamingStrategy.camelCase())
		.build();

	private final OperationCatalogFactory directives = OperationCatalogFactory
		.builder(SdlSchemaFactory.schemaFrom(DIRECTIVES_SCHEMA, "file:/schema/directives.graphqls"),
				ToolNamingStrategy.camelCase())
		.build();

	// A schema of its own, because every field of the movie schema is current. A written
	// operation that selects a deprecated field keeps its tool, and the warning is the
	// one place the team hears that the API has announced a removal.
	private static final String DEPRECATION_SCHEMA = """
			type Query {
			  "Returns the newest movies."
			  newest(first: Int, limit: Int @deprecated(reason: "Use first.")): [Movie!]!
			}

			type Movie {
			  id: ID!
			  title: String!
			  rating: Float @deprecated(reason: "Ratings moved to reviews.")
			}
			""";

	// A schema of its own, because the root field is what a tool is, and the movie
	// schema deprecates one root field, which it describes and gives a reason. Here a
	// deprecated root field comes described and undescribed, with a reason and without
	// one, on the query root and on the mutation root.
	private static final String DEPRECATED_ROOTS_SCHEMA = """
			type Query {
			  "Returns every movie."
			  allMovies: [Movie!]! @deprecated(reason: "Unbounded. Use movies.")
			  oldest: [Movie!]! @deprecated(reason: "Use movies.")
			  "Returns the first movie."
			  first: Movie @deprecated
			  "Returns a page of movies."
			  movies: [Movie!]!
			}

			type Mutation {
			  "Removes a movie."
			  removeMovie(id: ID!): Boolean @deprecated(reason: "Use archiveMovie.")
			}

			type Movie {
			  id: ID!
			  title: String!
			  rating: Float @deprecated(reason: "Ratings moved to reviews.")
			}
			""";

	private final OperationCatalogFactory deprecatedRoots = OperationCatalogFactory
		.builder(SdlSchemaFactory.schemaFrom(DEPRECATED_ROOTS_SCHEMA, "file:/schema/deprecated-roots.graphqls"),
				ToolNamingStrategy.camelCase())
		.build();

	// A schema of its own, because a literal can name a deprecated enum value or input
	// field in two places a field argument is not: the default of a variable, and the
	// argument of a directive the schema declares for operations. The directive takes
	// every location an operation file can write one at.
	private static final String DEPRECATED_VALUES_SCHEMA = """
			directive @audience(
			  status: Status
			  filter: Filter
			  legacy: Boolean @deprecated(reason: "Use status.")
			) on QUERY | FIELD | FRAGMENT_DEFINITION | FRAGMENT_SPREAD | INLINE_FRAGMENT | VARIABLE_DEFINITION

			type Query {
			  "Returns the newest movies."
			  newest(status: Status, filter: Filter, filters: [Filter!]): [Movie!]!
			}

			enum Status {
			  CURRENT
			  OLD @deprecated(reason: "Use CURRENT.")
			}

			input Filter {
			  title: String
			  status: Status
			  year: Int @deprecated(reason: "Use title.")
			}

			type Movie {
			  id: ID!
			  title: String!
			}
			""";

	private final OperationCatalogFactory deprecatedValues = OperationCatalogFactory
		.builder(SdlSchemaFactory.schemaFrom(DEPRECATED_VALUES_SCHEMA, "file:/schema/deprecated-values.graphqls"),
				ToolNamingStrategy.camelCase())
		.build();

	@Test
	void create_deprecatedEnumValueInAVariableDefault_shouldWarnAndKeepTheTool() {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql", """
				# Returns the newest movies.
				query Newest($status: Status = OLD) {
				  newest(status: $status) { id }
				}
				""", ToolExposureType.MCP)));

		// The default is the value the API applies whenever the model leaves the
		// variable out, so the file fails validation the day the API removes OLD.
		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(deprecationWarning(catalog)).contains("selects the value OLD of Status (Use CURRENT.), which");
	}

	@Test
	void create_deprecatedInputFieldInAVariableDefault_shouldWarn() {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql", """
				# Returns the newest movies.
				query Newest($filter: Filter = { year: 1999, status: OLD }, $filters: [Filter!] = [{ year: 2001 }]) {
				  newest(filter: $filter, filters: $filters) { id }
				}
				""", ToolExposureType.MCP)));

		// The walk goes into the object and the list the default writes, so the enum
		// value inside the object is named too, and the field written twice earns one
		// mention.
		assertThat(catalog.problems()).isEmpty();
		assertThat(deprecationWarning(catalog)).contains(
				"selects the input field year of Filter (Use title.) and the value OLD of Status (Use CURRENT.), which");
	}

	@Test
	void create_currentValuesInAVariableDefaultAndADirective_shouldStayQuiet() {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql", """
				# Returns the newest movies.
				query Newest($status: Status = CURRENT, $filter: Filter = { title: "Arrival" }) {
				  newest(status: $status, filter: $filter) @audience(status: CURRENT) { id }
				}
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
	}

	// One operation for each place a file can write a directive: the operation, a
	// variable definition, a field, an inline fragment, a fragment spread and a
	// fragment definition.
	@ParameterizedTest
	@ValueSource(strings = { "query Newest @audience(status: OLD) { newest { id } }",
			"query Newest($status: Status @audience(status: OLD)) { newest(status: $status) { id } }",
			"query Newest { newest { id @audience(status: OLD) } }",
			"query Newest { newest { ... on Movie @audience(status: OLD) { id } } }",
			"query Newest { newest { ...MovieFields @audience(status: OLD) } } fragment MovieFields on Movie { id }",
			"query Newest { newest { ...MovieFields } } fragment MovieFields on Movie @audience(status: OLD) { id }" })
	void create_deprecatedValueInADirectiveArgument_shouldWarnWhereverTheFileWritesTheDirective(String operation) {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql",
				"# Returns the newest movies.\n" + operation, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(deprecationWarning(catalog)).contains("selects the value OLD of Status (Use CURRENT.), which");
	}

	@Test
	void create_deprecatedInputFieldInADirectiveArgument_shouldWarn() {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql", """
				# Returns the newest movies.
				query Newest {
				  newest @audience(filter: { year: 1999 }) { id }
				}
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(deprecationWarning(catalog)).contains("selects the input field year of Filter (Use title.), which");
	}

	@Test
	void create_deprecatedArgumentOfADirective_shouldWarnNamingTheDirective() {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql", """
				# Returns the newest movies.
				query Newest {
				  newest @audience(legacy: true) { id }
				}
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(deprecationWarning(catalog))
			.contains("selects the argument legacy of @audience (Use status.), which");
	}

	@Test
	void create_deprecatedValueWrittenInAFieldArgumentADefaultAndADirective_shouldNameItOnce() {
		OperationCatalog catalog = this.deprecatedValues.create(List.of(sourceFor("file:/gatool/mcp/Newest.graphql", """
				# Returns the newest movies.
				query Newest($status: Status = OLD) {
				  newest(status: $status) { id }
				  older: newest(status: OLD) @audience(status: OLD) { id }
				}
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(deprecationWarning(catalog)).containsOnlyOnce("the value OLD of Status");
	}

	// The one warning about deprecation a catalog of one file holds, or an empty text
	// for a catalog without one, so an assertion on it fails showing the words missing.
	private static String deprecationWarning(OperationCatalog catalog) {
		return catalog.warnings()
			.stream()
			.map(OperationWarning::message)
			.filter((message) -> message.contains("which the schema marks deprecated"))
			.collect(Collectors.joining("\n"));
	}

	@Test
	void create_operationSelectingDeprecatedFieldAndArgument_shouldWarnAndKeepTheTool() {
		OperationCatalogFactory deprecations = OperationCatalogFactory
			.builder(SdlSchemaFactory.schemaFrom(DEPRECATION_SCHEMA, "file:/schema/deprecation.graphqls"),
					ToolNamingStrategy.camelCase())
			.build();

		OperationCatalog catalog = deprecations
			.create(List.of(new OperationSource("file:/gatool/mcp/Newest.graphql", """
					# Returns the newest movies.
					query Newest($limit: Int) {
					  newest(limit: $limit) {
					    id
					    rating
					  }
					}
					""", Set.of(ToolExposureType.MCP))));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.warnings()).anySatisfy((warning) -> assertThat(warning.message())
			.contains("the field Movie.rating (Ratings moved to reviews.)")
			.contains("the argument limit of newest (Use first.)")
			.contains("deprecated"));
	}

	@Test
	void create_deprecatedFieldUnderAConditionalDirective_shouldStillWarn() {
		OperationCatalogFactory deprecations = OperationCatalogFactory
			.builder(SdlSchemaFactory.schemaFrom(DEPRECATION_SCHEMA, "file:/schema/deprecation.graphqls"),
					ToolNamingStrategy.camelCase())
			.build();

		OperationCatalog catalog = deprecations
			.create(List.of(new OperationSource("file:/gatool/mcp/Newest.graphql", """
					# Returns the newest movies.
					query Newest($withRating: Boolean! = false) {
					  newest {
					    id
					    rating @include(if: $withRating)
					  }
					}
					""", Set.of(ToolExposureType.MCP))));

		// The variable is the model's to send at call time, so the walk visits the field
		// whatever the model decides. The walked copy has the directives stripped,
		// because the traverser throws a NullPointerException on this fixture as written.
		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings())
			.anySatisfy((warning) -> assertThat(warning.message()).contains("the field Movie.rating"));
	}

	@Test
	void create_fileSelectingADeprecatedRootFieldUnderItsOwnComment_shouldEndTheDescriptionWithTheReason() {
		OperationCatalog catalog = this.deprecatedRoots.create(List.of(sourceFor("file:/mcp/AllMovies.graphql", """
				# Lists every movie of the catalog.
				query AllMovies { allMovies { id } }
				""", ToolExposureType.MCP)));

		// The warning goes to the log, which the team reads, and the description is what
		// the model reads, so the description says that the API is removing the field the
		// tool calls.
		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description())
				.isEqualTo("Lists every movie of the catalog. Deprecated: Unbounded. Use movies."));
		assertThat(deprecationWarning(catalog))
			.contains("selects the field Query.allMovies (Unbounded. Use movies.), which");
	}

	@Test
	void create_fileSelectingADeprecatedRootFieldWithoutAComment_shouldDescribeItAsTheGeneratedToolIs() {
		OperationCatalog written = this.deprecatedRoots.create(List.of(sourceFor("file:/mcp/AllMovies.graphql",
				"query AllMovies { allMovies { id } }", ToolExposureType.MCP)));
		OperationCatalog generated = OperationCatalogFactory
			.builder(SdlSchemaFactory.schemaFrom(DEPRECATED_ROOTS_SCHEMA, "file:/schema/deprecated-roots.graphqls"),
					ToolNamingStrategy.camelCase())
			.generation(new OperationCatalogFactory.Generation(true, false, true, true, 1))
			.build()
			.create(List.of());

		assertThat(written.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description())
				.isEqualTo("Returns every movie. Deprecated: Unbounded. Use movies."));
		// The generated tool carries the sentence once, from the comment the generator
		// writes, and the two paths describe the root field in the same words.
		assertThat(generated.tools()).filteredOn((tool) -> tool.toolName().equals("allMovies"))
			.singleElement()
			.satisfies((tool) -> assertThat(tool.description())
				.isEqualTo("Returns every movie. Deprecated: Unbounded. Use movies."));
	}

	@Test
	void create_fileSelectingADeprecatedRootFieldTheSchemaLeavesUndescribed_shouldPublishTheReasonAndStillWarn() {
		OperationCatalog catalog = this.deprecatedRoots.create(
				List.of(sourceFor("file:/mcp/Oldest.graphql", "query Oldest { oldest { id } }", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description()).isEqualTo("Deprecated: Use movies."));
		// The reason says what the API is removing and leaves unsaid what the tool
		// returns, so the file is still asked for its own words.
		assertThat(catalog.warnings()).extracting(OperationWarning::message)
			.anySatisfy((message) -> assertThat(message).startsWith("lacks a description; write # lines"));
	}

	@Test
	void create_fileSelectingARootFieldDeprecatedWithoutAReason_shouldPublishTheReasonGraphQlGivesIt() {
		OperationCatalog catalog = this.deprecatedRoots.create(
				List.of(sourceFor("file:/mcp/First.graphql", "query First { first { id } }", ToolExposureType.MCP)));

		// graphql-java fills in the reason the specification gives the directive.
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description())
				.isEqualTo("Returns the first movie. Deprecated: No longer supported"));
	}

	@Test
	void create_mutationFileSelectingADeprecatedRootField_shouldEndTheDescriptionWithTheReason() {
		OperationCatalog catalog = this.deprecatedRoots.create(List.of(sourceFor("file:/mcp/RemoveMovie.graphql", """
				# Removes one movie from the catalog.
				mutation RemoveMovie($id: ID!) { removeMovie(id: $id) }
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description())
				.isEqualTo("Removes one movie from the catalog. Deprecated: Use archiveMovie."));
	}

	@Test
	void create_fileWhoseCommentSpeaksOfTheDeprecationItself_shouldStillEndWithTheReasonOfTheSchema() {
		OperationCatalog catalog = this.deprecatedRoots.create(List.of(sourceFor("file:/mcp/AllMovies.graphql", """
				# Lists every movie. Deprecated, prefer the paged tool.
				query AllMovies { allMovies { id } }
				""", ToolExposureType.MCP)));

		// The comment is the file's wording and the reason is the API's, and the model
		// reads both.
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description()).isEqualTo(
					"Lists every movie. Deprecated, prefer the paged tool. " + "Deprecated: Unbounded. Use movies."));
	}

	@Test
	void create_fileSelectingADeprecatedFieldUnderACurrentRootField_shouldLeaveTheDescriptionAsWritten() {
		OperationCatalog catalog = this.deprecatedRoots.create(List.of(sourceFor("file:/mcp/Movies.graphql", """
				# Lists a page of movies with their ratings.
				query Movies { movies { id rating } }
				""", ToolExposureType.MCP)));

		// The deprecated field is a part of the result, so the team hears of it at
		// startup and the description of the tool stays as the file wrote it.
		assertThat(catalog.tools()).singleElement()
			.satisfies(
					(tool) -> assertThat(tool.description()).isEqualTo("Lists a page of movies with their ratings."));
		assertThat(deprecationWarning(catalog))
			.contains("selects the field Movie.rating (Ratings moved to reviews.), which");
	}

	@Test
	void create_fileSelectingADeprecatedRootFieldBesideACurrentOne_shouldLeaveTheDescriptionAsWritten() {
		OperationCatalog catalog = this.deprecatedRoots.create(List.of(sourceFor("file:/mcp/Both.graphql", """
				# Lists every movie beside the first page.
				query Both { allMovies { id } movies { id } }
				""", ToolExposureType.MCP)));

		// Two root fields make a tool that is more than the deprecated one, so the
		// sentence would say of the whole tool what holds for a part of it.
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description()).isEqualTo("Lists every movie beside the first page."));
		assertThat(deprecationWarning(catalog))
			.contains("selects the field Query.allMovies (Unbounded. Use movies.), which");
	}

	@Test
	void create_oneQueryFile_shouldCreateOneQueryToolNamedInCamelCase() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
		assertThat(catalog.toolsFor(ToolExposureType.MCP)).singleElement().satisfies((tool) -> {
			assertThat(tool.toolName()).isEqualTo("topRatedMovies");
			assertThat(tool.operationName()).isEqualTo("TopRatedMovies");
			assertThat(tool.operationType()).isEqualTo(OperationType.QUERY);
			assertThat(tool.description()).isEqualTo("Returns the highest-rated movies, best first.");
			// The document and the input schema are both String, so each one is asserted
			// on its own, and a swapped pair of constructor arguments fails both.
			assertThat(tool.printedDocument()).startsWith("query TopRatedMovies");
			assertThat(tool.inputSchema()).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"first\":"
					+ "{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}],"
					+ "\"description\":\"How many movies to return.\",\"default\":10}},"
					+ "\"required\":[],\"additionalProperties\":false}");
			assertThat(tool.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
		});
		assertThat(catalog.toolsFor(ToolExposureType.IN_PROCESS)).isEmpty();
	}

	@Test
	void create_fileWhoseCommentRendersEmptyAboveTwoRootFields_shouldWarnThatTheToolLacksADescription() {
		// A comment of a no-break space renders empty, so it counts as absent. The
		// warning is the one a file without a comment earns.
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/MoviesAndPeople.graphql",
				"# \u00A0\nquery MoviesAndPeople {\n  topRatedMovies { title }\n"
						+ "  search(text: \"no\") { __typename }\n}\n",
				ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> assertThat(tool.description()).isNull());
		assertThat(catalog.warnings()).singleElement().satisfies((warning) -> {
			assertThat(warning.location()).isEqualTo("file:/mcp/MoviesAndPeople.graphql");
			assertThat(warning.message())
				.startsWith("lacks a description; write # lines directly above the " + "operation");
		});
	}

	@Test
	void create_fileWhoseCommentRendersEmptyAboveOneRootField_shouldDescribeTheToolFromTheSchema() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql",
				"# \u200B\nquery TopRatedMovies {\n  topRatedMovies { title }\n}\n", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.description())
				.isEqualTo("Returns the highest-rated movies, best first."));
	}

	@Test
	void create_problemsInSeveralFiles_shouldReportEveryProblem() {
		OperationCatalog catalog = this.builder.create(List.of(
				sourceFor("file:/mcp/Broken.graphql", "query Broken { topRatedMovies { title", ToolExposureType.MCP),
				sourceFor("file:/mcp/Unknown.graphql", "query Unknown { tagline }", ToolExposureType.MCP)));

		assertThat(catalog.hasProblems()).isTrue();
		assertThat(catalog.problems()).extracting(OperationProblem::location)
			.containsExactly("file:/mcp/Broken.graphql", "file:/mcp/Unknown.graphql");
	}

	@Test
	void create_anonymousOperationFile_shouldNameTheToolAfterTheFile() {
		OperationCatalog catalog = this.builder.create(List
			.of(sourceFor("file:/mcp/TopRatedMovies.graphql", "{ topRatedMovies { title } }", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.toolsFor(ToolExposureType.MCP)).extracting(ToolOperation::toolName)
			.containsExactly("topRatedMovies");
	}

	@Test
	void create_sameToolNameTwiceOnOneExposureType_shouldReportClashAndNameBothFiles() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP),
					sourceFor("file:/mcp/copy/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			// The message names the tools to look at, so it says "among the MCP
			// tools" instead of GATool's own word for the pair of exposure types.
			assertThat(problem.message()).contains("'topRatedMovies'", "among the MCP tools",
					"file:/mcp/copy/TopRatedMovies.graphql");
		});
	}

	@Test
	void create_sameToolNameOnDifferentExposureTypes_shouldCreateBothTools() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP),
					sourceFor("file:/tools/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.IN_PROCESS)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.toolsFor(ToolExposureType.MCP)).extracting(ToolOperation::location)
			.containsExactly("file:/mcp/TopRatedMovies.graphql");
		assertThat(catalog.toolsFor(ToolExposureType.IN_PROCESS)).extracting(ToolOperation::location)
			.containsExactly("file:/tools/TopRatedMovies.graphql");
	}

	@Test
	void create_mutationFile_shouldBecomeAMutationTool() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/AddReview.graphql", ADD_REVIEW, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.toolsFor(ToolExposureType.MCP)).singleElement().satisfies((tool) -> {
			assertThat(tool.toolName()).isEqualTo("addReview");
			// The operation type is what the MCP adapter turns into readOnlyHint,
			// destructiveHint and idempotentHint, so a write tool announces itself.
			assertThat(tool.operationType()).isEqualTo(OperationType.MUTATION);
		});
	}

	@Test
	void create_mutationAndQueryFiles_shouldCreateBothTools() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/AddReview.graphql", ADD_REVIEW, ToolExposureType.MCP),
					sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.toolsFor(ToolExposureType.MCP)).extracting(ToolOperation::toolName)
			.containsExactlyInAnyOrder("addReview", "topRatedMovies");
	}

	@Test
	void create_unusedSharedFragment_shouldCreateZeroToolsAndWarnNamingTheFile() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/fragments/MovieCard.graphql",
				"fragment MovieCard on Movie { id title }", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).isEmpty();
		// Such a file parses cleanly and yields zero tools, so an operations folder that
		// holds fragments alone would otherwise read a tool count one short and an empty
		// log.
		assertThat(catalog.warnings()).singleElement().satisfies((warning) -> {
			assertThat(warning.location()).isEqualTo("file:/mcp/fragments/MovieCard.graphql");
			assertThat(warning.message()).contains("MovieCard", "zero operation files spread");
		});
	}

	@Test
	void create_unspreadSharedFragmentSelectingAnUnknownField_shouldReportTheProblemNamingTheFragmentFile() {
		// A fragment is validated inside the operation that spreads it, so a fragment
		// file left unspread is validated on its own, and a wrong field in it is a
		// problem beside the zero-spreads warning.
		OperationCatalog catalog = this.builder.create(List
			.of(sourceFor("file:/mcp/fragments/Bad.graphql", "fragment Bad on Movie { nope }", ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/fragments/Bad.graphql");
			assertThat(problem.message()).contains("nope", "(line 1, column 25)");
		});
	}

	@Test
	void create_unspreadSharedFragmentSpreadingAnotherSharedOne_shouldResolveTheSpreadAndWarnAlone() {
		// The check sees the other shared fragments on the same side, so a spread of one
		// of them resolves, and the two files earn the zero-spreads warning alone.
		OperationCatalog catalog = this.builder.create(List.of(
				sourceFor("file:/mcp/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id ...Titles }",
						ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/Titles.graphql", "fragment Titles on Movie { title }",
						ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).hasSize(2)
			.allSatisfy((warning) -> assertThat(warning.message()).contains("zero operation files spread"));
	}

	@Test
	void create_unspreadSharedFragmentSpreadingAFragmentOfTheOtherSide_shouldReportTheUndefinedSpread() {
		// A shared fragment on the other side stays out of reach for an operation, and
		// the check reads the fragment file the same way.
		OperationCatalog catalog = this.builder.create(List.of(
				sourceFor("file:/mcp/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id ...Titles }",
						ToolExposureType.MCP),
				sourceFor("file:/in-process/fragments/Titles.graphql", "fragment Titles on Movie { title }",
						ToolExposureType.IN_PROCESS)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/fragments/MovieCard.graphql");
			assertThat(problem.message()).contains("Titles");
		});
	}

	@Test
	void create_sharedFragment_shouldJoinTheDocumentOfTheOperationThatSpreadsIt() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""", ToolExposureType.MCP), sourceFor("file:/mcp/fragments/MovieCard.graphql",
				"fragment MovieCard on Movie { id title }", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> {
			// The executor sends the assembled text, so the fragment has to be inside it.
			assertThat(tool.printedDocument()).contains("...MovieCard", "fragment MovieCard on Movie");
			assertThat(tool.sharedFragmentFiles()).containsExactly("file:/mcp/fragments/MovieCard.graphql");
		});
	}

	@Test
	void create_sharedFragmentSpreadingAnother_shouldAppendInFirstSpreadOrderDepthFirst() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard ...Credits } }
				""", ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/Credits.graphql", "fragment Credits on Movie { directors { name } }",
						ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id ...Titles }",
						ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/Titles.graphql", "fragment Titles on Movie { title }",
						ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		String document = catalog.tools().getFirst().printedDocument();
		// MovieCard is spread first and Titles is what it spreads, so Titles sits behind
		// MovieCard and ahead of Credits, whatever order the files were read in.
		assertThat(document.indexOf("fragment MovieCard")).isLessThan(document.indexOf("fragment Titles"));
		assertThat(document.indexOf("fragment Titles")).isLessThan(document.indexOf("fragment Credits"));
		assertThat(catalog.tools().getFirst().sharedFragmentFiles()).containsExactly(
				"file:/mcp/fragments/MovieCard.graphql", "file:/mcp/fragments/Titles.graphql",
				"file:/mcp/fragments/Credits.graphql");
	}

	@Test
	void create_ownFragmentShadowingASharedOne_shouldKeepTheFileOwnAndWarnNamingBoth() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				fragment MovieCard on Movie { releaseYear }
				""", ToolExposureType.MCP), sourceFor("file:/mcp/fragments/MovieCard.graphql",
				"fragment MovieCard on Movie { id title }", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.printedDocument()).contains("releaseYear").doesNotContain("title");
			assertThat(tool.sharedFragmentFiles()).isEmpty();
		});
		assertThat(catalog.warnings()).satisfiesExactlyInAnyOrder((warning) -> {
			assertThat(warning.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			assertThat(warning.message()).contains("MovieCard", "file:/mcp/fragments/MovieCard.graphql",
					"keeps its own");
		}, (warning) -> {
			assertThat(warning.location()).isEqualTo("file:/mcp/fragments/MovieCard.graphql");
			assertThat(warning.message()).contains("zero operation files spread");
		});
	}

	@Test
	void create_twoSharedFilesDefiningOneName_shouldReportProblemNamingBoth() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""", ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id title }",
						ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/Cards.graphql", "fragment MovieCard on Movie { id }",
						ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/fragments/Cards.graphql");
			assertThat(problem.message()).contains("MovieCard", "file:/mcp/fragments/MovieCard.graphql");
		});
	}

	@Test
	void create_sharedFragmentOnTheOtherSide_shouldStayOutOfReach() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""", ToolExposureType.MCP), sourceFor("file:/tools/fragments/MovieCard.graphql",
				"fragment MovieCard on Movie { id title }", ToolExposureType.IN_PROCESS)));

		// A spread resolves to a shared fragment file on the same side, so the MCP
		// operation fails validation the way it would without the file.
		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			assertThat(problem.message()).contains("MovieCard");
		});
		assertThat(catalog.tools()).isEmpty();
	}

	@Test
	void create_fileOnBothSidesWithTwoDefinitionsOfANameItLeavesUnspread_shouldReportNoProblem() {
		OperationCatalog catalog = this.builder
			.create(List.of(new OperationSource("file:/shared/TopRatedMovies.graphql", """
					query TopRatedMovies { topRatedMovies { id } }
					""", Set.of(ToolExposureType.MCP, ToolExposureType.IN_PROCESS)),
					sourceFor("file:/mcp/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id title }",
							ToolExposureType.MCP),
					sourceFor("file:/tools/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id }",
							ToolExposureType.IN_PROCESS)));

		// The two sides disagree about MovieCard, and this file leaves it unspread, so
		// the disagreement is a problem for a file that spreads it.
		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
	}

	@Test
	void create_fileOnBothSidesSpreadingANameTheSidesDefineDifferently_shouldReportProblemNamingBoth() {
		OperationCatalog catalog = this.builder
			.create(List.of(new OperationSource("file:/shared/TopRatedMovies.graphql", """
					query TopRatedMovies { topRatedMovies { ...MovieCard } }
					""", Set.of(ToolExposureType.MCP, ToolExposureType.IN_PROCESS)),
					sourceFor("file:/mcp/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id title }",
							ToolExposureType.MCP),
					sourceFor("file:/tools/fragments/MovieCard.graphql", "fragment MovieCard on Movie { id }",
							ToolExposureType.IN_PROCESS)));

		assertThat(catalog.problems()).anySatisfy((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/shared/TopRatedMovies.graphql");
			assertThat(problem.message()).contains("both exposure types", "file:/mcp/fragments/MovieCard.graphql",
					"file:/tools/fragments/MovieCard.graphql");
		});
	}

	@Test
	void create_sharedFragmentSelectingAnUnknownField_shouldNameTheFragmentFileBesideTheLine() {
		// The fragment keeps the nodes its own file parsed when it joins the operation's
		// document, so the line number is the fragment file's, and the problem names that
		// file beside it.
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""", ToolExposureType.MCP), sourceFor("file:/mcp/fragments/MovieCard.graphql", """
				fragment MovieCard on Movie {
				  id
				  tagline
				}
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			assertThat(problem.message()).startsWith("fails validation against the schema:")
				.contains("tagline")
				.contains("(line 3, column 3 of the shared fragment file file:/mcp/fragments/MovieCard.graphql)");
		});
	}

	@Test
	void create_deferInsideASharedFragmentFile_shouldRefuseItLikeAnOperationFileAndPublishNoTool() {
		// graphql-java declares @defer in every schema it builds, so validation alone
		// would let this document carry @defer to the API. The fragment file gets the
		// refusal an operation file gets, and the operation that spreads it fails
		// validation on the fragment it cannot find.
		OperationCatalog catalog = this.builder.create(List.of(
				sourceFor("file:/mcp/TopRated.graphql", "query TopRated { topRatedMovies { title ...Extra } }",
						ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/Extra.graphql", "fragment Extra on Movie { ... @defer { rating } }",
						ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).anySatisfy((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/fragments/Extra.graphql");
			assertThat(problem.message()).startsWith("uses @defer");
		});
	}

	@Test
	void create_streamInsideASharedFragmentFile_shouldRefuseItNamingTheFragmentFile() {
		// graphql-java leaves @stream undeclared, so validation alone would refuse this
		// one with "Unknown directive 'stream'" under the operation file's name. The
		// parser's own sentence names the fragment file and says why the directive cannot
		// stay.
		OperationCatalog catalog = this.builder.create(List.of(
				sourceFor("file:/mcp/TopRated.graphql", "query TopRated { topRatedMovies { title ...Credits } }",
						ToolExposureType.MCP),
				sourceFor("file:/mcp/fragments/Credits.graphql",
						"fragment Credits on Movie { directors @stream(initialCount: 1) { name } }",
						ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).anySatisfy((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/fragments/Credits.graphql");
			assertThat(problem.message()).startsWith("uses @stream");
		});
	}

	@Test
	void create_deferInsideTheOperationFileItself_shouldRefuseIt() {
		// The control: the same directive written in the operation file.
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRated.graphql",
				"query TopRated { topRatedMovies { title ... @defer { rating } } }", ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("uses @defer"));
	}

	@Test
	void create_spreadWithoutAnyDefinition_shouldReportProblem() {
		OperationCatalog catalog = this.builder.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", """
				query TopRatedMovies { topRatedMovies { ...MovieCard } }
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			assertThat(problem.message()).contains("MovieCard");
		});
	}

	@Test
	void create_strategyReturningNonPortableName_shouldReportProblem() {
		OperationCatalogFactory spacedNames = OperationCatalogFactory
			.builder(TestSchemas.movies(), (graphQlName) -> "top rated movies")
			.build();

		OperationCatalog catalog = spacedNames
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("'top rated movies'"));
	}

	@Test
	void create_strategyThrowingSomethingOtherThanIllegalArgument_shouldReportProblemNamingTheStrategy() {
		// IllegalArgumentException is the documented refusal, and anything else is a
		// fault in the strategy, so the problem names the file and the class.
		OperationCatalogFactory failing = OperationCatalogFactory.builder(TestSchemas.movies(), new FailingStrategy())
			.build();

		OperationCatalog catalog = failing
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			assertThat(problem.message()).startsWith("cannot be named as a tool, because the naming strategy ")
				.contains(FailingStrategy.class.getName(), "'TopRatedMovies'", "IllegalStateException",
						"the registry is down");
		});
	}

	@Test
	void create_strategyReturningNull_shouldReportProblemNamingTheStrategy() {
		OperationCatalogFactory nulls = OperationCatalogFactory.builder(TestSchemas.movies(), new NullStrategy())
			.build();

		OperationCatalog catalog = nulls
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("file:/mcp/TopRatedMovies.graphql");
			assertThat(problem.message()).contains(NullStrategy.class.getName(), "returned null", "'TopRatedMovies'");
		});
	}

	@Test
	void create_anonymousOperationInAHyphenatedFile_shouldNameTheToolTheWayAWrittenOperationIs() {
		// The file name yields the operation name top_rated_movies, and each strategy
		// reads its underscores as the word breaks they are: the two case-changing
		// strategies give the name the written operation TopRatedMovies gets, and the
		// as-written one keeps the file's own lower case.
		assertThat(anonymousToolName(ToolNamingStrategy.camelCase())).isEqualTo("topRatedMovies");
		assertThat(anonymousToolName(ToolNamingStrategy.snakeCase())).isEqualTo("top_rated_movies");
		assertThat(anonymousToolName(ToolNamingStrategy.asWritten())).isEqualTo("top_rated_movies");
	}

	private static String anonymousToolName(ToolNamingStrategy strategy) {
		OperationCatalog catalog = OperationCatalogFactory.builder(TestSchemas.movies(), strategy)
			.build()
			.create(List.of(sourceFor("file:/mcp/top-rated-movies.graphql", "{ topRatedMovies { title } }",
					ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		return catalog.tools().getFirst().toolName();
	}

	@Test
	void create_toolsUsingAnUnmappedScalar_shouldWarnOnceNamingTheScalarAndEveryTool() {
		OperationCatalogFactory countries = OperationCatalogFactory
			.builder(SdlSchemaFactory.schemaFrom(COUNTRY_SCHEMA, "file:/schema/countries.graphqls"),
					ToolNamingStrategy.camelCase())
			.build();

		OperationCatalog catalog = countries.create(List.of(sourceFor("file:/mcp/MoviesFrom.graphql",
				"query MoviesFrom($country: CountryCode) { moviesFrom(country: $country) }", ToolExposureType.MCP),
				sourceFor("file:/mcp/MoviesMadeIn.graphql",
						"query MoviesMadeIn($country: CountryCode) { moviesFrom(country: $country) }",
						ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).singleElement().satisfies((warning) -> {
			assertThat(warning.location()).isEqualTo(OperationWarning.THE_SCHEMA);
			assertThat(warning.message()).contains("CountryCode", "moviesFrom", "moviesMadeIn");
		});
	}

	@Test
	void create_aScalarWithAnInputFragment_shouldPublishItsTypeInBothSchemasAndItsEnumInTheInputAlone() {
		// One tool says the same about the scalar on the way in and on the way out. The
		// enum stays on the input side, because it lists what a model should send and the
		// API may return a value outside it.
		ToolOperation tool = priceTool(Map.of("Currency", Map.of("type", "string", "enum", List.of("EUR", "USD"))),
				Map.of());

		assertThat(tool.inputSchema()).contains(
				"\"currency\":{\"anyOf\":[{\"type\":\"string\",\"enum\":[\"EUR\",\"USD\"]}," + "{\"type\":\"null\"}]}");
		assertThat(tool.outputSchema()).contains("\"currency\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}")
			.doesNotContain("\"enum\"");
	}

	@Test
	void create_anOverrideInTheResultsMap_shouldWinInTheOutputSchemaAndLeaveTheInputSchemaToTheInputFragment() {
		// The scalar is accepted as a number and returned as a quoted string, which is
		// the case the results map exists for.
		ToolOperation tool = priceTool(Map.of("Money", Map.of("type", "number")),
				Map.of("Money", Map.of("type", "string", "pattern", "^[0-9]+\\.[0-9]{2}$")));

		assertThat(tool.inputSchema()).contains("\"atLeast\":{\"anyOf\":[{\"type\":\"number\"},{\"type\":\"null\"}]}");
		assertThat(tool.outputSchema())
			.contains("\"amount\":{\"anyOf\":[{\"type\":\"string\",\"pattern\":\"^[0-9]+\\\\.[0-9]{2}$\"},"
					+ "{\"type\":\"null\"}]}");
	}

	@Test
	void create_anOverrideForAScalarWithoutAnInputFragment_shouldReachTheOutputSchemaAlone() {
		ToolOperation tool = priceTool(Map.of(), Map.of("Money", Map.of("type", "string")));

		assertThat(tool.inputSchema()).contains("\"atLeast\":{}");
		assertThat(tool.outputSchema()).contains("\"amount\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
	}

	@Test
	void create_aRefusedKeywordInTheResultsMap_shouldBeAProblemNamingTheResultsProperty() {
		OperationCatalog catalog = pricesWith(true, Map.of("Money", Map.of("type", "number")),
				Map.of("Money", Map.of("type", "string", "minLength", 4)))
			.create(List.of(sourceFor("file:/mcp/Price.graphql", PRICE, ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo("The property gatool.results.scalar-schemas.Money");
			assertThat(problem.message()).startsWith("carries the keyword 'minLength'");
		});
	}

	@Test
	void create_aRefusedKeywordInTheResultsMapWhileOutputSchemasAreOff_shouldStillBeAProblem() {
		// A file can turn its own output schema on with @gatool(outputSchema: true), so
		// the map is checked whatever the default says, the way the input map is checked
		// whether or not an operation uses the scalar.
		OperationCatalog catalog = pricesWith(false, Map.of(),
				Map.of("Money", Map.of("type", "string", "minLength", 4)))
			.create(List.of(sourceFor("file:/mcp/Price.graphql", PRICE, ToolExposureType.MCP)));

		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.location())
				.isEqualTo("The property gatool.results.scalar-schemas.Money"));
	}

	private static ToolOperation priceTool(Map<String, ?> inputs, Map<String, ?> results) {
		OperationCatalog catalog = pricesWith(true, inputs, results)
			.create(List.of(sourceFor("file:/mcp/Price.graphql", PRICE, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		return catalog.tools().getFirst();
	}

	private static OperationCatalogFactory pricesWith(boolean outputSchemaByDefault, Map<String, ?> inputs,
			Map<String, ?> results) {
		return OperationCatalogFactory
			.builder(SdlSchemaFactory.schemaFrom(PRICES_SCHEMA, "file:/schema/prices.graphqls"),
					ToolNamingStrategy.camelCase())
			.outputSchemaByDefault(outputSchemaByDefault)
			.scalarSchemas(inputs)
			.resultScalarSchemas(results)
			.build();
	}

	@Test
	void create_toolCostingMoreThanFourThousandTokens_shouldWarnNamingTheFileTheToolAndTheCost() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Wordy.graphql", WORDY, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.warnings()).singleElement().satisfies((warning) -> {
			assertThat(warning.location()).isEqualTo("file:/mcp/Wordy.graphql");
			assertThat(warning.message()).matches("becomes the tool wordy and costs about \\d+ tokens as an MCP "
					+ "tool, which is a large share of a model's context before any call\\. Narrow the variables "
					+ "it declares, or split the operation\\.");
			// The startup listing of the MCP tools prints this estimate for the tool, so
			// the warning and that listing name one cost.
			assertThat(warning.message()).contains(
					"costs about " + catalog.tools().getFirst().estimatedTokens(ToolExposureType.MCP, 0) + " tokens");
		});
	}

	@Test
	void create_largeToolServedInProcessAlone_shouldWarnWithTheCostOfTheInProcessTool() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/in-process/Wordy.graphql", WORDY, ToolExposureType.IN_PROCESS)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).startsWith("becomes the tool wordy and costs about "
					+ catalog.tools().getFirst().estimatedTokens(ToolExposureType.IN_PROCESS, 0)
					+ " tokens as an in-process tool, "));
	}

	@Test
	void create_adapterThatAddsToEachSchema_shouldCountTheAdditionForItsExposureTypeAlone() {
		// The MCP adapter names each schema with a keyword of 98 characters, and states
		// that size to the factory. A tool one character short of the warning without
		// the keyword passes it with the keyword, and the same tool served in process
		// stays below, because the in-process adapter sends the writer's text.
		String description = "x".repeat(16_001 - "justBelow".length() - emptyInputSchema().length());
		String file = "# " + description + "\nquery JustBelow { topRatedMovies { id } }\n";
		OperationCatalogFactory counting = OperationCatalogFactory
			.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
			.schemaAdditions(List.of(new SchemaAddition(ToolExposureType.MCP, 98)))
			.build();

		OperationCatalog overMcp = counting
			.create(List.of(sourceFor("file:/mcp/JustBelow.graphql", file, ToolExposureType.MCP)));
		OperationCatalog inProcess = counting
			.create(List.of(sourceFor("file:/in-process/JustBelow.graphql", file, ToolExposureType.IN_PROCESS)));
		OperationCatalog withoutTheAddition = this.builder
			.create(List.of(sourceFor("file:/mcp/JustBelow.graphql", file, ToolExposureType.MCP)));

		assertThat(overMcp.tools().getFirst().estimatedTokens(ToolExposureType.MCP, 0)).isEqualTo(4000);
		assertThat(overMcp.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).contains("costs about 4024 tokens as an MCP tool"));
		assertThat(inProcess.warnings()).isEmpty();
		assertThat(withoutTheAddition.warnings()).isEmpty();
	}

	@Test
	void estimatedTokens_toolWithATitleAndAnOutputSchema_shouldCountBothOverMcpAndNeitherInProcess() {
		OperationCatalog catalog = publishingOutputSchemas()
			.create(List.of(new OperationSource("file:/shared/TopRated.graphql", """
					# Returns the highest-rated movies.
					query TopRated($first: Int = 3) @gatool(title: "Top rated movies") {
					  topRatedMovies(first: $first) { id title }
					}
					""", Set.of(ToolExposureType.MCP, ToolExposureType.IN_PROCESS))));

		assertThat(catalog.problems()).isEmpty();
		ToolOperation tool = catalog.tools().getFirst();
		String outputSchema = tool.outputSchema();
		assertThat(outputSchema).isNotNull();
		int sentInProcess = "topRated".length() + "Returns the highest-rated movies.".length()
				+ tool.inputSchema().length();
		assertThat(tool.estimatedTokens(ToolExposureType.IN_PROCESS, 0)).isEqualTo(sentInProcess / 4);
		assertThat(tool.estimatedTokens(ToolExposureType.MCP, 0))
			.isEqualTo((sentInProcess + "Top rated movies".length() + outputSchema.length()) / 4);
		// The addition is counted once for each schema the exposure type publishes.
		assertThat(tool.estimatedTokens(ToolExposureType.MCP, 98))
			.isEqualTo((sentInProcess + "Top rated movies".length() + outputSchema.length() + 2 * 98) / 4);
		assertThat(tool.estimatedTokens(ToolExposureType.IN_PROCESS, 98)).isEqualTo((sentInProcess + 98) / 4);
	}

	// The input schema of an operation without variables, whose length the test above
	// subtracts to land on a chosen estimate.
	private String emptyInputSchema() {
		return this.builder
			.create(List.of(sourceFor("file:/mcp/NoVariables.graphql",
					"# Lists.\nquery NoVariables { topRatedMovies { id } }\n", ToolExposureType.MCP)))
			.tools()
			.getFirst()
			.inputSchema();
	}

	@Test
	void create_largeToolServedOnBothExposureTypes_shouldWarnOnce() {
		// One file that both exposure types reach is one tool with one remedy, so it
		// earns one warning.
		OperationCatalog catalog = this.builder.create(List.of(new OperationSource("file:/shared/Wordy.graphql", WORDY,
				Set.of(ToolExposureType.MCP, ToolExposureType.IN_PROCESS))));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).contains("costs about"));
	}

	@Test
	void create_toolWhoseOutputSchemaTakesItPastFourThousandTokens_shouldWarnNamingTheOutputSchema() {
		// Four hundred aliases of one field make an output schema of about five and a
		// half thousand tokens, under a name, a description and an input schema of about
		// forty. The MCP tool list carries that schema, so the estimate counts it.
		OperationCatalog catalog = publishingOutputSchemas()
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", WIDE, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).singleElement().satisfies((warning) -> {
			assertThat(warning.location()).isEqualTo("file:/mcp/Wide.graphql");
			assertThat(warning.message()).matches("becomes the tool wide and costs about \\d+ tokens as an MCP "
					+ "tool, which is a large share of a model's context before any call\\. Its output schema is "
					+ "about \\d+ of them\\. Select fewer fields, narrow the variables it declares, or split the "
					+ "operation, and @gatool\\(outputSchema: false\\) leaves the output schema out of this "
					+ "tool\\.");
		});
	}

	@Test
	void create_toolWithALargeOutputSchemaServedInProcessAlone_shouldStayQuietAboutItsCost() {
		// A model provider reached in process is sent the name, the description and the
		// input schema, so the output schema stays out of what such a tool costs.
		OperationCatalog catalog = publishingOutputSchemas()
			.create(List.of(sourceFor("file:/in-process/Wide.graphql", WIDE, ToolExposureType.IN_PROCESS)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.warnings()).isEmpty();
	}

	private static OperationCatalogFactory publishingOutputSchemas() {
		return OperationCatalogFactory.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
			.outputSchemaByDefault(true)
			.build();
	}

	@Test
	void create_toolBelowFourThousandTokens_shouldStayQuietAboutItsCost() {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/TopRatedMovies.graphql", TOP_RATED_MOVIES, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		assertThat(catalog.warnings()).isEmpty();
	}

	@Test
	void create_variableTheDocumentGivesADefault_shouldProtectItFromNull() {
		assertThat(protectedVariables(this.builder, TOP_RATED_MOVIES)).containsExactly("first");
	}

	@Test
	void create_variableFillingAnArgumentTheSchemaGivesADefault_shouldProtectItFromNull() {
		// The default reaches the variable through the schema. A model in strict mode
		// sends null for a page size it would leave out, and sent through, the null would
		// replace the 10 the schema promises: the resolver would see null where an
		// omitted argument sees 10. The call would succeed, so the model could not tell
		// that it asked a different question than the file describes.
		assertThat(protectedVariables(this.builder, """
				query Newest($first: Int) { topRatedMovies(first: $first) { id } }
				""")).containsExactly("first");
	}

	@Test
	void create_variableFillingANonNullArgumentTheSchemaGivesADefault_shouldProtectItFromNull() {
		// 5.8.5 lets a nullable variable fill a non-null location when that location
		// declares a default, so this operation validates. Sending the null through then
		// fails coercion, and the model reads
		// "Argument 'first' has coerced Null value for NonNull type 'Int!'".
		assertThat(protectedVariables(this.defaults, """
				query Newest($first: Int) { newest(first: $first) }
				""")).containsExactly("first");
	}

	@Test
	void create_variableFillingAnInputFieldTheSchemaGivesADefault_shouldProtectItFromNull() {
		// An input field is where the value lands, so its default is the one that waits
		// for this variable, even though the argument above it does not declare one.
		assertThat(protectedVariables(this.defaults, """
				query Paged($first: Int) { paged(paging: { first: $first }) }
				""")).containsExactly("first");
	}

	@Test
	void create_variableFillingAnArgumentWithNoDefault_shouldKeepItsNull() {
		// Without a default, an omitted argument and an explicit null mean different
		// things, and a model clearing a value is a real request. The null goes through.
		assertThat(protectedVariables(this.builder, """
				query Page($first: Int) { movies(first: $first) { pageInfo { hasNextPage } } }
				""")).isEmpty();
	}

	@Test
	void create_variableUsedWhereOneArgumentLacksADefault_shouldKeepItsNull() {
		// topRatedMovies declares a default for first and movies does not, so dropping
		// the null would feed the schema's 10 to one field and an absent argument to the
		// other. Keeping it sends the same value to both, which is what the caller asked.
		assertThat(protectedVariables(this.builder, """
				query Both($first: Int) {
				  topRatedMovies(first: $first) { id }
				  movies(first: $first) { pageInfo { hasNextPage } }
				}
				""")).isEmpty();
	}

	@Test
	void create_variableFillingANonNullArgumentWithADefaultAndAnArgumentWithout_shouldProtectItFromNull() {
		// votes takes [Locale!]! with a default and publishedBy takes [Locale!] without
		// one. The null sent through fails at votes every time, with "Argument 'locales'
		// has coerced Null value for NonNull type '[Locale!]!'", so keeping it cannot
		// serve publishedBy either. Dropped, votes takes its default and publishedBy sees
		// an absent argument, which is the one way this call works.
		assertThat(protectedVariables(this.votes, """
				query Votes($locales: [Locale!]) {
				  votes(locales: $locales) { id publishedBy(locales: $locales) { name } }
				}
				""")).containsExactly("locales");
	}

	@Test
	void create_variableFillingANonNullInputFieldWithADefaultAndAnArgumentWithout_shouldProtectItFromNull() {
		// Inside an object literal the value lands in the input field, so the field is
		// the place the rule reads. Window.size is Int! with a default, and the null
		// fails there whatever the argument size makes of it.
		assertThat(protectedVariables(this.votes, """
				query Recent($size: Int) {
				  recent(window: { size: $size }, size: $size) { id }
				}
				""")).containsExactly("size");
	}

	@Test
	void create_variableWrittenAsAnItemOfAListLiteral_shouldKeepItsNull() {
		// The value lands in an item of the list, and an item cannot declare a default,
		// so the default votes declares for the whole list does not wait for this
		// variable. Left out, the variable leaves a null item behind, which [Locale!]
		// refuses as it refuses the null sent.
		assertThat(protectedVariables(this.votes, """
				query Votes($locale: Locale!) {
				  votes(locales: [$locale]) { id }
				}
				""")).isEmpty();
	}

	@Test
	void create_nonNullVariableFillingANonNullArgument_shouldKeepItsNull() {
		// A null fails a Non-Null variable whether it is sent or dropped, because the
		// variable is required as well. Sent, the API's answer names the null the model
		// wrote, so the null goes through.
		assertThat(protectedVariables(this.votes, """
				query Votes($locales: [Locale!]!) {
				  votes(locales: $locales) { id publishedBy(locales: $locales) { name } }
				}
				""")).isEmpty();
	}

	@Test
	void create_variableUsedOnlyInsideAMemberOfAUnion_shouldProtectItFromNull() {
		// The walk reads the selections under a union field, so it sees the default that
		// stage declares. Sent through, the null would draw the answer "Expected variable
		// "$current" provided to non-null type "Boolean!" not to be null".
		assertThat(protectedVariables(this.votes, """
				query Newest($current: Boolean) {
				  newest {
				    __typename
				    ... on Vote { stage(current: $current) }
				  }
				}
				""")).containsExactly("current");
	}

	@Test
	void create_variableInsideASpreadFragmentUnderAUnion_shouldProtectItFromNull() {
		assertThat(protectedVariables(this.votes, """
				query Newest($current: Boolean) {
				  newest { ...StageOfVote }
				}

				fragment StageOfVote on Vote {
				  stage(current: $current)
				}
				""")).containsExactly("current");
	}

	@Test
	void create_variableFillingADirectiveArgumentTheSchemaGivesADefault_shouldProtectItFromNull() {
		// The walk reads directive arguments as well as field arguments, so it sees the
		// default of one, and the "public" the schema promises for an argument left out
		// survives the null a model in strict mode sends.
		assertThat(protectedVariables(this.directives, """
				query Newest($status: String) { newest @audience(status: $status) { id } }
				""")).containsExactly("status");
	}

	@Test
	void create_variableFillingTheArgumentOfADirectiveOnTheOperation_shouldProtectItFromNull() {
		assertThat(protectedVariables(this.directives, """
				query Newest($status: String) @audience(status: $status) { newest { id } }
				""")).containsExactly("status");
	}

	@Test
	void create_variableFillingANonNullDirectiveArgumentTheSchemaGivesADefault_shouldProtectItFromNull() {
		// The argument is Int! with a default, so the null fails there and the variable
		// left out takes the 60, as at a field argument of that shape.
		assertThat(protectedVariables(this.directives, """
				query Newest($ttl: Int) { newest @cache(ttl: $ttl) { id } }
				""")).containsExactly("ttl");
	}

	@Test
	void create_variableFillingADirectiveArgumentWithNoDefault_shouldKeepItsNull() {
		assertThat(protectedVariables(this.directives, """
				query Newest($note: String) { newest @audience(note: $note) { id } }
				""")).isEmpty();
	}

	@Test
	void create_variableFillingAnArgumentWithADefaultAndADirectiveArgumentWithout_shouldKeepItsNull() {
		// first declares a default and size does not, so the variable stays unprotected
		// and the directive sees the explicit null the model wrote.
		assertThat(protectedVariables(this.directives, """
				query Newest($first: Int) { newest(first: $first) @window(size: $first) { id } }
				""")).isEmpty();
	}

	@Test
	void create_nonNullVariableFillingAnArgumentWithADefaultAndTheIfOfSkip_shouldKeepItsNull() {
		// @skip(if:) is Boolean! without a default, so it is a place where a default does
		// not wait. The variable is required, the call fails with the null dropped as it
		// does with the null sent, and the null goes through as the model wrote it.
		assertThat(protectedVariables(this.directives, """
				query Newest($brief: Boolean!) { newest(brief: $brief) { id title @skip(if: $brief) } }
				""")).isEmpty();
	}

	@Test
	void create_variableFillingTheIfOfIncludeWithADefaultOfItsOwn_shouldProtectItFromNull() {
		// Validation lets a variable reach @include(if:) where it is Non-Null or declares
		// a default, so the default of the variable is what waits for a null sent there.
		assertThat(protectedVariables(this.directives, """
				query Newest($withTitle: Boolean = false) { newest { id title @include(if: $withTitle) } }
				""")).containsExactly("withTitle");
	}

	@Test
	void create_variableFillingADirectiveArgument_shouldDescribeItWithTheWordsOfThatArgument() {
		OperationCatalog catalog = this.directives.create(List.of(sourceFor("file:/mcp/Newest.graphql", """
				query Newest($status: String, $withTitle: Boolean! = false, $brief: Boolean! = false) {
				  newest(brief: $brief) @audience(status: $status) {
				    id
				    title @include(if: $withTitle) @skip(if: $brief)
				  }
				}
				""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		// The argument of a directive describes the value it takes as the argument of a
		// field does. For the two built-in conditions the sentence is graphql-java's,
		// and it tells a model which way the flag switches the field.
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.inputSchema())
				.contains("\"status\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],"
						+ "\"description\":\"Who the answer is written for.\"}")
				.contains("\"withTitle\":{\"type\":\"boolean\",\"description\":\"Included when true.\","
						+ "\"default\":false}")
				.contains("\"brief\":{\"type\":\"boolean\",\"description\":\"Skipped when true.\","
						+ "\"default\":false}"));
	}

	@Test
	void create_aVariableOfAnInputType_shouldCarryThatTypeForTheNullRules() {
		// The null rules walk a value against its type to reach the input fields inside
		// it, so the tool carries the type of each variable it declares.
		OperationCatalog catalog = OperationCatalogFactory.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
			.build()
			.create(List.of(sourceFor("file:/mcp/RecentReviews.graphql", """
					query RecentReviews($filter: ReviewFilterInput, $first: Int = 20) {
					  reviews(filter: $filter, first: $first) { id }
					}
					""", ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		Map<String, GraphQLInputType> variableTypes = catalog.tools().getFirst().variableTypes();
		assertThat(variableTypes).containsOnlyKeys("filter", "first");
		assertThat(GraphQLTypeUtil.simplePrint(variableTypes.get("filter"))).isEqualTo("ReviewFilterInput");
	}

	@Test
	void create_oneFileWithLfCrlfAndLoneCrLineEndings_shouldPublishOneToolContract() {
		// Git for Windows checks a file out with \r\n, and graphql-java splits a block
		// string on \n alone, so without the rewrite every carriage return would stay in
		// the value: the default would reach the API and the input schema as "\r\nblock
		// string\r\n...\r", and the blank line in the middle would keep every line at its
		// indentation. GraphQL reads \r\n, \n and a lone \r each as one line terminator,
		// so the three texts below are one file, and the tool contract of a Windows
		// checkout is the one a Linux checkout publishes.
		String written = """
				# Finds the movies and the people that match.
				# The text matches in part.
				query Search($text: String! = \"""
				  block string
				  with "quotes"

				    indented line
				\""") {
				  search(text: $text) { __typename }
				}
				""";

		ToolOperation lineFeed = onlyToolOf(written);
		ToolOperation carriageReturnLineFeed = onlyToolOf(written.replace("\n", "\r\n"));
		ToolOperation carriageReturn = onlyToolOf(written.replace('\n', '\r'));

		assertThat(lineFeed.inputSchema())
			.contains("\"default\":\"block string\\nwith \\\"quotes\\\"\\n\\n  indented line\"");
		assertThat(lineFeed.description())
			.isEqualTo("Finds the movies and the people that match.\nThe text matches in part.");
		for (ToolOperation tool : List.of(carriageReturnLineFeed, carriageReturn)) {
			assertThat(tool.printedDocument()).isEqualTo(lineFeed.printedDocument());
			assertThat(tool.inputSchema()).isEqualTo(lineFeed.inputSchema());
			assertThat(tool.description()).isEqualTo(lineFeed.description());
		}
	}

	@Test
	void create_operationFileOfMoreThan15000Tokens_shouldBecomeATool() {
		// graphql-java stops reading a request at 15,000 tokens, which guards a server
		// against a caller it does not know. An operation file is the operator's own
		// text, so it is read without the limits of a request.
		String file = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a", 6000, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("wide");
		assertThat(catalog.tools().getFirst().printedDocument()).contains("a1: id").contains("a6000: id");
	}

	@Test
	void create_operationWhoseSharedFragmentsTakeItsDocumentPast15000Tokens_shouldBecomeATool() {
		// Each file stays below 15,000 tokens and the printed document holds both, so the
		// validator reads the printed document back without that limit as well.
		String operation = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a", 3000, "    ", "\n") + "    ...More\n  }\n}\n";
		String fragment = "fragment More on Movie {\n" + aliasedIds("b", 3000, "  ", "\n") + "}\n";

		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", operation, ToolExposureType.MCP),
					sourceFor("file:/mcp/More.graphql", fragment, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("wide");
		assertThat(catalog.tools().getFirst().printedDocument()).contains("a3000: id").contains("b3000: id");
	}

	@Test
	void create_toolWhoseDocumentHoldsMoreTokensThanAnApiReadsByDefault_shouldWarnAndKeepTheTool() {
		// GATool reads a file of any length, and the API applies limits of its own to the
		// request. A document above the defaults of graphql-java is refused by such an
		// API on every call, so startup warns about it.
		String file = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a", 6000, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("wide");
		assertThat(catalog.warnings()).extracting(OperationWarning::toString)
			.singleElement()
			.asString()
			.startsWith("file:/mcp/Wide.graphql becomes the tool wide and sends a document of more than "
					+ "15,000 tokens, which passes gatool.api.request-limits.max-tokens.")
			.contains("GATool cannot read those limits from the API")
			.contains("the defaults of graphql-java")
			.endsWith("Select less or split the operation, or raise the property where your API reads more.");
	}

	@Test
	void create_toolWhoseSharedFragmentsTakeItsDocumentPastTheTokensOfARequest_shouldNameTheFragmentFiles() {
		String operation = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a", 3000, "    ", "\n") + "    ...More\n  }\n}\n";
		String fragment = "fragment More on Movie {\n" + aliasedIds("b", 3000, "  ", "\n") + "}\n";

		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", operation, ToolExposureType.MCP),
					sourceFor("file:/mcp/More.graphql", fragment, ToolExposureType.MCP)));

		assertThat(catalog.warnings()).extracting(OperationWarning::toString)
			.singleElement()
			.asString()
			.startsWith(
					"file:/mcp/Wide.graphql becomes the tool wide and sends a document of more than " + "15,000 tokens")
			.contains("The document holds the shared fragments of file:/mcp/More.graphql.");
	}

	@Test
	void create_toolWhoseDocumentPassesTheCharactersAndTheTokensOfARequest_shouldNameBoth() {
		// An alias of 200 characters, so 6,000 fields pass 1,048,576 characters as well
		// as 15,000 tokens.
		String file = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a".repeat(200), 6000, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		String printed = catalog.tools().getFirst().printedDocument();
		assertThat(printed.length()).isGreaterThan(1_048_576);
		assertThat(catalog.warnings()).extracting(OperationWarning::toString)
			.singleElement()
			.asString()
			.contains("sends a document of more than 1,048,576 characters and more than 15,000 tokens, "
					+ "which passes gatool.api.request-limits.max-characters and "
					+ "gatool.api.request-limits.max-tokens.");
	}

	@Test
	void create_documentAboveALimitTheApplicationLowered_shouldWarnAtThatLimit() {
		// The limits are the application's to set, because the API sets its own and
		// GATool cannot read them from it. An API that reads 1,000 tokens refuses this
		// document, which the defaults let through without a word.
		OperationCatalogFactory strict = withRequestLimits(new RequestLimits(1_048_576, 1_000, 200_000));
		String file = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a", 400, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = strict
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("wide");
		assertThat(catalog.warnings()).extracting(OperationWarning::toString)
			.singleElement()
			.asString()
			.startsWith("file:/mcp/Wide.graphql becomes the tool wide and sends a document of more than "
					+ "1,000 tokens, which passes gatool.api.request-limits.max-tokens.");
	}

	@Test
	void create_documentBelowALimitTheApplicationRaised_shouldStayQuiet() {
		OperationCatalogFactory generous = withRequestLimits(new RequestLimits(1_048_576, 50_000, 200_000));
		String file = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a", 6000, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = generous
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
	}

	@Test
	void create_limitsSetToZero_shouldLeaveTheSizeOfADocumentUnchecked() {
		OperationCatalogFactory unchecked = withRequestLimits(new RequestLimits(0, 0, 0));
		String file = "# Returns the highest-rated movies.\nquery Wide {\n  topRatedMovies {\n"
				+ aliasedIds("a".repeat(200), 6000, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = unchecked
			.create(List.of(sourceFor("file:/mcp/Wide.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
	}

	@Test
	void create_toolWhoseDocumentFitsARequest_shouldStayQuietAboutItsSize() {
		String file = "# Returns the highest-rated movies.\nquery Narrow {\n  topRatedMovies {\n"
				+ aliasedIds("a", 4000, "    ", "\n") + "  }\n}\n";

		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Narrow.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.warnings()).isEmpty();
	}

	@Test
	void create_operationFileNestedDeeperThanTheParserFollows_shouldBeRefusedNamingTheLimit() {
		// The limits on the length of a file are lifted, and the one on nesting stays.
		// Read without it, this file parses, and the printer then runs the thread out of
		// stack, which ends startup with a StackOverflowError in place of a problem that
		// names the file.
		OperationCatalogFactory nested = OperationCatalogFactory.builder(SdlSchemaFactory.schemaFrom("""
				type Query {
				  "Returns the root of the tree."
				  root: Node
				}

				type Node {
				  id: ID
				  child: Node
				}
				""", "file:/schema/nested.graphqls"), ToolNamingStrategy.camelCase()).build();
		String file = "query Deep{root" + "{child".repeat(2000) + "{id}" + "}".repeat(2000) + "}";

		OperationCatalog catalog = nested
			.create(List.of(sourceFor("file:/mcp/Deep.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.toString()).contains("file:/mcp/Deep.graphql")
				.contains("graphql-java's parser stopped reading it at a limit")
				.contains("More than 500 deep 'grammar' rules have been entered")
				.doesNotContain("invalid GraphQL syntax"));
	}

	@Test
	void create_operationFileNestedAsDeepAsTheParserFollows_shouldBecomeATool() {
		OperationCatalogFactory nested = OperationCatalogFactory.builder(SdlSchemaFactory.schemaFrom("""
				type Query {
				  "Returns the root of the tree."
				  root: Node
				}

				type Node {
				  id: ID
				  child: Node
				}
				""", "file:/schema/nested.graphqls"), ToolNamingStrategy.camelCase()).build();
		String file = "query Deep{root" + "{child".repeat(163) + "{id}" + "}".repeat(163) + "}";

		OperationCatalog catalog = nested
			.create(List.of(sourceFor("file:/mcp/Deep.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("deep");
	}

	@Test
	void create_minifiedFileWhosePrintedDocumentHoldsMoreThan200000WhitespaceTokens_shouldBecomeATool() {
		// Every space counts as a token of its own, against a limit of 200,000. The file
		// is written on one line, and AstPrinter indents each of its 4,000 fields by 64
		// spaces, so the printed document holds more whitespace tokens than the limit of
		// a request allows.
		OperationCatalogFactory nested = OperationCatalogFactory.builder(SdlSchemaFactory.schemaFrom("""
				type Query {
				  "Returns the root of the tree."
				  root: Node
				}

				type Node {
				  id: ID
				  child: Node
				}
				""", "file:/schema/nested.graphqls"), ToolNamingStrategy.camelCase()).build();
		String file = "query Deep{root" + "{child".repeat(30) + "{" + aliasedIds("a", 4000, "", " ") + "}"
				+ "}".repeat(30) + "}";

		OperationCatalog catalog = nested
			.create(List.of(sourceFor("file:/mcp/Deep.graphql", file, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).extracting(ToolOperation::toolName).containsExactly("deep");
		assertThat(catalog.tools().getFirst().printedDocument().chars().filter((character) -> character == ' ').count())
			.isGreaterThan(200_000);
		// The API receives the printed document, so the spaces the printer indents with
		// count there, and the warning names them.
		assertThat(catalog.warnings()).extracting(OperationWarning::toString)
			.singleElement()
			.asString()
			.contains("sends a document of more than 200,000 whitespace tokens, which passes "
					+ "gatool.api.request-limits.max-whitespace-tokens.");
	}

	// Each alias is three tokens, the alias, the colon and the field.
	private OperationCatalogFactory withRequestLimits(RequestLimits limits) {
		return OperationCatalogFactory.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
			.requestLimits(limits)
			.build();
	}

	private static String aliasedIds(String prefix, int count, String indent, String separator) {
		StringBuilder fields = new StringBuilder();
		for (int number = 1; number <= count; number++) {
			fields.append(indent).append(prefix).append(number).append(": id").append(separator);
		}
		return fields.toString();
	}

	private ToolOperation onlyToolOf(String text) {
		OperationCatalog catalog = this.builder
			.create(List.of(sourceFor("file:/mcp/Search.graphql", text, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).hasSize(1);
		return catalog.tools().getFirst();
	}

	// The variables of one file's tool that the null rules protect from the null a model
	// in strict mode sends for a value it would have left out.
	private static Set<String> protectedVariables(OperationCatalogFactory catalogueBuilder, String text) {
		OperationCatalog catalog = catalogueBuilder
			.create(List.of(sourceFor("file:/mcp/Operation.graphql", text, ToolExposureType.MCP)));

		assertThat(catalog.problems()).isEmpty();
		return catalog.tools().getFirst().variablesWithDefaults();
	}

	private static OperationSource sourceFor(String location, String text, ToolExposureType toolExposureType) {
		return new OperationSource(location, text, Set.of(toolExposureType));
	}

	// Named classes, so the problem can be asserted to carry the class name a reader
	// would look up.
	private static final class FailingStrategy implements ToolNamingStrategy {

		@Override
		public String toolName(String graphQlName) {
			throw new IllegalStateException("the registry is down");
		}

	}

	private static final class NullStrategy implements ToolNamingStrategy {

		@Override
		@SuppressWarnings("NullAway")
		public String toolName(String graphQlName) {
			return null;
		}

	}

}
