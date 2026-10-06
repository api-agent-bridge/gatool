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

package io.gatool.core.internal.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.internal.operation.OperationDiagnostics;
import io.gatool.core.internal.operation.OperationFile;
import io.gatool.core.internal.operation.OperationFileParser;
import io.gatool.core.internal.operation.OperationSource;
import io.gatool.core.internal.operation.ParsedFile;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

class InputSchemaWriterTests {

	// The dialect keyword opens every schema the writer produces, and naming it once
	// keeps each expected value inside the line length the project keeps to.
	private static final String DIALECT = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",";

	private static final GraphQLSchema MOVIES = SdlSchemaFactory.schemaFrom(MoviesSchema.sdl(),
			"classpath:" + MoviesSchema.RESOURCE);

	// A second schema, because every argument in the movie schema takes a single
	// value, and a list variable needs a list argument.
	private static final String TAG_SCHEMA = """
			type Query {
			  taggedMovies(
			    "The tags to match."
			    tags: [String!]!
			  ): [String!]!
			}
			""";

	// A fourth schema, because the movie schema lacks an argument that takes a list, an
	// ID, or an Input Object carrying a default of its own. A scalar whose page says one
	// thing while the API takes another is the case a fragment override exists for, and
	// it is where a default and its property can disagree.
	private static final GraphQLSchema CONTRADICTING = SdlSchemaFactory.schemaFrom("""
			scalar Long @specifiedBy(url: "https://scalars.graphql.org/jakobmerrild/long")
			scalar Stamp @specifiedBy(url: "https://scalars.graphql.org/andimarek/date-time")

			type Row { id: ID! }

			type Query {
			  rows(n: Long, at: Stamp): [Row!]!
			}
			""", "classpath:contradicting.graphqls");

	// A fifth schema, for the two fragment keywords that assert on a string beyond its
	// type, and for a scalar that takes an object or a list literal.
	private static final GraphQLSchema COLOURS = SdlSchemaFactory.schemaFrom("""
			scalar Colour
			scalar Code
			scalar JSON

			input Paint {
			  colour: Colour = "blue"
			  code: Code = "ABC"
			}

			type Query {
			  paint(colour: Colour, code: Code, paint: Paint): String!
			  search(filter: JSON, tags: JSON): String!
			}
			""", "classpath:colours.graphqls");

	private static final GraphQLSchema DEFAULTS = SdlSchemaFactory.schemaFrom("""
			enum Genre { ACTION COMEDY }

			input DefaultFilter {
			  genre: Genre
			  minRating: Float = 5.0
			}

			type Query {
			  defaults(text: String, flag: Boolean, rate: Float, genre: Genre, absent: String): String!
			  tagged(tags: [String!]): String!
			  byId(id: ID): String!
			  filtered(filter: DefaultFilter): String!
			}
			""", "defaults.graphqls");

	// A sixth schema, for two scalars whose values GATool passes through as the
	// literal spells them: a decimal the API reads as a BigDecimal, and a whole number
	// past a long.
	private static final GraphQLSchema PASS_THROUGH = SdlSchemaFactory.schemaFrom("""
			scalar Decimal
			scalar Long

			type Query {
			  amounts(d: Decimal, e: Decimal, n: Long): String!
			}
			""", "pass-through.graphqls");

	// A third schema, because every argument in the movie schema takes a built-in type.
	// RFC 4122 and RFC 3986 are the two URLs the GraphQL specification writes in its own
	// @specifiedBy examples, and the third is the one graphql-java-extended-scalars
	// declares on its DateTime.
	private static final String SCALAR_SCHEMA = """
			scalar DateTime @specifiedBy(url: "https://scalars.graphql.org/andimarek/date-time")

			scalar UUID @specifiedBy(url: "https://tools.ietf.org/html/rfc4122")

			scalar Url @specifiedBy(url: "https://tools.ietf.org/html/rfc3986")

			"A two-letter ISO 3166-1 country code, such as SI."
			scalar CountryCode

			"A postal code, in the format the country uses."
			scalar PostalCode @specifiedBy(url: "https://example.com/postal-codes")

			type Query {
			  reviews(since: DateTime, id: UUID, home: Url, country: CountryCode,
			    postalCode: PostalCode): [String!]!
			}
			""";

