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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLSchema;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.internal.operation.OperationDiagnostics;
import io.gatool.core.internal.operation.OperationFile;
import io.gatool.core.internal.operation.OperationFileParser;
import io.gatool.core.internal.operation.OperationSource;
import io.gatool.core.internal.operation.OperationValidator;
import io.gatool.core.internal.operation.ParsedFile;
import io.gatool.core.internal.operation.ToolExposureType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the output schema GATool publishes, at the writer, without a server around it.
 *
 * <p>
 * The published schema is a promise a client enforces: the MCP Java SDK validates a
 * result against it and replaces a response that fails with a tool error. So a schema
 * that describes less than the API sends, or demands more, costs the model the data it
 * asked for.
 */
class OutputSchemaWriterTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final GraphQLSchema FRAGMENTS = SdlSchemaFactory.schemaFrom("""
			type Query { movie: Movie }
			type Movie { title: String!  studio: Studio }
			type Studio { name: String  country: String  founded: Int }
			""", "classpath:fragments.graphqls");

	private static final GraphQLSchema ABSTRACT = SdlSchemaFactory.schemaFrom("""
			type Query { node(id: ID!): Node  movie(id: ID!): Movie }
			interface Node { id: ID!  name: String }
			type Movie implements Node { id: ID!  name: String  rating: Float }
			type Person implements Node { id: ID!  name: String }
			""", "classpath:abstract.graphqls");

	private static final GraphQLSchema UNIONS = SdlSchemaFactory.schemaFrom("""
			type Query { feed: [Named!]! }
			interface Named { name: String }
			union SearchResult = Movie | Person
			type Movie implements Named { name: String  released: Boolean }
			type Person implements Named { name: String }
			""", "classpath:unions.graphqls");

	private static final GraphQLSchema LISTS = SdlSchemaFactory.schemaFrom("""
			type Query { movies: [Movie!]! }
			type Movie { id: ID!  title: String! }
			""", "classpath:lists.graphqls");

	private static final GraphQLSchema PAYMENTS = SdlSchemaFactory.schemaFrom("""
			type Query { pay: PayResult! }
			union PayResult = Payment | Declined
			type Payment { id: ID! }
			type Declined { message: String! }
			""", "classpath:payments.graphqls");

	// Every implementation carries the interface's own fields, and owner nests the
	// interface inside itself, which is the Relay shape a Node hierarchy has.
	private static final GraphQLSchema OWNED = SdlSchemaFactory.schemaFrom("""
			interface Named { name: String }
			interface Node implements Named { id: ID!  name: String  owner: Node }
			type A implements Node & Named { id: ID!  name: String  owner: Node }
			type B implements Node & Named { id: ID!  name: String  owner: Node }
			type C implements Node & Named { id: ID!  name: String  owner: Node }
			type Query { node(id: ID!): Node }
			""", "classpath:owned.graphqls");

	// A union with an object position and an interface position beside it, for a
	// fragment written once for search results and spread over a single movie.
	private static final GraphQLSchema RESULTS = SdlSchemaFactory.schemaFrom("""
			type Query { movie: Movie  node: Node }
			interface Node { id: ID! }
			type Movie implements Node { id: ID!  title: String! }
			type Person { name: String! }
			union Result = Movie | Person
			""", "classpath:results.graphqls");

	// Custom scalars at each kind of position a result carries one: a field of an
	// object, the items of a list, a nested object and a member of a union. Stamp cites
	// a specification the input side maps, and every test leaves it without a fragment.
	private static final GraphQLSchema SCALARS = SdlSchemaFactory.schemaFrom("""
			scalar DateTime
			scalar Currency
			scalar Long
			scalar Stamp @specifiedBy(url: "https://scalars.graphql.org/andimarek/date-time")

			type Query { order: Order  latest: Event }
			type Order {
			  placedAt: DateTime
			  paidAt: DateTime
			  currency: Currency
			  total: Long
			  stamps: [DateTime!]!
			  price: Price
			  seenAt: Stamp
			}
			type Price { at: DateTime }
			union Event = Order | Refund
			type Refund { at: DateTime }
			""", "classpath:scalars.graphqls");

	private static final String TIMESTAMP = "{\"anyOf\":[{\"type\":\"string\",\"format\":\"date-time\"},"
			+ "{\"type\":\"null\"}]";

	private static final Map<String, Object> DATE_TIME = Map.of("DateTime",
			Map.of("type", "string", "format", "date-time", "description", "An RFC 3339 timestamp."));

	@Test
	void write_aScalarWithAnInputFragment_shouldPublishItsTypeAndFormatAndDescribeItAtTheFirstPosition() {
		// The fragment is written once, for the arguments, and a result carries the same
		// wire form, so the output schema says what the input schema says about it. The
		// words are written where the scalar appears first and left out after that,
		// because every repeat is paid for in tokens by whoever reads the listing.
		JsonNode order = orderOf("""
				query Order { order { placedAt paidAt } }
				""", DATE_TIME, Map.of());

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/placedAt"))
				.hasToString(TIMESTAMP + ",\"description\":\"An RFC 3339 timestamp.\"}");
			softly.assertThat(order.at("/properties/paidAt")).hasToString(TIMESTAMP + "}");
		});
	}

	@Test
	void write_twoOperationsReadingOneScalar_shouldEachDescribeItAtTheirOwnFirstPosition() {
		// A model reads one tool's output schema without the others, so each schema
		// carries the words once.
		ScalarSchemas schemas = published(DATE_TIME, Map.of());

		String placed = OutputSchemaWriter.write(SCALARS, validFile(SCALARS, """
				query Placed { order { placedAt } }
				"""), schemas).json();
		String paid = OutputSchemaWriter.write(SCALARS, validFile(SCALARS, """
				query Paid { order { paidAt } }
				"""), schemas).json();

		assertThat(count(placed, "An RFC 3339 timestamp.")).isEqualTo(1);
		assertThat(count(paid, "An RFC 3339 timestamp.")).isEqualTo(1);
	}

	@Test
	void write_anInputFragmentCarryingAnEnum_shouldPublishItsTypeAndLeaveTheEnumOnTheInputSide() {
		// An enum in a fragment says what a model should send, and an operator may list
		// fewer values than the API holds. Published for a result, it would refuse a
		// value the API returned, after the API ran the call.
		JsonNode order = orderOf("""
				query Order { order { currency } }
				""", Map.of("Currency", Map.of("type", "string", "enum", List.of("EUR", "USD"))), Map.of());

		assertThat(order.at("/properties/currency"))
			.hasToString("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_anInputFragmentCarryingAPattern_shouldPublishItsTypeAndFormatAndLeaveThePatternOnTheInputSide() {
		// The pattern asks a model for 2026-09-28, and the API may answer
		// 2026-09-28T00:00:00Z for the same scalar.
		JsonNode order = orderOf("""
				query Order { order { placedAt } }
				""",
				Map.of("DateTime",
						Map.of("type", "string", "format", "date", "pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}$")),
				Map.of());

		assertThat(order.at("/properties/placedAt"))
			.hasToString("{\"anyOf\":[{\"type\":\"string\",\"format\":\"date\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_anInputFragmentWhoseBranchesCarryAnEnumAndAPattern_shouldLeaveBothOutOfEachBranch() {
		JsonNode order = orderOf("""
				query Order { order { total } }
				""",
				Map.of("Long",
						Map.of("anyOf",
								List.of(Map.of("type", "integer", "enum", List.of(1, 2)),
										Map.of("type", "string", "pattern", "^-?(0|[1-9][0-9]{0,18})$")),
								"description", "A 64-bit signed integer.")),
				Map.of());

		assertThat(order.at("/properties/total"))
			.hasToString("{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"string\"},{\"type\":\"null\"}],"
					+ "\"description\":\"A 64-bit signed integer.\"}");
	}

	@Test
	void write_branchesThatBecomeIdentical_shouldBePublishedOnce() {
		// The two branches differ in their patterns alone, so without the patterns they
		// are one branch, and the position has one typed branch beside null.
		JsonNode order = orderOf("""
				query Order { order { total } }
				""",
				Map.of("Long", Map.of("anyOf", List.of(Map.of("type", "string", "pattern", "^(0|[1-9][0-9]{0,18})$"),
						Map.of("type", "string", "pattern", "^-[1-9][0-9]{0,18}$")))),
				Map.of());

		assertThat(order.at("/properties/total"))
			.hasToString("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_anInputFragmentHoldingAnEnumAlone_shouldPublishTheEmptySchema() {
		// Without the enum the fragment does not state anything about a result, so the
		// position keeps the schema that accepts any JSON value.
		JsonNode order = orderOf("""
				query Order { order { currency } }
				""", Map.of("Currency", Map.of("enum", List.of("EUR", "USD"))), Map.of());

		assertThat(order.at("/properties/currency")).hasToString("{}");
	}

	@Test
	void write_anInputFragmentHoldingADescriptionAlone_shouldPublishTheEmptySchemaWithItsWords() {
		JsonNode order = orderOf("""
				query Order { order { placedAt paidAt } }
				""", Map.of("DateTime", Map.of("description", "A moment, in the form the provider chose.")), Map.of());

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/placedAt"))
				.hasToString("{\"description\":\"A moment, in the form the provider chose.\"}");
			softly.assertThat(order.at("/properties/paidAt")).hasToString("{}");
		});
	}

	@Test
	void write_anInputFragmentWithOneBranchThatStatesAnEnumAlone_shouldPublishTheEmptySchema() {
		// Without its enum the first branch accepts every value, so the anyOf as a whole
		// does, and the empty schema says the same in fewer characters.
		JsonNode order = orderOf("""
				query Order { order { total } }
				""",
				Map.of("Long",
						Map.of("anyOf", List.of(Map.of("enum", List.of("MAX", "MIN")), Map.of("type", "integer")))),
				Map.of());

		assertThat(order.at("/properties/total")).hasToString("{}");
	}

	@Test
	void write_anOverride_shouldBePublishedAsWrittenInPlaceOfTheInputFragment() {
		// The override is what the operator wrote about results, so its enum and its
		// pattern are published, and the input fragment stays on the input side.
		JsonNode order = orderOf("""
				query Order { order { currency total } }
				""",
				Map.of("Currency",
						Map.of("type", "string", "enum", List.of("EUR", "USD"), "description",
								"The currency to price in."),
						"Long", Map.of("type", "integer")),
				Map.of("Currency", Map.of("type", "string", "enum", List.of("EUR", "USD", "GBP"), "pattern",
						"^[A-Z]{3}$", "description", "An ISO 4217 code."), "Long", Map.of("type", "string")));

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/currency"))
				.hasToString("{\"anyOf\":[{\"type\":\"string\",\"pattern\":\"^[A-Z]{3}$\","
						+ "\"enum\":[\"EUR\",\"USD\",\"GBP\"]},{\"type\":\"null\"}],"
						+ "\"description\":\"An ISO 4217 code.\"}");
			softly.assertThat(order.at("/properties/total"))
				.hasToString("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
		});
	}

	@Test
	void write_anOverrideWithSeveralBranches_shouldPublishEachBesideNull() {
		JsonNode order = orderOf("""
				query Order { order { total } }
				""", Map.of(), Map.of("Long", Map.of("anyOf",
				List.of(Map.of("type", "integer"), Map.of("type", "string", "pattern", "^-?(0|[1-9][0-9]{0,18})$")))));

		assertThat(order.at("/properties/total")).hasToString("{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"string\","
				+ "\"pattern\":\"^-?(0|[1-9][0-9]{0,18})$\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_anOverrideForAScalarWithoutAnInputFragment_shouldBePublished() {
		JsonNode order = orderOf("""
				query Order { order { placedAt } }
				""", Map.of(),
				Map.of("DateTime", Map.of("type", "integer", "description", "Seconds since 1970-01-01T00:00:00Z.")));

		assertThat(order.at("/properties/placedAt"))
			.hasToString("{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}],"
					+ "\"description\":\"Seconds since 1970-01-01T00:00:00Z.\"}");
	}

	@Test
	void write_anOverrideHoldingADescriptionAlone_shouldGiveBackTheEmptySchema() {
		// The way to switch the reuse off for one scalar: the override states the words
		// and leaves the type out, so the input fragment's type stays on the input side.
		JsonNode order = orderOf("""
				query Order { order { placedAt paidAt } }
				""", DATE_TIME, Map.of("DateTime", Map.of("description", "A moment, in one of several forms.")));

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/placedAt"))
				.hasToString("{\"description\":\"A moment, in one of several forms.\"}");
			softly.assertThat(order.at("/properties/paidAt")).hasToString("{}");
		});
	}

	@Test
	void write_aScalarInsideAList_shouldDescribeTheItems() {
		JsonNode order = orderOf("""
				query Order { order { stamps } }
				""", DATE_TIME, Map.of());

		assertThat(order.at("/properties/stamps")).hasToString("{\"anyOf\":[{\"type\":\"array\",\"items\":" + TIMESTAMP
				+ ",\"description\":\"An RFC 3339 timestamp.\"}},{\"type\":\"null\"}]}");
	}

	@Test
	void write_aScalarInsideANestedObject_shouldDescribeItThere() {
		JsonNode order = orderOf("""
				query Order { order { price { at } paidAt } }
				""", DATE_TIME, Map.of());

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/price/anyOf/0/properties/at"))
				.hasToString(TIMESTAMP + ",\"description\":\"An RFC 3339 timestamp.\"}");
			softly.assertThat(order.at("/properties/paidAt")).hasToString(TIMESTAMP + "}");
		});
	}

	@Test
	void write_aScalarInsideAUnionBranch_shouldDescribeItInTheFirstBranchThatCarriesIt() {
		JsonNode latest = data(OutputSchemaWriter.write(SCALARS, validFile(SCALARS, """
				query Latest {
				  latest {
				    __typename
				    ... on Order { placedAt }
				    ... on Refund { at }
				  }
				}
				"""), published(DATE_TIME, Map.of()))).at("/properties/latest/anyOf");

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(latest.at("/0/properties/placedAt"))
				.hasToString(TIMESTAMP + ",\"description\":\"An RFC 3339 timestamp.\"}");
			softly.assertThat(latest.at("/1/properties/at")).hasToString(TIMESTAMP + "}");
		});
	}

	@Test
	void write_aScalarOnlyItsSpecificationDescribes_shouldPublishTheTypeAndTheFormatOfTheSpecification() {
		// A specification says how a scalar is written on the wire, in a result as in an
		// argument, so a scalar that cites one GATool has read says the same on the way
		// out as on the way in. A scalar without a specification and without a fragment
		// keeps the empty schema.
		JsonNode order = orderOf("""
				query Order { order { seenAt total } }
				""", Map.of(), Map.of());

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/seenAt")).hasToString(TIMESTAMP + "}");
			softly.assertThat(order.at("/properties/total")).hasToString("{}");
		});
	}

	@Test
	void write_aSpecifiedScalarWithAnInputFragment_shouldFollowTheFragment() {
		// A configured fragment wins over the table on the input side, because it
		// describes the API being called, and a result follows the same order.
		JsonNode order = orderOf("""
				query Order { order { seenAt } }
				""", Map.of("Stamp", Map.of("type", "integer")), Map.of());

		assertThat(order.at("/properties/seenAt"))
			.hasToString("{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_aSpecifiedScalarWhoseInputFragmentHoldsAnEnumAlone_shouldPublishTheEmptySchema() {
		// The operator took the scalar over with a fragment, so the table stays out of
		// it, and the fragment leaves the type unstated on both sides.
		JsonNode order = orderOf("""
				query Order { order { seenAt } }
				""", Map.of("Stamp", Map.of("enum", List.of("2026-09-28T00:00:00.000Z"))), Map.of());

		assertThat(order.at("/properties/seenAt")).hasToString("{}");
	}

	@Test
	void write_aSpecifiedScalarWithAnOverride_shouldPublishTheOverride() {
		// The way out for an API that cites a specification and returns another form.
		JsonNode order = orderOf("""
				query Order { order { seenAt } }
				""", Map.of(), Map.of("Stamp", Map.of("type", "integer")));

		assertThat(order.at("/properties/seenAt"))
			.hasToString("{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}]}");
	}

	@Test
	void write_aSpecifiedScalarWithAnOverrideHoldingADescriptionAlone_shouldGiveBackTheEmptySchema() {
		JsonNode order = orderOf("""
				query Order { order { seenAt } }
				""", Map.of(), Map.of("Stamp", Map.of("description", "A moment, in one of several forms.")));

		assertThat(order.at("/properties/seenAt"))
			.hasToString("{\"description\":\"A moment, in one of several forms.\"}");
	}

	@Test
	void write_aScalarCitingASpecificationWithoutAJsonTypeOrAPageGAToolHasYetToRead_shouldKeepTheEmptySchema() {
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				scalar Anything @specifiedBy(url: "https://scalars.graphql.org/chillicream/any")
				scalar Unread @specifiedBy(url: "https://example.com/scalars/unread")

				type Query { order: Order }
				type Order { anything: Anything  unread: Unread }
				""", "classpath:specified.graphqls");

		JsonNode order = data(OutputSchemaWriter.write(schema, validFile(schema, """
				query Order { order { anything unread } }
				"""), ScalarSchemas.none())).at("/properties/order/anyOf/0");

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(order.at("/properties/anything")).hasToString("{}");
			softly.assertThat(order.at("/properties/unread")).hasToString("{}");
		});
	}

	@Test
	void write_twoFragmentsSelectingOneKey_shouldDescribeTheFieldsOfBoth() {
		// 6.4.3 MergeSelectionSets appends the second occurrence's selections to the
		// first. Replacing the property node would describe only whichever fragment came
		// last, so the model would be told a field it can read is absent.
		String text = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M { movie { ...Basic ...Credits } }
				fragment Basic on Movie { studio { name } }
				fragment Credits on Movie { studio { country } }
				""")).json();

		assertThat(text).contains("\"name\"").contains("\"country\"");
	}

	@Test
	void write_twoFragmentsSelectingOneKey_shouldDescribeTheSameFieldsInEitherOrder() {
		String basicFirst = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M { movie { ...Basic ...Credits } }
				fragment Basic on Movie { studio { name } }
				fragment Credits on Movie { studio { country } }
				""")).json();
		String creditsFirst = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M { movie { ...Credits ...Basic } }
				fragment Basic on Movie { studio { name } }
				fragment Credits on Movie { studio { country } }
				""")).json();

		assertThat(basicFirst).contains("\"name\"").contains("\"country\"");
		assertThat(creditsFirst).contains("\"name\"").contains("\"country\"");
	}

	@Test
	void write_aKeySelectedTwiceWithOneCopySwitched_shouldLeaveTheSwitchedCopysKeysOutOfRequired() {
		// studio is selected twice. The first copy always arrives and carries name. The
		// second carries country and founded, and arrives only when the model passes
		// true. With false the API answers studio with name alone, so requiring country
		// would refuse a correct response.
		String text = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M($withDetail: Boolean!) {
				  movie {
				    studio { name }
				    studio @include(if: $withDetail) { country founded }
				  }
				}
				""")).json();

		assertThat(text).contains("\"country\"").contains("\"founded\"");
		assertThat(requiredNames(text)).contains("name").doesNotContain("country").doesNotContain("founded");
	}

	@Test
	void write_aKeySelectedTwiceWithOneCopySkipped_shouldLeaveTheSkippedCopysKeysOutOfRequired() {
		String text = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M($withoutDetail: Boolean!) {
				  movie {
				    studio { name }
				    studio @skip(if: $withoutDetail) { country founded }
				  }
				}
				""")).json();

		assertThat(requiredNames(text)).contains("name").doesNotContain("country").doesNotContain("founded");
	}

	@Test
	void write_aKeySelectedTwiceWithNeitherCopySwitched_shouldStillRequireEveryKey() {
		// The guard above must leave the ordinary case alone: without a directive both
		// copies always arrive, so every key is as certain as a single selection.
		String text = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M { movie { studio { name } studio { country founded } } }
				""")).json();

		assertThat(requiredNames(text)).contains("name").contains("country").contains("founded");
	}

	@Test
	void write_aKeySelectedOnceUnderASwitch_shouldRequireItsOwnKeysWheneverItArrives() {
		// movie arrives only when the model passes true, and then its whole selection set
		// arrives with it: whenever movie is there, studio is, and whenever studio is,
		// name is. Passing the switched copy's certainty into its selections would leave
		// studio and name optional, and the schema would then describe keys that may be
		// missing from an object that always carries them.
		String text = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M($x: Boolean!) { movie @include(if: $x) { studio { name } } }
				""")).json();

		assertThat(requiredNames(text)).contains("studio").contains("name").doesNotContain("movie");
	}

	@Test
	void write_aKeyReachedThroughASwitchedCopyAlone_shouldRequireItsOwnKeysWheneverItArrives() {
		// studio comes through the switched copy alone, so it may be missing from movie.
		// Once it is there, name is there too, because that one selection is the only
		// way studio reaches the answer.
		String text = OutputSchemaWriter.write(FRAGMENTS, file("""
				query M($x: Boolean!) { movie { title } movie @include(if: $x) { studio { name } } }
				""")).json();

		assertThat(requiredNames(text)).contains("movie").contains("title").contains("name").doesNotContain("studio");
	}

	@Test
	void write_anInterfacePositionSelectedOnceUnderASwitch_shouldRequireItsOwnKeysWheneverItArrives() {
		// The same rule at an abstract position: the switch above node decides whether
		// node arrives, and id and name arrive with it every time it does.
		String text = OutputSchemaWriter.write(ABSTRACT, file("""
				query A($id: ID!, $x: Boolean!) { node(id: $id) @include(if: $x) { id name } }
				""")).json();

		assertThat(requiredNames(text)).contains("id").contains("name").doesNotContain("node");
	}

	@Test
	void write_aSwitchedFragmentInAnInterfacePosition_shouldLeaveItsKeysOutOfRequired() {
		// The model's own argument decides whether the fragment arrives, so a response
		// without it is correct. Requiring the key would make the SDK refuse that
		// response and answer the model with a validation error instead of its data.
		String text = OutputSchemaWriter.write(ABSTRACT, file("""
				query A($id: ID!, $withName: Boolean!) {
				  node(id: $id) { id ... @include(if: $withName) { name } }
				}
				""")).json();

		assertThat(text).contains("\"name\"");
		assertThat(requiredNames(text)).contains("id").doesNotContain("name");
	}

	@Test
	void write_aSwitchedSpreadInAnInterfacePosition_shouldLeaveItsKeysOutOfRequired() {
		String text = OutputSchemaWriter.write(ABSTRACT, file("""
				query A($id: ID!, $withRating: Boolean!) {
				  node(id: $id) { id __typename ...Extra @include(if: $withRating) }
				}
				fragment Extra on Movie { rating }
				""")).json();

		assertThat(text).contains("\"rating\"");
		assertThat(requiredNames(text)).doesNotContain("rating");
	}

	@Test
	void write_anUnswitchedFragmentInAnInterfacePosition_shouldStillRequireItsKeys() {
		// The guard above must leave the ordinary case alone: without a directive the key
		// is as certain as one the operation selected directly.
		String text = OutputSchemaWriter.write(ABSTRACT, file("""
				query A($id: ID!) { node(id: $id) { id ... { name } } }
				""")).json();

		assertThat(requiredNames(text)).contains("id").contains("name");
	}

	@Test
	void write_aUnionSpreadAnAliasedTypenameAndABooleanField_shouldDescribeAllThree() {
		// Three shapes a model reads that the other tests leave unasserted: a type
		// condition naming a union inside an abstract position, the alias a model
		// switches on when it renames __typename, and a Boolean field in a result.
		String text = OutputSchemaWriter.write(UNIONS, file("""
				query Feed {
				  feed {
				    kind: __typename
				    ... on SearchResult { ... on Movie { released } }
				  }
				}
				""")).json();

		// The union covers Movie, so its branch carries the field that spread selects,
		// and the const a model switches on sits under the alias the operation wrote.
		assertThat(text).contains("\"released\":{\"anyOf\":[{\"type\":\"boolean\"},{\"type\":\"null\"}]}")
			.contains("\"kind\":{\"const\":\"Movie\"}")
			.contains("\"kind\":{\"const\":\"Person\"}")
			.doesNotContain("\"__typename\":{\"const\"");
	}

	@Test
	void write_aListFieldSelectedByTwoFragments_shouldDescribeTheItemFieldsOfBoth() {
		// The README's shared-fragments pattern: two fragments on Query that both reach
		// movies. The fields of both fragments belong to the items of the list, so id
		// stays in properties and in required.
		OutputSchema written = OutputSchemaWriter.write(LISTS, validFile(LISTS, """
				query M { ...Ids ...Titles }
				fragment Ids on Query { movies { id } }
				fragment Titles on Query { movies { title } }
				"""));

		JsonNode items = data(written).path("properties")
			.path("movies")
			.path("anyOf")
			.path(0)
			.path("items")
			.path("anyOf")
			.path(0);
		// Soft, so one run reports every fact about the items object.
		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(items.path("properties").has("id"))
				.as("items.properties holds id; items: %s", items)
				.isTrue();
			softly.assertThat(items.path("properties").has("title"))
				.as("items.properties holds title; items: %s", items)
				.isTrue();
			softly.assertThat(names(items.path("required")))
				.as("items.required; items: %s", items)
				.containsExactly("id", "title");
		});
	}

	@Test
	void write_aUnionFieldSelectedTwice_shouldKeepTheBranchesOfBothOccurrences() {
		// A second occurrence written as a fresh anyOf over the first would lose the
		// Payment branch, the __typename property and its const.
		OutputSchema written = OutputSchemaWriter.write(PAYMENTS, validFile(PAYMENTS, """
				query P {
				  pay { __typename ... on Payment { id } }
				  pay { ... on Declined { message } }
				}
				"""));

		JsonNode anyOf = data(written).path("properties").path("pay").path("anyOf");
		List<JsonNode> branches = anyOf.valueStream().toList();
		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(branches)
				.as("a Payment branch pinned by __typename; pay.anyOf: %s", anyOf)
				.anyMatch((branch) -> "Payment"
					.equals(branch.path("properties").path("__typename").path("const").asString())
						&& branch.path("properties").has("id"));
			softly.assertThat(branches)
				.as("a Declined branch pinned by __typename; pay.anyOf: %s", anyOf)
				.anyMatch((branch) -> "Declined"
					.equals(branch.path("properties").path("__typename").path("const").asString())
						&& branch.path("properties").has("message"));
			softly.assertThat(written.positionsWithoutTypename())
				.as("__typename is selected at the position, so the position does not earn a warning")
				.isEmpty();
		});
	}

	@Test
	void write_aFragmentOnThePositionsOwnInterface_shouldWriteOneShapeAndNoTypenameWarning() {
		// The condition holds for every implementation, so the fields are the position's
		// own. Expanding it into the members would publish three identical branches.
		OutputSchema written = OutputSchemaWriter.write(OWNED, validFile(OWNED, """
				query N($id: ID!) { node(id: $id) { ...NodeFields } }
				fragment NodeFields on Node { id }
				"""));

		JsonNode node = data(written).path("properties").path("node");
		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(node.path("anyOf").size()).as("one shape and null; node: %s", node).isEqualTo(2);
			softly.assertThat(names(node.path("anyOf").path(0).path("required")))
				.as("node: %s", node)
				.containsExactly("id");
			softly.assertThat(written.positionsWithoutTypename())
				.as("every implementation answers the same shape, so __typename is not needed to tell them apart")
				.isEmpty();
		});
	}

	@Test
	void write_aFragmentOnASupertypeOfThePosition_shouldCountAsThePositionsOwn() {
		// Every implementation of Node implements Named as well, the one added tomorrow
		// included, because Node declares it. A union or an interface the members merely
		// happen to share would not carry that promise, and stays a branch per member.
		OutputSchema written = OutputSchemaWriter.write(OWNED, validFile(OWNED, """
				query N($id: ID!) { node(id: $id) { id ... on Named { name } } }
				"""));

		JsonNode node = data(written).path("properties").path("node");
		assertThat(node.path("anyOf").size()).as("node: %s", node).isEqualTo(2);
		assertThat(names(node.path("anyOf").path(0).path("required"))).containsExactly("id", "name");
		assertThat(written.positionsWithoutTypename()).isEmpty();
	}

	@Test
	void write_aFragmentOnOneMember_shouldStillBranchAndWarnWithoutTypename() {
		// The rule above must leave the member case alone: a condition naming one
		// implementation creates its branch, and without __typename a model reading the
		// response cannot tell that branch from the open one.
		OutputSchema written = OutputSchemaWriter.write(OWNED, validFile(OWNED, """
				query N($id: ID!) { node(id: $id) { id ... on A { name } } }
				"""));

		JsonNode node = data(written).path("properties").path("node");
		assertThat(node.path("anyOf").size()).as("A, the open branch and null; node: %s", node).isEqualTo(3);
		assertThat(written.positionsWithoutTypename()).containsExactly("node");
	}

	@Test
	void write_nestedFragmentsOnTheOwnInterface_shouldGrowLinearlyWithDepth() {
		// Without the rule, each level would multiply the branches by the number of
		// implementations: 6, 18 and 54 anyOf arrays at depth 1, 2 and 3 with three
		// implementations. One shape per level adds a constant amount of text.
		String depth1 = "query N($id: ID!) { node(id: $id) { ... on Node { id } } }";
		String depth2 = "query N($id: ID!) { node(id: $id) { ... on Node { id owner { ... on Node { id } } } } }";
		String depth3 = "query N($id: ID!) { node(id: $id) { ... on Node { id owner { ... on Node { id owner "
				+ "{ ... on Node { id } } } } } } }";

		List<Integer> anyOfCounts = new ArrayList<>();
		List<Integer> lengths = new ArrayList<>();
		for (String document : List.of(depth1, depth2, depth3)) {
			String json = OutputSchemaWriter.write(OWNED, validFile(OWNED, document)).json();
			anyOfCounts.add(count(json, "\"anyOf\""));
			lengths.add(json.length());
		}

		// Two arrays per level, the position and its id, and 146 characters. Six arrays
		// come before the first level: the data member, the errors member, and the four
		// members of an error, each nullable.
		assertThat(anyOfCounts).containsExactly(8, 10, 12);
		assertThat(lengths).containsExactly(740, 886, 1032);
	}

	@Test
	void write_anOperationNamingMoreShapesThanTheBudget_shouldStopAtTheOpenShapeAndSayWhere() {
		// Eight implementations named at each of three levels ask for 8, 64 and 512
		// branches. The budget of 500 covers the first two levels and 53 of the 64
		// third-level positions, 496 branches in all, and the remaining eleven publish
		// the open shape with a note. Without the bound, a Relay schema with a hundred
		// Node implementations would multiply by a hundred per level.
		StringBuilder sdl = new StringBuilder("interface Node { id: ID!  owner: Node }\n");
		for (char member = 'A'; member <= 'H'; member++) {
			sdl.append("type ").append(member).append(" implements Node { id: ID!  owner: Node }\n");
		}
		sdl.append("type Query { node(id: ID!): Node }\n");
		GraphQLSchema wide = SdlSchemaFactory.schemaFrom(sdl.toString(), "classpath:wide.graphqls");

		OutputSchema written = OutputSchemaWriter.write(wide,
				validFile(wide, "query N($id: ID!) { node(id: $id) { __typename " + membersToDepth(3) + " } }"));

		assertThat(written.cutPositions()).containsExactly("owner");
		assertThat(count(written.json(), "\"const\"")).isEqualTo(496);
		assertThat(written.json()).contains("An object whose fields this schema leaves unlisted");
		// 81,207 characters. Without the bound the text would grow with the document.
		assertThat(written.json().length()).isLessThan(100_000);
	}

	@Test
	void write_anOperationInsideTheBudget_shouldKeepEveryShape() {
		// The case the bound has to leave alone: the errors-as-data shape takes one
		// branch per member.
		OutputSchema written = OutputSchemaWriter.write(PAYMENTS, validFile(PAYMENTS, """
				query P { pay { __typename ... on Payment { id } ... on Declined { message } } }
				"""));

		assertThat(written.cutPositions()).isEmpty();
		assertThat(written.json()).doesNotContain("leaves unlisted");
	}

	// One inline fragment per implementation A to H, each selecting id and, above the
	// last level, the owner position with the same fragments inside it.
	private static String membersToDepth(int depth) {
		StringBuilder selections = new StringBuilder();
		for (char member = 'A'; member <= 'H'; member++) {
			selections.append("... on ").append(member).append(" { id ");
			if (depth > 1) {
				selections.append("owner { __typename ").append(membersToDepth(depth - 1)).append(" } ");
			}
			selections.append("} ");
		}
		return selections.toString();
	}

	@Test
	void write_anInlineFragmentOnTheParentsOwnType_shouldKeepItsFieldsInRequired() {
		// A named spread on the parent's own type and an inline fragment with the same
		// condition both keep their keys certain, so the same fields land in required
		// through either spelling.
		String text = OutputSchemaWriter.write(FRAGMENTS, validFile(FRAGMENTS, """
				query M { movie { ... on Movie { title } } }
				""")).json();

		assertThat(requiredNames(text)).contains("title");
	}

	@Test
	void write_aFragmentOnAUnionAtAnObjectPosition_shouldKeepItsSelections() {
		// GraphQL 5.5.2.3 allows a fragment on a union wherever the surrounding object is
		// one of its members. A union is not a fields container, so the fragment resolves
		// against the surrounding type, and movie keeps the properties the fragment
		// selects, as the same spread at the interface position beside it does.
		OutputSchema written = OutputSchemaWriter.write(RESULTS, validFile(RESULTS, """
				query M {
				  movie { ...ResultFields }
				  node { ...ResultFields }
				}
				fragment ResultFields on Result { __typename ... on Movie { id title } }
				"""));

		JsonNode movie = data(written).path("properties").path("movie").path("anyOf").path(0);
		assertThat(movie.path("properties").propertyNames()).as("movie: %s", movie)
			.containsExactly("__typename", "id", "title");
		// Movie is a member of Result, so the condition holds for every movie and the
		// keys are as certain as the ones beside them.
		assertThat(names(movie.path("required"))).containsExactly("__typename", "id", "title");
	}

	@Test
	void write_anInlineFragmentOnAUnionAtAnObjectPosition_shouldKeepItsSelections() {
		// The inline spelling of the case above, which takes the same path.
		OutputSchema written = OutputSchemaWriter.write(RESULTS, validFile(RESULTS, """
				query M { movie { ... on Result { __typename } id } }
				"""));

		JsonNode movie = data(written).path("properties").path("movie").path("anyOf").path(0);
		assertThat(movie.path("properties").propertyNames()).as("movie: %s", movie).containsExactly("__typename", "id");
		assertThat(names(movie.path("required"))).containsExactly("__typename", "id");
	}

	// Every name inside a required array, which is what a client enforces presence on.
	@Test
	void write_theErrorsMember_shouldAllowNullForEachMemberOfAnError() {
		// AWS AppSync answers a variable that fails coercion with "path": null and
		// "locations": null, so a schema typing both as an array alone would refuse the
		// error the API wrote.
		JsonNode error = JSON.readTree(OutputSchemaWriter.write(LISTS, validFile(LISTS, """
				query Movies { movies { id } }
				""")).json()).at("/properties/errors/anyOf/0/items");

		SoftAssertions.assertSoftly((softly) -> {
			softly.assertThat(error.at("/properties/message"))
				.hasToString("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
			softly.assertThat(error.at("/properties/locations"))
				.hasToString("{\"anyOf\":[{\"type\":\"array\"},{\"type\":\"null\"}]}");
			softly.assertThat(error.at("/properties/path"))
				.hasToString("{\"anyOf\":[{\"type\":\"array\"},{\"type\":\"null\"}]}");
			softly.assertThat(error.at("/properties/extensions"))
				.hasToString("{\"anyOf\":[{\"type\":\"object\"},{\"type\":\"null\"}]}");
		});
	}

	@Test
	void write_theErrorsMember_shouldDescribeAnErrorWithoutRequiringAnyOfItsMembers() {
		// The GraphQL specification gives every error a message, and an API or a gateway
		// in front of it answers {"code": "UNAUTHENTICATED"} all the same. A schema that
		// requires message would refuse that error.
		JsonNode error = JSON.readTree(OutputSchemaWriter.write(LISTS, validFile(LISTS, """
				query Movies { movies { id } }
				""")).json()).at("/properties/errors/anyOf/0/items");

		assertThat(error.path("type").asString()).isEqualTo("object");
		assertThat(error.path("properties").propertyNames()).containsExactly("message", "locations", "path",
				"extensions");
		assertThat(error.has("required")).as("the error: %s", error).isFalse();
	}

	@Test
	void write_theErrorsMember_shouldEndWithABranchThatAcceptsWhateverTheApiWrote() {
		// A gateway that answers "errors": ["Rate limit exceeded"], or writes one object
		// or one string where the list belongs, writes errors the described branch
		// refuses. The branch after it carries a description alone, so it accepts every
		// JSON value, and the member reads as the list, then null, then that branch.
		JsonNode branches = JSON.readTree(OutputSchemaWriter.write(LISTS, validFile(LISTS, """
				query Movies { movies { id } }
				""")).json()).at("/properties/errors/anyOf");

		assertThat(branches.size()).as("the branches: %s", branches).isEqualTo(3);
		assertThat(branches.path(0).path("type").asString()).isEqualTo("array");
		assertThat(branches.path(1)).hasToString("{\"type\":\"null\"}");
		assertThat(branches.path(2).propertyNames()).containsExactly("description");
		assertThat(branches.path(2).path("description").asString())
			.isEqualTo("errors in another shape, as the API or a gateway in front of it wrote them");
	}

	private static String requiredNames(String text) {
		StringBuilder names = new StringBuilder();
		int at = text.indexOf("\"required\":[");
		while (at >= 0) {
			int end = text.indexOf(']', at);
			names.append(text, at, end).append(' ');
			at = text.indexOf("\"required\":[", end);
		}
		return names.toString();
	}

	private static OperationFile file(String text) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ParsedFile parsed = new OperationFileParser()
			.parse(new OperationSource("file:/mcp/Operation.graphql", text, Set.of(ToolExposureType.MCP)), diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		assertThat(parsed).isInstanceOf(OperationFile.class);
		return (OperationFile) parsed;
	}

	// Parsed and validated against the schema, so the writer is handed a document a
	// GraphQL API accepts, which is the only kind it ever sees at startup.
	private static OperationFile validFile(GraphQLSchema schema, String text) {
		OperationFile parsed = file(text);
		assertThat(OperationValidator.validate(schema, parsed.document())).as("the document validates").isEmpty();
		return parsed;
	}

	// The output-side fragments as the catalog builds them: the input map reduced, and
	// the results map over it.
	private static ScalarSchemas published(Map<String, ?> inputs, Map<String, ?> results) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas schemas = ScalarSchemas.of(SCALARS, inputs, diagnostics)
			.forResults(ScalarSchemas.ofResults(SCALARS, results, diagnostics));
		assertThat(diagnostics.problems()).isEmpty();
		return schemas;
	}

	// The typed branch of the order field, which holds what the operation selected on it.
	private static JsonNode orderOf(String operation, Map<String, ?> inputs, Map<String, ?> results) {
		return data(OutputSchemaWriter.write(SCALARS, validFile(SCALARS, operation), published(inputs, results)))
			.at("/properties/order/anyOf/0");
	}

	// The typed branch of the data member, which holds the root fields.
	private static JsonNode data(OutputSchema written) {
		return JSON.readTree(written.json()).path("properties").path("data").path("anyOf").path(0);
	}

	private static List<String> names(JsonNode array) {
		return array.valueStream().map(JsonNode::asString).toList();
	}

	private static int count(String text, String word) {
		int count = 0;
		int at = text.indexOf(word);
		while (at >= 0) {
			count++;
			at = text.indexOf(word, at + word.length());
		}
		return count;
	}

}
