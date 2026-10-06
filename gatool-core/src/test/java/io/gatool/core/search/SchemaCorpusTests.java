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

package io.gatool.core.search;

import java.util.List;

import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the search layer holds for one schema, and what it leaves out.
 *
 * <p>
 * Every case here is cheap and deterministic: what the corpus holds for a schema shape,
 * and what it leaves out.
 */
class SchemaCorpusTests {

	private static final String SDL = """
			\"""A thing with an id.\"""
			interface Node {
			  \"""The identifier.\"""
			  id: ID!
			}
			type Movie implements Node {
			  id: ID!
			  \"""The average rating,
			  from 0 to 10.\"""
			  rating(scale: RatingScale, precise: Boolean = false): Float
			  title: String!
			}
			enum RatingScale { STARS PERCENT }
			input MovieFilter { titleContains: String, minRating: Float }
			union Result = Movie
			type Query {
			  movie(id: ID!): Movie
			  search(filter: MovieFilter): [Result!]!
			}
			""";

	@Test
	void of_anySchema_shouldHoldOneEntryPerFieldOfEveryObjectAndInterface() {
		List<CorpusEntry> corpus = SchemaCorpus.of(schema(), CorpusFormat.RAW, false, true);

		assertThat(corpus).extracting(CorpusEntry::coordinate)
			// Movie's three fields, Node's one, Query's two. An interface is walked
			// because a schema declares many of its most-used fields on one.
			.containsExactly("Movie.id", "Movie.rating", "Movie.title", "Node.id", "Query.movie", "Query.search");
	}

	@Test
	void of_anySchema_shouldLeaveOutWhatNoSelectionSetCanName() {
		List<CorpusEntry> corpus = SchemaCorpus.of(schema(), CorpusFormat.RAW, false, true);

		// An enum, an input object and a union do not hold a field a selection set names,
		// so introspectType is what shows them once search has named a coordinate.
		assertThat(corpus).extracting(CorpusEntry::coordinate)
			.noneMatch((coordinate) -> coordinate.startsWith("RatingScale.") || coordinate.startsWith("MovieFilter.")
					|| coordinate.startsWith("Result."));
	}

	@Test
	void of_anySchema_shouldLeaveOutTheTypesTheSpecificationReserves() {
		List<CorpusEntry> corpus = SchemaCorpus.of(schema(), CorpusFormat.RAW, false, true);

		// __Schema and friends are in every schema graphql-java builds, and __schema and
		// __type are meta-fields of Query that getFieldDefinitions() already omits.
		// isNotEmpty first, because allSatisfy on an empty list passes without asserting
		// anything, and an empty corpus would be the real failure.
		assertThat(corpus).extracting(CorpusEntry::coordinate).isNotEmpty().allSatisfy((coordinate) -> {
			assertThat(coordinate).doesNotStartWith("__");
			assertThat(coordinate).doesNotContain(".__");
		});
	}

	@Test
	void of_sdlFormat_shouldWriteTheFieldAsTheSchemaDeclaresIt() {
		String rating = textOf(SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true), "Movie.rating");