	@Test
	void write_operationWithoutVariables_shouldWriteAnObjectWithoutProperties() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query TopRatedMovies {
				  topRatedMovies { id title }
				}
				""")).json();

		assertThat(text).isEqualTo(
				DIALECT + "\"type\":\"object\",\"properties\":{},\"required\":[]," + "\"additionalProperties\":false}");
	}

	@Test
	void write_nullableIntWithADefault_shouldWriteTheNullBranchTheDescriptionAndTheDefault() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { id title rating }
				}
				""")).json();

		assertThat(text).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"first\":"
				+ "{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}],"
				+ "\"description\":\"How many movies to return.\",\"default\":10}},\"required\":[],"
				+ "\"additionalProperties\":false}");
	}

	@Test
	void write_nonNullVariableWithoutADefault_shouldListTheVariableAsRequired() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query Search($text: String!) {
				  search(text: $text) { __typename }
				}
				""")).json();

		assertThat(text).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
				+ "\"required\":[\"text\"],\"additionalProperties\":false}");
	}

	@Test
	void write_argumentWithoutADescription_shouldWriteThePropertyWithoutOne() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query Movies($first: Int) {
				  movies(first: $first) { edges { node { title } } }
				}
				""")).json();

		assertThat(text).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"first\":"
				+ "{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}]}},"
				+ "\"required\":[],\"additionalProperties\":false}");
	}

	@Test
	void write_oneOfInputObjectVariable_shouldWriteOneBranchPerField() {
		// A OneOf Input Object takes exactly one of its fields, which JSON Schema says as
		// a branch per field, each requiring that field alone.
		String text = InputSchemaWriter.write(MOVIES, file("""
				query MovieByTitle($by: MovieLookupInput!) {
				  movie(by: $by) { id title }
				}
				""")).json();

		assertThat(text).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"by\":{\"anyOf\":["
				+ "{\"type\":\"object\",\"properties\":{\"id\":{\"anyOf\":[{\"type\":\"string\"},"
				+ "{\"type\":\"integer\"}]}}," + "\"required\":[\"id\"],\"additionalProperties\":false},"
				+ "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},"
				+ "\"required\":[\"title\"],\"additionalProperties\":false}],"
				+ "\"description\":\"Identifies one movie. Give exactly one of the fields.\"}},"
				+ "\"required\":[\"by\"],\"additionalProperties\":false}");
	}

	@Test
	void write_inputObjectWithAnEnumField_shouldDescribeBothInline() {
		// The model reads the field names and the enum's values.
		String text = InputSchemaWriter.write(MOVIES, file("""
				query Movies($filter: MovieFilterInput) {
				  movies(filter: $filter) { edges { node { title } } }
				}
				""")).json();

		assertThat(text)
			.contains("\"genre\":{\"anyOf\":[{\"type\":\"string\",\"enum\":[\"ACTION\",\"COMEDY\","
					+ "\"DRAMA\",\"SCIFI\"]},{\"type\":\"null\"}]}")
			.contains("\"minRating\":{\"anyOf\":[{\"type\":\"number\"},{\"type\":\"null\"}]}")
			// Every field of MovieFilterInput is nullable, so the required list stays
			// empty, and the object stays closed to fields the schema leaves out.
			.contains("\"required\":[],\"additionalProperties\":false");
	}

	@Test
	void write_inputObjectWithNonNullFields_shouldListThemAsRequired() {
		// Obligation reads the way GraphQL declares it: movieId and score carry !, and
		// comment does not, so the object requires the first two alone.
		String text = InputSchemaWriter.write(MOVIES, file("""
				mutation AddReview($input: AddReviewInput!) {
				  addReview(input: $input) { review { id } }
				}
				""")).json();

		// movieId is an ID, which takes a string or an integer on the way in.
		assertThat(text).contains("\"movieId\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")
			.contains("\"score\":{\"type\":\"integer\",\"description\":\"Score from 1 to 10.\"}")
			.contains("\"comment\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}")
			.contains("\"required\":[\"movieId\",\"score\"]");
	}

	@Test
	void write_listVariable_shouldWriteAnArrayWithItsItemType() {
		GraphQLSchema tags = SdlSchemaFactory.schemaFrom(TAG_SCHEMA, "file:/schema/tags.graphqls");

		String text = InputSchemaWriter.write(tags, file("""
				query TaggedMovies($tags: [String!]!) {
				  taggedMovies(tags: $tags)
				}
				""")).json();

		assertThat(text).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"tags\":{\"type\":\"array\","
				+ "\"items\":{\"type\":\"string\"},\"description\":\"The tags to match.\"}},"
				+ "\"required\":[\"tags\"],\"additionalProperties\":false}");
	}

	@Test
	void write_scalarsCarryingTheSpecifiedByUrls_shouldMapEachToItsJsonType() {
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom(SCALAR_SCHEMA, "file:/schema/scalars.graphqls");

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Reviews($since: DateTime, $id: UUID, $home: Url) {
				  reviews(since: $since, id: $id, home: $home)
				}
				"""));

		assertThat(written.json()).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{"
		// The page narrows RFC 3339, and format: date-time cannot say so, which is
		// what the table entry's own description is for.
				+ "\"since\":{\"anyOf\":[{\"type\":\"string\",\"format\":\"date-time\"},{\"type\":\"null\"}],"
				+ "\"description\":\"An RFC 3339 date-time string with exactly three fractional-second "
				+ "digits and a UTC offset, where -00:00 is rejected, such as "
				+ "\\\"2011-08-30T13:22:53.108Z\\\".\"},"
				+ "\"id\":{\"anyOf\":[{\"type\":\"string\",\"format\":\"uuid\"},{\"type\":\"null\"}]},"
				// RFC 3986 keeps the format off, because OpenAI's strict subset omits
				// uri, so the URL is what tells the model which kind of string this is.
				+ "\"home\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],"
				+ "\"description\":\"See https://tools.ietf.org/html/rfc3986 for the format of this value.\"}},"
				+ "\"required\":[],\"additionalProperties\":false}");
		assertThat(written.unmappedScalars()).isEmpty();
	}

	@Test
	void write_mappedScalarCarryingItsOwnDescription_shouldKeepIt() {
		// A scalar GATool recognises keeps its description, so a schema that documents
		// its Url scalar publishes the sentence beside {"type":"string"}.
		GraphQLSchema described = SdlSchemaFactory.schemaFrom("""
				"The home page of the studio, as an absolute URL."
				scalar Home @specifiedBy(url: "https://tools.ietf.org/html/rfc3986")

				"The identifier the studio registry issued."
				scalar StudioId @specifiedBy(url: "https://tools.ietf.org/html/rfc4122")

				type Query { studio(home: Home, id: StudioId): String! }
				""", "file:/schema/described.graphqls");

		InputSchema written = InputSchemaWriter.write(described, file("""
				query Studio($home: Home, $id: StudioId) {
				  studio(home: $home, id: $id)
				}
				"""));

		assertThat(written.json()).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{"
		// RFC 3986 maps to string without a format, so the URL still names the format.
				+ "\"home\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}],"
				+ "\"description\":\"The home page of the studio, as an absolute URL. "
				+ "See https://tools.ietf.org/html/rfc3986 for the format of this value.\"},"
				// format: uuid names the format, so the URL sentence would repeat it.
				+ "\"id\":{\"anyOf\":[{\"type\":\"string\",\"format\":\"uuid\"},{\"type\":\"null\"}],"
				+ "\"description\":\"The identifier the studio registry issued.\"}},"
				+ "\"required\":[],\"additionalProperties\":false}");
		// A scalar the table described stays out of the startup warning.
		assertThat(written.unmappedScalars()).isEmpty();
	}

	@Test
	void write_scalarWithoutASpecifiedByUrl_shouldAcceptAnyJsonValueAndCarryItsDescription() {
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom(SCALAR_SCHEMA, "file:/schema/scalars.graphqls");

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Reviews($country: CountryCode) {
				  reviews(country: $country)
				}
				"""));

		assertThat(written.json()).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{\"country\":"
				+ "{\"description\":\"A two-letter ISO 3166-1 country code, such as SI.\"}},"
				+ "\"required\":[],\"additionalProperties\":false}");
		assertThat(written.unmappedScalars()).containsExactly("CountryCode");
	}

	@Test
	void write_scalarWithAnUnknownSpecifiedByUrl_shouldCarryThatUrlInTheDescription() {
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom(SCALAR_SCHEMA, "file:/schema/scalars.graphqls");

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Reviews($postalCode: PostalCode) {
				  reviews(postalCode: $postalCode)
				}
				"""));

		assertThat(written.json()).contains("A postal code, in the format the country uses.",
				"See https://example.com/postal-codes for the format of this value.");
		assertThat(written.unmappedScalars()).containsExactly("PostalCode");
	}

	@Test
	void write_inputObjectVariable_shouldLeaveTheUnmappedScalarListEmpty() {
		InputSchema written = InputSchemaWriter.write(MOVIES, file("""
				query MovieByTitle($by: MovieLookupInput!) {
				  movie(by: $by) { id title }
				}
				"""));

		assertThat(written.unmappedScalars()).isEmpty();
	}

	@Test
	void write_variableWithADefault_shouldPublishItAsTheDefaultKeyword() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) { title }
				}
				""")).json();

		// A model that leaves the argument out gets ten movies, and the default keyword
		// says so.
		assertThat(text).contains("\"default\":10");
	}

	@Test
	void write_defaultOfEachLiteralKind_shouldCarryItsJsonForm() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query Defaults($text: String = "hello", $flag: Boolean = true, $rate: Float = 1.5,
				        $genre: Genre = ACTION, $absent: String = null) {
				  defaults(text: $text, flag: $flag, rate: $rate, genre: $genre, absent: $absent)
				}
				""")).json();

		assertThat(text).contains("\"default\":\"hello\"")
			.contains("\"default\":true")
			.contains("\"default\":1.5")
			// An enum default is its name, which is what the model sends back.
			.contains("\"default\":\"ACTION\"")
			.contains("\"default\":null");
	}

	@Test
	void write_singleValueDefaultForAListVariable_shouldCoerceItToAList() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query TaggedMovies($tags: [String!] = "drama") {
				  tagged(tags: $tags)
				}
				""")).json();

		// GraphQL wraps a single value given for a list, so the default says what the
		// API will hold instead of what the file typed.
		assertThat(text).contains("\"default\":[\"drama\"]");
	}

	@Test
	void write_integerDefaultForAnIdVariable_shouldCoerceItToAString() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query MovieById($id: ID = 123) {
				  byId(id: $id)
				}
				""")).json();

		// The property accepts both forms, and the default shows the coerced value,
		// because that is what the API holds once it applies the default.
		assertThat(text).contains("\"default\":\"123\"");
	}

	@Test
	void write_inputObjectDefault_shouldCarryTheFieldsTheFileWrote() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query Filtered($filter: DefaultFilter = { genre: COMEDY, minRating: 7.5 }) {
				  filtered(filter: $filter)
				}
				""")).json();

		assertThat(text).contains("\"default\":{\"genre\":\"COMEDY\",\"minRating\":7.5}");
	}

	@Test
	void write_inputFieldWithADefault_shouldPublishItInsideTheObject() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query Filtered($filter: DefaultFilter) {
				  filtered(filter: $filter)
				}
				""")).json();

		// A schema states its normal behaviour with a field default, and the model
		// reads it for the same reason it reads a variable's default.
		assertThat(text)
			.contains("\"minRating\":{\"anyOf\":[{\"type\":\"number\"},{\"type\":\"null\"}],\"default\":5.0}");
	}

	@Test
	void write_variableWithoutADefault_shouldWriteNoDefaultKeyword() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query SearchTitles($text: String!) {
				  search(text: $text) { ... on Movie { title } }
				}
				""")).json();

		assertThat(text).doesNotContain("\"default\"");
	}

	@Test
	void write_enumVariable_shouldCarryTheTypeDescriptionAndEachValuesOwnWords() {
		GraphQLSchema documented = SdlSchemaFactory.schemaFrom("""
				"How a movie is classified."
				enum Genre {
				  "Chases, fights and explosions."
				  ACTION
				  "Jokes, and a happy ending."
				  COMEDY
				}

				type Query {
				  byGenre(genre: Genre): String!
				}
				""", "documented.graphqls");

		String text = InputSchemaWriter.write(documented, file("""
				query ByGenre($genre: Genre) {
				  byGenre(genre: $genre)
				}
				""")).json();

		// The names say what a model may send, and the words say what each one means,
		// which is what stops it choosing a value that exists and means something else.
		assertThat(text).contains("\"enum\":[\"ACTION\",\"COMEDY\"]")
			.contains("How a movie is classified.")
			.contains("Values:")
			.contains("ACTION: Chases, fights and explosions.")
			.contains("COMEDY: Jokes, and a happy ending.");
	}

	@Test
	void write_deprecatedEnumValuesAndInputFields_shouldSayEachOneIsDeprecated() {
		// The GraphQL specification asks a tool to discourage deprecated use. Left
		// unmarked, a retired value and a retired field read as equal choices, and a
		// model keeps sending them.
		GraphQLSchema retiring = SdlSchemaFactory.schemaFrom("""
				enum Genre {
				  "Chases, fights and explosions."
				  ACTION
				  "Silver screen, no sound."
				  SILENT @deprecated(reason: "Merged into CLASSIC.")
				  CLASSIC @deprecated
				}

				input ReviewFilter {
				  stars: Int
				  "At least this many stars."
				  minStars: Int @deprecated(reason: "Use stars.")
				}

				type Query {
				  byGenre(genre: Genre, filter: ReviewFilter): String!
				}
				""", "retiring.graphqls");

		String text = InputSchemaWriter.write(retiring, file("""
				query ByGenre($genre: Genre, $filter: ReviewFilter) {
				  byGenre(genre: $genre, filter: $filter)
				}
				""")).json();

		assertThat(text).contains("SILENT: Silver screen, no sound. Deprecated: Merged into CLASSIC.")
			// graphql-java fills in the reason the specification's own directive carries.
			.contains("CLASSIC: Deprecated: No longer supported")
			.contains("ACTION: Chases, fights and explosions.")
			.doesNotContain("ACTION: Chases, fights and explosions. Deprecated")
			.contains("At least this many stars. Deprecated: Use stars.");
	}

	@Test
	void write_aNullableEnumVariable_shouldCarryItsWordsOnTheProperty() throws Exception {
		// A nullable variable publishes anyOf, and the enum's words go on the property,
		// because a client reading a property's own description does not find them inside
		// the typed branch. The test above asserts the text holds the words somewhere, so
		// this one asserts the placement.
		GraphQLSchema documented = SdlSchemaFactory.schemaFrom("""
				"How a movie is classified."
				enum Genre {
				  "Chases, fights and explosions."
				  ACTION
				}

				type Query {
				  byGenre(genre: Genre): String!
				}
				""", "documented.graphqls");

		String text = InputSchemaWriter.write(documented, file("""
				query ByGenre($genre: Genre) {
				  byGenre(genre: $genre)
				}
				""")).json();

		JsonNode genre = JsonMapper.builder().build().readTree(text).path("properties").path("genre");
		assertThat(genre.path("description").asString()).contains("How a movie is classified.");
		assertThat(genre.path("anyOf").path(0).has("description"))
			.describedAs("the words belong on the property, so the typed branch of anyOf leaves them out")
			.isFalse();
	}

	@Test
	void write_enumWithoutDocumentation_shouldPublishTheValuesAlone() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query Defaults($genre: Genre) {
				  defaults(genre: $genre)
				}
				""")).json();

		assertThat(text).contains("\"enum\":[\"ACTION\",\"COMEDY\"]").doesNotContain("Values:");
	}

	@Test
	void write_oneOfInputObject_shouldCarryTheTypesOwnSentence() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query MovieByLookup($by: MovieLookupInput!) {
				  movie(by: $by) { id title }
				}
				""")).json();

		// The branches carry the shape, and the type's sentence is the one place the
		// rule is stated in words.
		assertThat(text).contains("Identifies one movie. Give exactly one of the fields.");
	}

	@Test
	void write_listOfAnUnmappedScalar_shouldDescribeTheItemsRatherThanTheArray() {
		GraphQLSchema codes = SdlSchemaFactory.schemaFrom("""
				"A two-letter ISO 3166-1 country code, such as SI."
				scalar CountryCode

				type Query {
				  inCountries(codes: [CountryCode!]): String!
				}
				""", "codes.graphqls");

		String text = InputSchemaWriter.write(codes, file("""
				query InCountries($codes: [CountryCode!]) {
				  inCountries(codes: $codes)
				}
				""")).json();

		// The format rule belongs to one element, so it sits on items. On the array, a
		// model would read a per-element rule as a rule about the list.
		assertThat(text).contains("\"items\":{\"description\":\"A two-letter ISO 3166-1 country code, such as SI.\"}");
	}

	@Test
	void write_oneScalarInManyFieldsOfOneArgument_shouldSayItsWordsOnce() {
		GraphQLSchema cursors = SdlSchemaFactory.schemaFrom("""
				"An opaque cursor."
				scalar Cursor @specifiedBy(url: "https://example.com/cursor")

				input Page {
				  after: Cursor
				  before: Cursor
				  around: Cursor
				}

				type Query {
				  paged(page: Page): String!
				}
				""", "cursors.graphqls");

		String text = InputSchemaWriter.write(cursors, file("""
				query Paged($page: Page) {
				  paged(page: $page)
				}
				""")).json();

		// Three fields of one type carry the sentence once, because the repetition would
		// multiply with nesting.
		assertThat(text.split("An opaque cursor.", -1)).hasSize(2);
	}

	@Test
	void write_sameScalarInTwoArguments_shouldDescribeEachArgument() {
		GraphQLSchema cursors = SdlSchemaFactory.schemaFrom("""
				"An opaque cursor."
				scalar Cursor

				type Query {
				  paged(after: Cursor, before: Cursor): String!
				}
				""", "cursors.graphqls");

		String text = InputSchemaWriter.write(cursors, file("""
				query Paged($after: Cursor, $before: Cursor) {
				  paged(after: $after, before: $before)
				}
				""")).json();

		// Each argument stays readable on its own, so the words appear once per
		// argument instead of once per tool.
		assertThat(text.split("An opaque cursor.", -1)).hasSize(3);
	}

	@Test
	void write_variableOnANestedFieldArgument_shouldTakeThatArgumentsDescription() {
		GraphQLSchema nested = SdlSchemaFactory.schemaFrom("""
				type Query {
				  movies: [Movie!]!
				}

				type Movie {
				  title: String!
				  reviews(
				    "How many reviews to return, newest first."
				    first: Int
				  ): [String!]!
				}
				""", "nested.graphqls");

		String text = InputSchemaWriter.write(nested, file("""
				query MoviesWithReviews($first: Int) {
				  movies {
				    title
				    reviews(first: $first)
				  }
				}
				""")).json();

		// Reading the root fields alone would leave every variable of a nested argument
		// undescribed, although the schema has the sentence.
		assertThat(text).contains("How many reviews to return, newest first.");
	}

	@Test
	void write_variableInsideAFragment_shouldTakeItsArgumentsDescription() {
		GraphQLSchema nested = SdlSchemaFactory.schemaFrom("""
				type Query {
				  movies: [Movie!]!
				}

				type Movie {
				  title: String!
				  reviews(
				    "How many reviews to return, newest first."
				    first: Int
				  ): [String!]!
				}
				""", "nested.graphqls");

		String text = InputSchemaWriter.write(nested, file("""
				query MoviesWithReviews($first: Int) {
				  movies { ...MovieFields }
				}

				fragment MovieFields on Movie {
				  title
				  reviews(first: $first)
				}
				""")).json();

		assertThat(text).contains("How many reviews to return, newest first.");
	}

	@Test
	void write_variableInsideAMemberOfAUnion_shouldTakeItsArgumentsDescription() {
		GraphQLSchema results = SdlSchemaFactory.schemaFrom("""
				type Query {
				  newest: MovieOrSeries
				}

				union MovieOrSeries = Movie | Series

				type Movie {
				  title: String!
				  reviews(
				    "How many reviews to return, newest first."
				    first: Int
				  ): [String!]!
				}

				type Series {
				  title: String!
				}
				""", "results.graphqls");

		String text = InputSchemaWriter.write(results, file("""
				query NewestWithReviews($first: Int) {
				  newest {
				    __typename
				    ... on Movie { reviews(first: $first) }
				  }
				}
				""")).json();

		// A union leaves its fields to its members, and the walk goes on through them, so
		// a variable used under one is published with its sentence.
		assertThat(text).contains("How many reviews to return, newest first.");
	}

	@Test
	void write_variableInsideAnObjectLiteral_shouldTakeTheInputFieldsDescription() {
		GraphQLSchema filters = SdlSchemaFactory.schemaFrom("""
				input Filter {
				  "The smallest rating to accept."
				  minRating: Float
				}

				type Query {
				  movies(filter: Filter): [String!]!
				}
				""", "filters.graphqls");

		String text = InputSchemaWriter.write(filters, file("""
				query MoviesAboveRating($rating: Float) {
				  movies(filter: { minRating: $rating })
				}
				""")).json();

		// The variable fills one field of the literal, so that field's sentence is the
		// one that describes the value the model sends.
		assertThat(text).contains("The smallest rating to accept.");
	}

	@Test
	void write_variableFeedingTwoArguments_shouldKeepBothSentences() {
		GraphQLSchema both = SdlSchemaFactory.schemaFrom("""
				type Query {
				  movies(
				    "How many movies to return."
				    first: Int
				  ): [Movie!]!
				}

				type Movie {
				  reviews(
				    "How many reviews to return."
				    first: Int
				  ): [String!]!
				}
				""", "both.graphqls");

		String text = InputSchemaWriter.write(both, file("""
				query MoviesAndReviews($first: Int) {
				  movies(first: $first) {
				    reviews(first: $first)
				  }
				}
				""")).json();

		// Both sentences describe the one value the model sends, and dropping them would
		// leave the argument without a description.
		assertThat(text).contains("How many movies to return.").contains("How many reviews to return.");
	}

	@Test
	void write_variableFeedingTheSameArgumentTwice_shouldPublishItsSentenceOnce() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query BestAndTheirTitles($first: Int) {
				  best: topRatedMovies(first: $first) { id }
				  titles: topRatedMovies(first: $first) { title }
				}
				""")).json();

		// Every usage contributes its sentence, so one argument used under two aliases
		// would describe the one value with the same sentence twice.
		assertThat(text).contains("\"description\":\"How many movies to return.\"");
	}

	@Test
	void write_variableFeedingTwoArgumentsOneOfThemTwice_shouldKeepEachSentenceOnceInTheOrderMet() {
		GraphQLSchema both = SdlSchemaFactory.schemaFrom("""
				type Query {
				  movies(
				    "How many movies to return."
				    first: Int
				  ): [Movie!]!
				}

				type Movie {
				  reviews(
				    "How many reviews to return."
				    first: Int
				  ): [String!]!
				}
				""", "both.graphqls");

		String text = InputSchemaWriter.write(both, file("""
				query MoviesAndReviews($first: Int) {
				  movies(first: $first) {
				    reviews(first: $first)
				  }
				  more: movies(first: $first) {
				    reviews(first: $first)
				  }
				}
				""")).json();

		assertThat(text).contains("\"description\":\"How many movies to return.\\nHow many reviews to return.\"");
	}

	@Test
	void write_idVariable_shouldAcceptAStringOrAnInteger() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query MovieById($id: ID!) {
				  node(id: $id) { id }
				}
				""")).json();

		// GraphQL's input coercion takes "any string (such as "4") or integer (such as 4
		// or -4)", and the MCP SDK validates arguments against this schema before the
		// handler runs, so publishing string alone would refuse a call the API would
		// take.
		assertThat(text).contains("\"id\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}")
			.contains("\"required\":[\"id\"]");
	}

	@Test
	void write_nullableIdVariable_shouldAddTheNullBranchBeside() {
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query MovieById($id: ID) {
				  byId(id: $id)
				}
				""")).json();

		assertThat(text)
			.contains("\"id\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_idInsideAnInputObject_shouldAcceptBothFormsThere() {
		String text = InputSchemaWriter.write(MOVIES, file("""
				query MovieByLookup($by: MovieLookupInput!) {
				  movie(by: $by) { id title }
				}
				""")).json();

		assertThat(text).contains("\"id\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]}");
	}

	@Test
	void write_configuredScalar_shouldPublishTheFragmentInsideTheNullableWrapper() {
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom("""
				scalar Stamp

				type Query { reviews(since: Stamp): String! }
				""", "file:/schema/stamp.graphqls");
		ScalarSchemas configured = ScalarSchemas.of(scalars,
				Map.of("Stamp",
						Map.of("type", "string", "format", "date-time", "description",
								"An RFC 3339 timestamp, for example 2026-09-18T10:00:00Z.")),
				new OperationDiagnostics());

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Reviews($since: Stamp) { reviews(since: $since) }
				"""), configured);

		// GATool owns the wrapper, so the fragment becomes the typed branch and the null
		// branch is written around it from the GraphQL type.
		assertThat(written.json()).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{"
				+ "\"since\":{\"anyOf\":[{\"type\":\"string\",\"format\":\"date-time\"}," + "{\"type\":\"null\"}],"
				+ "\"description\":\"An RFC 3339 timestamp, for example 2026-09-18T10:00:00Z.\"}},"
				+ "\"required\":[],\"additionalProperties\":false}");
		// A scalar a fragment described stays out of the startup warning.
		assertThat(written.unmappedScalars()).isEmpty();
	}

	@Test
	void write_configuredScalarWithTwoWireForms_shouldPublishEachBranchBesideNull() {
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom("""
				scalar Long

				type Query { reviews(id: Long): String! }
				""", "file:/schema/long.graphqls");
		ScalarSchemas configured = ScalarSchemas.of(scalars,
				Map.of("Long",
						Map.of("anyOf",
								List.of(Map.of("type", "integer"),
										Map.of("type", "string", "pattern", "^-?(0|[1-9][0-9]{0,18})$")),
								"description", "A 64-bit signed integer. Send a large value as a quoted string.")),
				new OperationDiagnostics());

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Reviews($id: Long) { reviews(id: $id) }
				"""), configured);

		// A 64-bit value travels either as a number or as a quoted string, because a JSON
		// reader using IEEE 754 binary64 changes a number above 2^53.
		assertThat(written.json()).contains("\"anyOf\":[{\"type\":\"integer\"},"
				+ "{\"type\":\"string\",\"pattern\":\"^-?(0|[1-9][0-9]{0,18})$\"}," + "{\"type\":\"null\"}]");
	}

	@Test
	void write_configuredScalarThatTheTableAlsoDescribes_shouldPublishTheTeamsFragment() {
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom("""
				scalar Stamp @specifiedBy(url: "https://scalars.graphql.org/andimarek/date-time")

				type Query { reviews(since: Stamp): String! }
				""", "file:/schema/stamp.graphqls");
		ScalarSchemas configured = ScalarSchemas.of(scalars,
				Map.of("Stamp", Map.of("type", "integer", "description", "Seconds since the epoch.")),
				new OperationDiagnostics());

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Reviews($since: Stamp) { reviews(since: $since) }
				"""), configured);

		// The table holds what GATool read from a published specification, and the API
		// owns the API, so a schema that declares the URL and answers with epoch seconds
		// publishes what the fragment says. The URL goes with the fragment it
		// contradicts, because pointing a model at a string format beside an integer
		// property is worse than leaving the format unsaid.
		assertThat(written.json()).contains("\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}]")
			.contains("\"description\":\"Seconds since the epoch.\"")
			.doesNotContain("date-time");
	}

	@Test
	void write_fragmentSayingOnlyWhatTheValueMeans_shouldLeaveThePropertyEmpty() {
		// The JSON scalar case: the value is any JSON value, and the empty schema is the
		// accurate way to say so. Wrapping an empty branch beside null would publish
		// {"anyOf":[{},{"type":"null"}]}, which says the same thing three times over.
		GraphQLSchema scalars = SdlSchemaFactory.schemaFrom("""
				scalar Payload

				type Query { send(payload: Payload): String! }
				""", "file:/schema/payload.graphqls");
		ScalarSchemas configured = ScalarSchemas.of(scalars,
				Map.of("Payload", Map.of("description", "Arbitrary JSON, whose keys depend on the provider.")),
				new OperationDiagnostics());

		InputSchema written = InputSchemaWriter.write(scalars, file("""
				query Send($payload: Payload) { send(payload: $payload) }
				"""), configured);

		assertThat(written.json()).isEqualTo(DIALECT + "\"type\":\"object\",\"properties\":{"
				+ "\"payload\":{\"description\":\"Arbitrary JSON, whose keys depend on the provider.\"}},"
				+ "\"required\":[],\"additionalProperties\":false}");
	}

	@Test
	void write_aDefaultThatFailsItsOwnProperty_shouldBeLeftOutAndReported() {
		// The MCP SDK validates arguments against this property before the call runs, so
		// publishing a default the property refuses gets a model refused for sending back
		// the value GATool told it to use.
		InputSchema written = InputSchemaWriter.write(CONTRADICTING, file("""
				query rows($n: Long = 42, $at: Stamp = 1700000000) {
				  rows(n: $n, at: $at) { id }
				}
				"""));

		// Both scalars publish as strings, from their @specifiedBy pages, while the
		// defaults are numbers, so neither default satisfies the property beside it.
		assertThat(written.json()).contains("\"type\":\"string\"");
		assertThat(written.json()).doesNotContain("\"default\"");
		assertThat(written.refusedDefaults()).containsExactlyInAnyOrder("n = 42", "at = 1700000000");
	}

	@Test
	void write_anInputFieldDefaultThatFailsItsOwnProperty_shouldBeLeftOutAndReported() {
		// The same trap one level down: a model reading a field default the property
		// refuses and sending it back would be refused before the call reaches the API.
		GraphQLSchema contradictingField = SdlSchemaFactory.schemaFrom("""
				scalar Long @specifiedBy(url: "https://scalars.graphql.org/jakobmerrild/long")

				input RowFilter {
				  n: Long = 42
				  label: String = "rows"
				}

				type Row { id: ID! }
				type Query { rows(filter: RowFilter): [Row!]! }
				""", "classpath:contradicting-field.graphqls");

		InputSchema written = InputSchemaWriter.write(contradictingField, file("""
				query rows($filter: RowFilter) {
				  rows(filter: $filter) { id }
				}
				"""));

		// Long publishes as a string from its @specifiedBy page, so the number 42 fails
		// the property beside it, while the string default of label passes.
		assertThat(written.json()).contains("\"default\":\"rows\"").doesNotContain("\"default\":42");
		assertThat(written.refusedFieldDefaults()).containsExactly("RowFilter.n = 42");
	}

	@Test
	void write_aDefaultOutsideTheFragmentsEnumOrPattern_shouldBeLeftOutAndReported() {
		// A check of the JSON type alone would publish "default": "blue" for $colour:
		// Colour = "blue" against enum [red, green], which the SDK's validator refuses
		// before the API sees the call. enum and pattern assert exactly as type does, for
		// a variable's default and for a field's.
		InputSchema written = InputSchemaWriter.write(COLOURS, file("""
				query Paint($colour: Colour = "blue", $code: Code = "ABC", $paint: Paint) {
				  paint(colour: $colour, code: $code, paint: $paint)
				}
				"""), colourFragments());

		assertThat(written.json()).doesNotContain("\"default\"");
		assertThat(written.refusedDefaults()).containsExactly("colour = \"blue\"", "code = \"ABC\"");
		assertThat(written.refusedFieldDefaults()).containsExactly("Paint.colour = \"blue\"", "Paint.code = \"ABC\"");
	}

	@Test
	void write_aDefaultInsideTheFragmentsEnumAndPattern_shouldStayPublished() {
		InputSchema written = InputSchemaWriter.write(COLOURS, file("""
				query Paint($colour: Colour = "red", $code: Code = "abc", $paint: Paint) {
				  paint(colour: $colour, code: $code, paint: $paint)
				}
				"""), colourFragments());

		assertThat(written.json()).contains("\"default\":\"red\"").contains("\"default\":\"abc\"");
		assertThat(written.refusedDefaults()).isEmpty();
		// The field defaults of Paint fail either way, and they are reported for the
		// field, so they cannot hide the variable result above.
		assertThat(written.refusedFieldDefaults()).hasSize(2);
	}

	@Test
	void write_objectAndListDefaultsOnACustomScalar_shouldPublishTheirJsonForm() {
		// A JSON scalar takes any literal, and a filter written as an object literal is
		// spelled exactly as the API takes it. Without the defaults the model would read
		// a JSON argument that leaves its shape unstated.
		String text = InputSchemaWriter.write(COLOURS, file("""
				query Search($filter: JSON = { city: "Ljubljana", limit: 10, nested: { on: true } },
				        $tags: JSON = ["a", 1, null]) {
				  search(filter: $filter, tags: $tags)
				}
				""")).json();

		assertThat(text).contains("\"default\":{\"city\":\"Ljubljana\",\"limit\":10,\"nested\":{\"on\":true}}")
			.contains("\"default\":[\"a\",1,null]");
	}

	@Test
	void write_anIntegerDefaultPastALongOnAFloat_shouldPublishItAsANumber() {
		// GraphQL's Float coerces a whole literal, so this is a legal default, although
		// it is past a long.
		String text = InputSchemaWriter.write(DEFAULTS, file("""
				query Defaults($rate: Float = 10000000000000000000) {
				  defaults(rate: $rate)
				}
				""")).json();

		assertThat(text).contains("\"default\":1.0E19");
	}

	@Test
	void write_aDecimalDefaultOnACustomScalar_shouldPublishEveryDigit() {
		// A custom scalar such as Decimal takes the literal as a BigDecimal, and GATool
		// sends it to the API intact. Rounded to a double, it would read
		// 1.2345678901234567E9 for a default the operation spells to eighteen decimal
		// places.
		String text = InputSchemaWriter.write(PASS_THROUGH, file("""
				query Amounts($d: Decimal = 1234567890.123456789012345678, $e: Decimal = 2.5e3) {
				  amounts(d: $d, e: $e)
				}
				""")).json();

		assertThat(text).contains("\"default\":1234567890.123456789012345678")
			// An exponent literal is written in plain form, so every client reads the
			// same number.
			.contains("\"default\":2500");
	}

	@Test
	void write_anIntegerDefaultPastALongOnACustomScalar_shouldPublishTheDigitsOrNameTheVariable() {
		// 9223372036854775808 is one past a long. JSON carries the digits, and the API
		// reads them as the literal spells them.
		InputSchema passedThrough = InputSchemaWriter.write(PASS_THROUGH, file("""
				query Amounts($n: Long = 9223372036854775808) {
				  amounts(n: $n)
				}
				"""));

		assertThat(passedThrough.json()).contains("\"default\":9223372036854775808");
		assertThat(passedThrough.refusedDefaults()).isEmpty();

		// A configured fragment typing the scalar as an integer accepts the digits,
		// because they are an integral number whatever their size.
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas integerFragment = ScalarSchemas.of(PASS_THROUGH, Map.of("Long", Map.of("type", "integer")),
				diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		assertThat(InputSchemaWriter.write(PASS_THROUGH, file("""
				query Amounts($n: Long = 9223372036854775808) {
				  amounts(n: $n)
				}
				"""), integerFragment).json()).contains("\"type\":\"integer\"")
			.contains("\"default\":9223372036854775808");

		// The same digits against a Long whose @specifiedBy page publishes a string fail
		// the property, and startup names the variable, as it does for 42.
		InputSchema contradicting = InputSchemaWriter.write(CONTRADICTING, file("""
				query rows($n: Long = 9223372036854775808) {
				  rows(n: $n) { id }
				}
				"""));

		assertThat(contradicting.json()).doesNotContain("\"default\"");
		assertThat(contradicting.refusedDefaults()).containsExactly("n = 9223372036854775808");
	}

	@Test
	void write_aDefaultItsOwnPropertyAccepts_shouldStayPublished() {
		// The guard must leave the ordinary case alone, or every default disappears.
		InputSchema written = InputSchemaWriter.write(CONTRADICTING, file("""
				query rows($n: Long = "42") {
				  rows(n: $n) { id }
				}
				"""));

		assertThat(written.json()).contains("\"default\":\"42\"");
		assertThat(written.refusedDefaults()).isEmpty();
	}

	/**
	 * The filter graph a Hasura or PostGraphile API publishes: one boolean expression
	 * type per table, with {@code _and}, {@code _or} and {@code _not} on itself and one
	 * field per foreign key.
	 */
	private static String filterGraphSdl(int tables, int relationships) {
		StringBuilder sdl = new StringBuilder();
		for (int table = 0; table < tables; table++) {
			sdl.append("input t")
				.append(table)
				.append("_bool_exp {\n")
				.append("  _and: [t")
				.append(table)
				.append("_bool_exp!]\n")
				.append("  _or: [t")
				.append(table)
				.append("_bool_exp!]\n")
				.append("  _not: t")
				.append(table)
				.append("_bool_exp\n")
				.append("  id: String\n  name: String\n");
			for (int relationship = 1; relationship <= relationships; relationship++) {
				sdl.append("  rel")
					.append(relationship)
					.append(": t")
					.append((table + relationship) % tables)
					.append("_bool_exp\n");
			}
			sdl.append("}\n");
		}
		return sdl.append("type Row { id: ID! }\n")
			.append("type Query { rows(where: t0_bool_exp): [Row!]! }\n")
			.toString();
	}

	@Test
	void write_aNarrowInputGraph_shouldStayWhole() {
		// The case the bound must leave alone. Eight tables, one relationship each,
		// without filter combinators: 34 input object nodes and 7,417 characters.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(filterGraphSdl(8, 1), "file:/narrow.graphqls");

		InputSchema written = InputSchemaWriter.write(schema, file("""
				query Rows($where: t0_bool_exp) {
				  rows(where: $where) { id }
				}
				"""), ScalarSchemas.none());

		assertThat(written.cutTypes()).isEmpty();
		assertThat(written.json()).doesNotContain("stops here");
	}

	@Test
	void write_anInputGraphWideEnoughToExhaustTheHeap_shouldStayBoundedAndSayWhereItStopped() {
		// Twenty tables with three relationships each, the shape that runs a 2 GB heap
		// out of memory in an unbounded walk. The bounded walk writes 107,774 characters.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(filterGraphSdl(20, 3), "file:/wide.graphqls");

		InputSchema written = InputSchemaWriter.write(schema, file("""
				query Rows($where: t0_bool_exp) {
				  rows(where: $where) { id }
				}
				"""), ScalarSchemas.none());

		assertThat(written.json().length()).isLessThan(200_000);
		assertThat(written.cutTypes()).isNotEmpty();
		assertThat(written.cutTypes().getFirst()).startsWith("where at t");
		assertThat(written.json()).contains("The schema of the API names its fields, and this one stops here");
	}

	@Test
	void write_aFilterGraphOfOrdinarySize_shouldStayBounded() {
		// The cycle guard is scoped to the path: a type's name goes in on the way down
		// and comes out on the way up, so every sibling path expands the whole sub-graph
		// again and the node count grows with the number of simple paths through the
		// graph. Eight tables with three relationships each is a small API. The size
		// warning cannot save this, because it reads the schema after the walk has
		// already built it.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(filterGraphSdl(8, 3), "file:/filters.graphqls");

		String text = InputSchemaWriter.write(schema, file("""
				query Rows($where: t0_bool_exp) {
				  rows(where: $where) { id }
				}
				"""), ScalarSchemas.none()).json();

		assertThat(text.length()).describedAs("one variable's input schema, which travels in every tools/list")
			.isLessThan(200_000);
	}

	@Test
	void write_anInputTypeHoldingAnotherOfItsOwnKind_shouldStopAndSaySo() {
		// The guard against an unbounded walk, and the sentence it publishes is what a
		// model reads about a self-referencing filter. Without this test, an edit that
		// reopened the walk would show up as a stack overflow at startup.
		GraphQLSchema recursive = SdlSchemaFactory.schemaFrom("""
				input MovieWhere {
				  title: String
				  and: [MovieWhere!]
				}

				type Movie { id: ID! }
				type Query { movies(where: MovieWhere): [Movie!]! }
				""", "classpath:recursive.graphqls");

		InputSchema written = InputSchemaWriter.write(recursive, file("""
				query Movies($where: MovieWhere) {
				  movies(where: $where) { id }
				}
				"""));

		// The sentence names the fields, because a model cannot read the schema of the
		// API from a tool definition.
		assertThat(descriptionOfTheRepeat(written, "and")).isEqualTo("A MovieWhere, which holds another of its "
				+ "own kind. Its fields, as GraphQL writes them: title: String, and: [MovieWhere!].");
		// The repeat writes the sentence and stops there, so the fields of the type are
		// written once and the walk ends.
		assertThat(written.json().split("\"title\"")).hasSize(2);
	}

	@Test
	void write_aRepeatedInputType_shouldNameEachFieldWithTheTypeTheSchemaDeclares() {
		GraphQLSchema recursive = SdlSchemaFactory.schemaFrom("""
				enum Genre { ACTION COMEDY }

				input MovieWhere {
				  label: String!
				  tags: [String!]
				  genre: Genre = ACTION
				  year: Int @deprecated(reason: "Use released.")
				  not: MovieWhere
				}

				type Movie { id: ID! }
				type Query { movies(where: MovieWhere): [Movie!]! }
				""", "classpath:recursive-types.graphqls");

		InputSchema written = InputSchemaWriter.write(recursive, file("""
				query Movies($where: MovieWhere) {
				  movies(where: $where) { id }
				}
				"""));

		// The type is written as the schema declares it, so a model reads which field is
		// required and which takes a list. A deprecated field is marked, as it is where
		// the fields are written in full.
		assertThat(descriptionOfTheRepeat(written, "not")).isEqualTo("A MovieWhere, which holds another of its "
				+ "own kind. Its fields, as GraphQL writes them: label: String!, tags: [String!], genre: Genre, "
				+ "year: Int (deprecated), not: MovieWhere.");
	}

	@Test
	void write_aRepeatedInputTypeOfMoreFieldsThanTheSentenceNames_shouldNameTheFirstAndCountTheRest() {
		GraphQLSchema recursive = SdlSchemaFactory.schemaFrom("""
				input Wide {
				  not: Wide
				  f1: String
				  f2: String
				  f3: String
				  f4: String
				  f5: String
				  f6: String
				  f7: String
				  f8: String
				  f9: String
				  f10: String
				  f11: String
				}

				type Row { id: ID! }
				type Query { rows(where: Wide): [Row!]! }
				""", "classpath:recursive-wide.graphqls");

		InputSchema written = InputSchemaWriter.write(recursive, file("""
				query Rows($where: Wide) {
				  rows(where: $where) { id }
				}
				"""));

		// A filter type of a generated API holds a field for each column, so the sentence
		// names the first ten. The rest are counted, and the object around the repeat is
		// the Wide written in full, which lists every one.
		assertThat(descriptionOfTheRepeat(written, "not")).isEqualTo("A Wide, which holds another of its own "
				+ "kind. The first 10 of its 12 fields, as GraphQL writes them: not: Wide, f1: String, f2: String, "
				+ "f3: String, f4: String, f5: String, f6: String, f7: String, f8: String, f9: String. The Wide "
				+ "this one sits inside lists all 12.");
	}

	@Test
	void write_anInputTypeRepeatedTwiceInOneVariable_shouldNameTheFieldsOnceAndPointThereAfterwards() {
		GraphQLSchema recursive = SdlSchemaFactory.schemaFrom("""
				input MovieWhere {
				  title: String
				  and: [MovieWhere!]
				  not: MovieWhere
				}

				type Movie { id: ID! }
				type Query { movies(where: MovieWhere, except: MovieWhere): [Movie!]! }
				""", "classpath:recursive-twice.graphqls");

		InputSchema written = InputSchemaWriter.write(recursive, file("""
				query Movies($where: MovieWhere, $except: MovieWhere) {
				  movies(where: $where, except: $except) { id }
				}
				"""));

		// The repeats are most of the nodes of a filter graph, so naming the fields at
		// each of them would multiply the text. The first repeat of a type names them and
		// the next one points there.
		assertThat(descriptionOfTheRepeat(written, "where", "and")).isEqualTo("A MovieWhere, which holds another "
				+ "of its own kind. Its fields, as GraphQL writes them: title: String, and: [MovieWhere!], "
				+ "not: MovieWhere.");
		assertThat(descriptionOfTheRepeat(written, "where", "not")).isEqualTo("A MovieWhere, which holds another "
				+ "of its own kind. The first MovieWhere described this way names its fields.");
		// Each variable is read on its own, so the second one names the fields again.
		assertThat(descriptionOfTheRepeat(written, "except", "and"))
			.isEqualTo(descriptionOfTheRepeat(written, "where", "and"));
	}

	@Test
	void write_aRepeatedOneOfInputType_shouldSayThatItTakesOneOfTheFieldsItNames() {
		GraphQLSchema recursive = SdlSchemaFactory.schemaFrom("""
				input Lookup @oneOf {
				  id: ID
				  either: [Lookup!]
				}

				type Row { id: ID! }
				type Query { rows(by: Lookup): [Row!]! }
				""", "classpath:recursive-one-of.graphqls");

		InputSchema written = InputSchemaWriter.write(recursive, file("""
				query Rows($by: Lookup) {
				  rows(by: $by) { id }
				}
				"""));

		// The branches of the first occurrence carry the rule as a shape, and the repeat
		// is an open object, so the sentence is the one place the rule is said there.
		assertThat(written.json()).contains("A Lookup, which holds another of its own kind and takes exactly one "
				+ "of its fields. Its fields, as GraphQL writes them: id: ID, either: [Lookup!].");
	}

	private static String descriptionOfTheRepeat(InputSchema written, String fieldName) {
		return descriptionOfTheRepeat(written, "where", fieldName);
	}

	// The description of the open object written where the type repeats, which sits
	// under the items of a list field and in the first branch of a nullable one.
	private static String descriptionOfTheRepeat(InputSchema written, String variableName, String fieldName) {
		JsonNode field = JsonMapper.builder()
			.build()
			.readTree(written.json())
			.path("properties")
			.path(variableName)
			.path("anyOf")
			.path(0)
			.path("properties")
			.path(fieldName);
		JsonNode typed = field.path("anyOf").path(0);
		JsonNode repeat = typed.has("items") ? typed.path("items") : typed;
		return repeat.path("description").asString();
	}

	// Colour takes two names and Code lowercase letters, both stated by the API's owner.
	private static ScalarSchemas colourFragments() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas fragments = ScalarSchemas.of(COLOURS,
				Map.of("Colour", Map.of("type", "string", "enum", List.of("red", "green")), "Code",
						Map.of("type", "string", "pattern", "^[a-z]+$")),
				diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		return fragments;
	}

	private static OperationFile file(String text) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ParsedFile parsed = new OperationFileParser().parse(
				new OperationSource("file:/mcp/TopRatedMovies.graphql", text, Set.of(ToolExposureType.MCP)),
				diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		assertThat(parsed).isInstanceOf(OperationFile.class);
		return (OperationFile) parsed;
	}

}