		// The arguments are the reason SDL is the default: it is the only format that
		// tells a model an argument exists at all.
		assertThat(rating).isEqualTo("""
				\"""The average rating,
				from 0 to 10.\"""
				rating(scale: RatingScale, precise: Boolean): Float  # on type Movie""");
	}

	@Test
	void of_sdlFormat_shouldLeaveTheDescriptionOutWhenTheSchemaHasNone() {
		String title = textOf(SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true), "Movie.title");

		assertThat(title).isEqualTo("title: String!  # on type Movie");
	}

	@Test
	void of_glossFormat_shouldWriteOneSentence() {
		String rating = textOf(SchemaCorpus.of(schema(), CorpusFormat.GLOSS, false, true), "Movie.rating");

		assertThat(rating).startsWith("GraphQL field Movie.rating. Owner type: Movie. Returns: Float. ")
			.endsWith("from 0 to 10.");
	}

	@Test
	void of_rawFormat_shouldWriteTheCoordinateAlone() {
		assertThat(textOf(SchemaCorpus.of(schema(), CorpusFormat.RAW, false, true), "Movie.rating"))
			.isEqualTo("Movie.rating");
	}

	@Test
	void of_aDescriptionOverSeveralLines_shouldKeepItsOwnLineBreaks() {
		String rating = textOf(SchemaCorpus.of(schema(), CorpusFormat.GLOSS, false, true), "Movie.rating");

		// A description is stripped and otherwise left as the schema wrote it. Collapsing
		// the breaks would change every token count, and with it the number of hits a
		// budget admits.
		assertThat(rating).contains("The average rating,").contains("from 0 to 10.");
	}

	@Test
	void of_everyEntry_shouldCarryWhatReadingItCosts() {
		List<CorpusEntry> corpus = SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true);

		// The count travels with the text, because the search budget is spent by walking
		// ranked entries and stopping before the next one crosses it. It covers the path
		// too, because a model reads that as well.
		assertThat(corpus).allSatisfy((entry) -> {
			int read = entry.text().length() + ((entry.path() != null) ? entry.path().length() : 0);
			assertThat(entry.tokens()).isEqualTo(read / 4);
		}).anySatisfy((entry) -> assertThat(entry.tokens()).isPositive());
	}

	@Test
	void of_theSameSchemaTwice_shouldGiveTheSameList() {
		// A caller that caches anything keyed on this text depends on the order, so the
		// types are sorted and the fields keep the order the schema declares.
		assertThat(SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true))
			.isEqualTo(SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true));
	}

	@Test
	void of_aSchemaWithOneRootField_shouldHoldThatFieldAlone() {
		GraphQLSchema oneField = SdlSchemaFactory.schemaFrom("type Query { ping: String }", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(oneField, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.containsExactly("Query.ping");
	}

	@Test
	void of_aFieldOnANestedType_shouldCarryTheWayToReachItsOwner() {
		// A hit names Movie.rating and says what it returns. Without this, an operation
		// cannot be written, because a selection set starts at a root.
		CorpusEntry rating = entry(SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true), "Movie.rating");

		assertThat(rating.path()).isEqualTo("Query.movie(id: ID!)");
	}

	@Test
	void of_aFieldOnARootType_shouldCarryNoPath() {
		// Query.movie is already where an operation starts.
		assertThat(entry(SchemaCorpus.of(schema(), CorpusFormat.SDL, false, true), "Query.movie").path()).isNull();
	}

	@Test
	void of_aSchemaWithASubscriptionRoot_shouldLeaveItsFieldsOut() {
		// A subscription answers over a stream, and a tool call returns a single result,
		// so no tool GATool publishes can reach those fields. Indexed, they would come
		// back from a search looking like any other coordinate, because SchemaPaths seeds
		// its roots from the query and mutation types alone, so the hit would print
		// without a path the way a root field does.
		GraphQLSchema streaming = SdlSchemaFactory.schemaFrom("""
				type Query { movie: String }
				type Subscription { movieAdded: String }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(streaming, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.containsExactly("Query.movie");
	}

	@Test
	void of_aTypeReachableOnlyThroughASubscription_shouldLeaveItOut() {
		// Skipping the subscription root alone would leave everything behind it offered,
		// and without a path, which is how a root field prints. The model would read
		// ReviewEvent.score as something it could select.
		GraphQLSchema streaming = SdlSchemaFactory.schemaFrom("""
				type Query { movie: String }
				type ReviewEvent { id: ID! score: Int! }
				type Subscription { reviewAdded: ReviewEvent! }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(streaming, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.containsExactly("Query.movie");
	}

	@Test
	void of_aTypeNamedAsTheSubscriptionRootAndReachable_shouldKeepItsEntries() {
		// The filter reads reachability, so a name that merely looks like the root does
		// not count. A query reaches this one, so it stays.
		GraphQLSchema plans = SdlSchemaFactory.schemaFrom("""
				type Query { plan: SubscriptionPlan }
				type SubscriptionPlan { name: String }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(plans, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.contains("SubscriptionPlan.name");
	}

	@Test
	void of_anInterfaceNoFieldReturns_shouldKeepItsEntries() {
		// Filtering on SchemaPaths would have dropped this: nothing returns Node, so it
		// does not get a path. Its fields are still what a model reads on Movie, so it
		// belongs.
		GraphQLSchema interfaces = SdlSchemaFactory.schemaFrom("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! title: String! }
				type Query { movie: Movie }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(interfaces, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.contains("Node.id");
	}

	@Test
	void of_anImplementationNoFieldReturns_shouldKeepItsEntries() {
		// The other half of the same gap. Nothing returns Movie, and
		// "... on Movie { rating }" under a field returning Node selects it.
		GraphQLSchema interfaces = SdlSchemaFactory.schemaFrom("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! rating: Float }
				type Query { anything: Node }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(interfaces, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.contains("Movie.rating");
	}

	@Test
	void of_aDeprecatedField_shouldStayOutByDefault() {
		// A large schema carries many, and each one spends the budget on a field the
		// schema asks a caller to stop using. Offered unmarked, the model cannot tell
		// searchMovies from search.
		assertThat(SchemaCorpus.of(deprecating(), CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.contains("Query.search", "Movie.score")
			.doesNotContain("Query.searchMovies", "Movie.rating");
	}

	@Test
	void of_aDeprecatedFieldWithTheSettingOn_shouldCarryTheSchemasOwnReason() {
		// Turned on, the field arrives marked. The reason is what sends the model to the
		// replacement.
		String rating = textOf(SchemaCorpus.of(deprecating(), CorpusFormat.SDL, true, true), "Movie.rating");

		assertThat(rating).isEqualTo("rating: Float @deprecated(reason: \"Use score instead.\")  # on type Movie");
	}

	@Test
	void of_aFieldWithADeprecatedArgument_shouldFollowTheSetting() {
		// Written bare, the argument would read as current and a model would pass it.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				type Query { movies(limit: Int @deprecated(reason: "Use first."), first: Int): [String!]! }
				""", "corpus-test.graphqls");

		assertThat(textOf(SchemaCorpus.of(schema, CorpusFormat.SDL, false, true), "Query.movies"))
			.isEqualTo("movies(first: Int): [String!]!  # on type Query");
		assertThat(textOf(SchemaCorpus.of(schema, CorpusFormat.SDL, true, true), "Query.movies")).isEqualTo(
				"movies(limit: Int @deprecated(reason: \"Use first.\"), first: Int): [String!]!" + "  # on type Query");
	}

	@Test
	void of_aFieldOnAnInterfaceNoFieldReturns_shouldSayWhereToSelectIt() {
		// Nothing returns Node, so it is without a path, and without the hint a hit on
		// Node.id would print the way a root field does, and the model would write "{ id
		// }" at the root.
		GraphQLSchema interfaces = SdlSchemaFactory.schemaFrom("""
				interface Node { id: ID! }
				type Studio implements Node { id: ID! }
				type Movie implements Node { id: ID! title: String! }
				type Query { movie: Movie }
				""", "corpus-test.graphqls");

		assertThat(entry(SchemaCorpus.of(interfaces, CorpusFormat.SDL, false, true), "Node.id").path())
			.isEqualTo("Query.movie (declared on interface Node; select it on an implementation such as Movie)");
	}

	@Test
	void of_aTypeReachableOnlyThroughADeprecatedField_shouldStayOutWithIt() {
		// The route matters as much as the field. Leaving searchMovies out of the corpus
		// and still routing through it would offer coordinates whose only way in is a
		// coordinate the model was not shown.
		GraphQLSchema deprecatedRoute = SdlSchemaFactory.schemaFrom("""
				type Query {
				  search(text: String!): [Movie!]!
				  legacy: LegacyReport @deprecated(reason: "Use search instead.")
				}
				type Movie { id: ID! }
				type LegacyReport { rows: Int! }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(deprecatedRoute, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.doesNotContain("LegacyReport.rows");
		assertThat(SchemaCorpus.of(deprecatedRoute, CorpusFormat.RAW, true, true)).extracting(CorpusEntry::coordinate)
			.contains("LegacyReport.rows");
	}

	private static GraphQLSchema deprecating() {
		return SdlSchemaFactory.schemaFrom("""
				type Movie {
				  id: ID!
				  rating: Float @deprecated(reason: "Use score instead.")
				  score: Float
				}
				type Query {
				  search(text: String!): [Movie!]!
				  searchMovies(text: String!): [Movie!]! @deprecated(reason: "Use 'search' instead.")
				}
				""", "corpus-test.graphqls");
	}

	@Test
	void of_aDeprecationReasonCarryingAQuoteAndALineBreak_shouldWriteSdlTheParserReads() {
		// The reason is the schema author's text. Written between quotes as it is, a
		// quotation mark would close the string early, and what follows would sit beside
		// the field looking like structure.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				type Query {
				  legacy: String @deprecated(reason: "Use \\"search\\" instead.\\nSame scale, new name.")
				}
				""", "corpus-test.graphqls");
		String legacy = textOf(SchemaCorpus.of(schema, CorpusFormat.SDL, true, true), "Query.legacy");

		assertThat(legacy)
			.isEqualTo("legacy: String @deprecated(reason: \"Use \\\"search\\\" instead.\\nSame scale, new name.\")"
					+ "  # on type Query");
		assertThat(deprecationReasonOf(legacy)).isEqualTo("Use \"search\" instead.\nSame scale, new name.");
	}

	@Test
	void of_theMutationRootWithMutationsOff_shouldStayOutWithEverythingOnlyItReaches() {
		// executeGraphql refuses a mutation while the switch is off, so a mutation
		// coordinate the search offers would be one the model writes an operation against
		// and then reads a refusal for.
		GraphQLSchema writing = SdlSchemaFactory.schemaFrom("""
				type Query { movie: Movie }
				type Movie { id: ID! }
				input ReviewInput { score: Int! }
				type ReviewPayload { review: Movie ok: Boolean! }
				type Mutation { addReview(input: ReviewInput!): ReviewPayload }
				""", "corpus-test.graphqls");

		assertThat(SchemaCorpus.of(writing, CorpusFormat.RAW, false, false)).extracting(CorpusEntry::coordinate)
			.containsExactly("Movie.id", "Query.movie");
		assertThat(SchemaCorpus.of(writing, CorpusFormat.RAW, false, true)).extracting(CorpusEntry::coordinate)
			.containsExactly("Movie.id", "Mutation.addReview", "Query.movie", "ReviewPayload.review",
					"ReviewPayload.ok");
	}

	@Test
	void of_aFieldOnATypeReachedThroughAnInterface_shouldCarryTheFragmentStep() {
		// Nothing returns Movie itself, so without the fragment step the hit would arrive
		// without a path and print like a root field, and the model would write "{ movie
		// { title } }" against a schema whose only way in is node.
		GraphQLSchema relay = SdlSchemaFactory.schemaFrom("""
				interface Node { id: ID! }
				type Movie implements Node { id: ID! title: String! }
				type Query { node(id: ID!): Node }
				""", "corpus-test.graphqls");

		assertThat(entry(SchemaCorpus.of(relay, CorpusFormat.SDL, false, true), "Movie.title").path())
			.isEqualTo("Query.node(id: ID!) -> ... on Movie");
	}

	@Test
	void of_aPath_shouldNeverRouteThroughAFieldTheCorpusLeavesOut() {
		// The shortest way to Movie is the deprecated field, which the corpus hides by
		// default, so the hint has to take the way the model can see.
		GraphQLSchema shortcut = SdlSchemaFactory.schemaFrom("""
				type Query {
				  latest: Movie @deprecated(reason: "Use movies.")
				  movies: [MovieEdge!]!
				}
				type MovieEdge { node: Movie! }
				type Movie { title: String! }
				""", "corpus-test.graphqls");

		assertThat(entry(SchemaCorpus.of(shortcut, CorpusFormat.SDL, false, true), "Movie.title").path())
			.isEqualTo("Query.movies -> MovieEdge.node");
		assertThat(entry(SchemaCorpus.of(shortcut, CorpusFormat.SDL, true, true), "Movie.title").path())
			.isEqualTo("Query.latest");
	}

	// The reason a parser reads back out of one deprecated corpus entry.
	private static String deprecationReasonOf(String entryText) {
		ObjectTypeDefinition type = (ObjectTypeDefinition) new Parser()
			.parseDocument("type Query {\n" + entryText + "\n}")
			.getDefinitions()
			.getFirst();
		StringValue reason = (StringValue) type.getFieldDefinitions()
			.getFirst()
			.getDirectives("deprecated")
			.getFirst()
			.getArgument("reason")
			.getValue();
		return reason.getValue();
	}

	@Test
	void of_aDescriptionCarryingQuotes_shouldWriteSdlTheParserReads() {
		// The SDL entry is what searchSchema hands the model, and a description is
		// whatever the schema's authors wrote.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				type Query {
				  \"""The cursor. Send it as \\\""" to reset.\"""
				  page(after: String): String
				  \"""
				  Use the operator "eq"
				  \"""
				  filtered(by: String): String
				}
				""", "corpus-test.graphqls");
		List<CorpusEntry> corpus = SchemaCorpus.of(schema, CorpusFormat.SDL, false, true);

		assertThat(descriptionOf(textOf(corpus, "Query.page"))).isEqualTo("The cursor. Send it as \"\"\" to reset.");
		assertThat(descriptionOf(textOf(corpus, "Query.filtered"))).isEqualTo("Use the operator \"eq\"");
	}

	// The description a parser reads back out of one corpus entry, which is what the
	// entry says to a model. An entry holds one field, so it is read inside a type, and
	// the trailing comment naming the owner is a comment to the parser as well.
	private static String descriptionOf(String entryText) {
		ObjectTypeDefinition type = (ObjectTypeDefinition) new Parser()
			.parseDocument("type Query {\n" + entryText + "\n}")
			.getDefinitions()
			.getFirst();
		return type.getFieldDefinitions().getFirst().getDescription().getContent();
	}

	private static CorpusEntry entry(List<CorpusEntry> corpus, String coordinate) {
		return corpus.stream()
			.filter((each) -> each.coordinate().equals(coordinate))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no entry for " + coordinate));
	}

	private static GraphQLSchema schema() {
		return SdlSchemaFactory.schemaFrom(SDL, "corpus-test.graphqls");
	}

	private static String textOf(List<CorpusEntry> corpus, String coordinate) {
		return corpus.stream()
			.filter((entry) -> entry.coordinate().equals(coordinate))
			.map(CorpusEntry::text)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no entry for " + coordinate));
	}

}
