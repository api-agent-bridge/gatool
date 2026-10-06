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

package io.gatool.core.internal.search;

import java.util.List;

import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What {@code introspectType} shows for each kind of type a schema declares.
 *
 * <p>
 * This is the step between a search hit and a working operation, so the cases here are
 * the things a hit cannot carry: what an input object holds, which values an enum allows,
 * which fields a returned type has.
 */
class TypeDetailTests {

	private static final String SDL = """
			\"""A thing with an id.\"""
			interface Node {
			  id: ID!
			}
			interface Titled implements Node {
			  id: ID!
			  title: String!
			}
			\"""One film,
			as the catalog holds it.\"""
			type Movie implements Titled & Node {
			  id: ID!
			  title: String!
			  \"""The average rating.\"""
			  rating(scale: RatingScale = STARS, precise: Boolean = false): Float
			  reviews(first: Int): [Review!]!
			}
			type Review { id: ID! score: Int! }
			\"""How a rating is expressed.\"""
			enum RatingScale {
			  \"""Out of five.\"""
			  STARS
			  PERCENT
			}
			\"""Narrows a search.\"""
			input MovieFilter {
			  \"""Matched anywhere in the title.\"""
			  titleContains: String
			  minRating: Float = 0
			}
			union Result = Movie | Review
			\"""An ISO 8601 instant.\"""
			scalar DateTime
			type Query { movie(id: ID!): Movie when: DateTime search(filter: MovieFilter): [Result!]! }
			""";

	@Test
	void of_anObjectType_shouldWriteItsFieldsWithArgumentsAndDefaults() {
		assertThat(detail("Movie")).isEqualTo("""
				\"""One film, as the catalog holds it.\"""
				type Movie implements Node & Titled {
				  id: ID!
				  title: String!
				  \"""The average rating.\"""
				  rating(scale: RatingScale = STARS, precise: Boolean = false): Float
				  reviews(first: Int): [Review!]!
				}""");
	}

	@Test
	void of_anObjectType_shouldNameTheReturnTypesWithoutExpandingThem() {
		// One level deep on purpose: the model asks for Review when it needs Review, and
		// pays for that type alone.
		assertThat(detail("Movie")).contains("[Review!]!").doesNotContain("score");
	}

	@Test
	void of_anInterfaceThatImplementsAnother_shouldWriteTheImplementsClause() {
		assertThat(detail("Titled")).isEqualTo("""
				interface Titled implements Node {  # implemented by Movie
				  id: ID!
				  title: String!
				}""");
	}

	@Test
	void of_anInterface_shouldNameWhatImplementsItInNameOrder() {
		// An interface's fields are selected on an implementation, and a model that reads
		// the interface alone has to guess which type to spread.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				interface Node { id: ID! }
				type Studio implements Node { id: ID! }
				type Movie implements Node { id: ID! }
				type Query { node(id: ID!): Node }
				""", "type-detail-test.graphqls");

		assertThat(of(schema, "Node", false)).isEqualTo("""
				interface Node {  # implemented by Movie, Studio
				  id: ID!
				}""");
	}

	@Test
	void of_anInterfaceNothingImplements_shouldWriteNoImplementationComment() {
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				interface Node { id: ID! }
				type Query { node(id: ID!): Node }
				""", "type-detail-test.graphqls");

		assertThat(of(schema, "Node", false)).isEqualTo("""
				interface Node {
				  id: ID!
				}""");
	}

	@Test
	void of_anEnumWithADeprecatedValueAndTheSettingOff_shouldLeaveThatValueOut() {
		// The same rule as a field: hidden from both schema tools, so the model cannot
		// write a value the search keeps out of its results.
		assertThat(of(deprecatingInputs(), "SortOrder", false)).isEqualTo("""
				enum SortOrder {
				  NAME
				  CREATED
				}""");
	}

	@Test
	void of_anEnumWithADeprecatedValueAndTheSettingOn_shouldWriteTheSchemasOwnReason() {
		// Written bare, the value would read as current and a model would use it.
		assertThat(of(deprecatingInputs(), "SortOrder", true)).isEqualTo("""
				enum SortOrder {
				  NAME
				  CREATED
				  SIZE @deprecated(reason: "Use CREATED.")
				}""");
	}

	@Test
	void of_anInputObjectWithADeprecatedField_shouldFollowTheSetting() {
		assertThat(of(deprecatingInputs(), "MovieFilter", false)).isEqualTo("""
				input MovieFilter {
				  title: String
				  tags: [String!]
				}""");
		assertThat(of(deprecatingInputs(), "MovieFilter", true)).isEqualTo("""
				input MovieFilter {
				  title: String
				  genre: String @deprecated(reason: "Use tags.")
				  tags: [String!]
				}""");
	}

	@Test
	void of_aFieldWithADeprecatedArgument_shouldFollowTheSetting() {
		assertThat(of(deprecatingInputs(), "Query", false))
			.contains("movies(order: SortOrder, filter: MovieFilter, first: Int): [String!]!");
		assertThat(of(deprecatingInputs(), "Query", true)).contains("movies(order: SortOrder, filter: MovieFilter, "
				+ "limit: Int @deprecated(reason: \"Use first.\"), first: Int): [String!]!");
	}

	// GraphQL declares @deprecated on an argument, an enum value and an input field as
	// well as on a field, and a schema author uses all four.
	private static GraphQLSchema deprecatingInputs() {
		return SdlSchemaFactory.schemaFrom("""
				enum SortOrder { NAME CREATED SIZE @deprecated(reason: "Use CREATED.") }
				input MovieFilter { title: String genre: String @deprecated(reason: "Use tags.") tags: [String!] }
				type Query {
				  movies(order: SortOrder, filter: MovieFilter, limit: Int @deprecated(reason: "Use first."),
				      first: Int): [String!]!
				}
				""", "type-detail-test.graphqls");
	}

	@Test
	void of_anEnum_shouldWriteEveryValueWithItsOwnWords() {
		// A value's description is what stops a model inventing a plausible member, which
		// is why the input schema writer publishes it too.
		assertThat(detail("RatingScale")).isEqualTo("""
				\"""How a rating is expressed.\"""
				enum RatingScale {
				  \"""Out of five.\"""
				  STARS
				  PERCENT
				}""");
	}

	@Test
	void of_anInputObject_shouldWriteWhatItHolds() {
		// The one thing a search hit cannot carry, because a hit is one field and this is
		// a type.
		assertThat(detail("MovieFilter")).isEqualTo("""
				\"""Narrows a search.\"""
				input MovieFilter {
				  \"""Matched anywhere in the title.\"""
				  titleContains: String
				  minRating: Float = 0
				}""");
	}

	@Test
	void of_aUnion_shouldNameItsMembers() {
		assertThat(detail("Result")).isEqualTo("union Result = Movie | Review");
	}

	@Test
	void of_aScalar_shouldWriteItsWords() {
		assertThat(detail("DateTime")).isEqualTo("""
				\"""An ISO 8601 instant.\"""
				scalar DateTime""");
	}

	@Test
	void of_aDescriptionOverSeveralLines_shouldBecomeOneLine() {
		assertThat(detail("Movie")).contains("\"\"\"One film, as the catalog holds it.\"\"\"");
	}

	@Test
	void of_aNameTheSchemaLacks_shouldAnswerWithNothing() {
		// The tool turns this into a message sending the model back to search, which is
		// the move it should make next.
		assertThat(of(schema(), "Studio", false)).isNull();
	}

	@Test
	void of_aReservedName_shouldAnswerWithNothing() {
		// __Schema is in every schema graphql-java builds and the corpus leaves it out,
		// so a model asking for one asked for something it has not read.
		assertThat(of(schema(), "__Schema", false)).isNull();
	}

	@Test
	void of_theSubscriptionRoot_shouldAnswerWithNothing() {
		// The corpus leaves this type out, so it has to be unreadable here too: a model
		// that could read its fields would write an operation against them, and a tool
		// call returns a single result.
		GraphQLSchema streaming = SdlSchemaFactory.schemaFrom("""
				type Query { movie: String }
				type Subscription { movieAdded: String }
				""", "type-detail-test.graphqls");

		assertThat(of(streaming, "Subscription", false)).isNull();
		assertThat(of(streaming, "Query", false)).isNotNull();
	}

	@Test
	void of_aTypeNamedOnlyByADirectiveAnOperationMayWrite_shouldStillBeReadable() {
		// Nothing in the field graph leads to FilterInput, and a model that writes
		// @filter has to know what it holds. graphql-java validates "query { hits
		// @filter(by: {term: \"a\"}) }", so answering that the type does not exist would
		// leave the model unable to fill in a directive the schema offers.
		GraphQLSchema directives = SdlSchemaFactory.schemaFrom("""
				directive @filter(by: FilterInput!, mode: Mode) on FIELD
				input FilterInput { term: String! }
				enum Mode { LOOSE STRICT }
				type Query { hits: [String!]! }
				""", "type-detail-test.graphqls");

		assertThat(of(directives, "FilterInput", false)).contains("term: String!");
		assertThat(of(directives, "Mode", false)).contains("LOOSE").contains("STRICT");
	}

	@Test
	void of_aTypeNamedOnlyByASchemaDirective_shouldStayUnreadable() {
		// The other side of the same rule. A directive declared on FIELD_DEFINITION is
		// written in the schema document, so no operation can pass it anything and its
		// argument type is as unreachable as any other orphan.
		GraphQLSchema directives = SdlSchemaFactory.schemaFrom("""
				directive @tag(spec: TagInput!) on FIELD_DEFINITION
				input TagInput { name: String! }
				type Query { hits: [String!]! }
				""", "type-detail-test.graphqls");

		assertThat(of(directives, "TagInput", false)).isNull();
	}

	@Test
	void of_aTypeWithADeprecatedField_shouldLeaveThatFieldOutByDefault() {
		// The corpus leaves it out, so reading the type has to as well. A model that met
		// rating here would use it without having seen it in a search result.
		assertThat(of(deprecating(), "Movie", false)).isEqualTo("""
				type Movie {
				  id: ID!
				  score: Float
				}""");
	}

	@Test
	void of_aTypeWithADeprecatedFieldAndTheSettingOn_shouldWriteTheSchemasOwnReason() {
		assertThat(of(deprecating(), "Movie", true)).isEqualTo("""
				type Movie {
				  id: ID!
				  rating: Float @deprecated(reason: "Use score instead.")
				  score: Float
				}""");
	}

	@Test
	void of_aDescriptionCarryingQuotes_shouldWriteSdlTheParserReads() {
		// A schema author writes these legally, and what GATool publishes has to survive
		// being read as the SDL both the javadoc and the tool description call it.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				type Query {
				  \"""The cursor. Send it as \\\""" to reset.\"""
				  page(after: String): String
				  \"""
				  Use the operator "eq"
				  \"""
				  filtered(by: String): String
				}
				""", "type-detail-test.graphqls");

		String written = of(schema, "Query", false);

		assertThat(written).contains("The cursor.").contains("Use the operator");
		assertThatCode(() -> new Parser().parseDocument(written)).doesNotThrowAnyException();
		assertThat(descriptionsOf(written)).containsExactly("The cursor. Send it as \"\"\" to reset.",
				"Use the operator \"eq\"");
	}

	// The descriptions the parser reads back out of what was written, which is what a
	// model reading this text takes the description to be.
	private static List<String> descriptionsOf(String written) {
		ObjectTypeDefinition type = (ObjectTypeDefinition) new Parser().parseDocument(written)
			.getDefinitions()
			.getFirst();
		return type.getFieldDefinitions().stream().map((field) -> field.getDescription().getContent()).toList();
	}

	private static GraphQLSchema deprecating() {
		return SdlSchemaFactory.schemaFrom("""
				type Movie {
				  id: ID!
				  rating: Float @deprecated(reason: "Use score instead.")
				  score: Float
				}
				type Query { movie: Movie }
				""", "type-detail-test.graphqls");
	}

	@Test
	void of_aDeprecationReasonCarryingAQuoteAndALineBreak_shouldWriteSdlTheParserReads() {
		// Written between quotes as the schema holds it, a quotation mark would close the
		// string early and the rest of the line would read as structure.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				type Movie {
				  rating: Float @deprecated(reason: "Use \\"score\\" instead.\\nSame scale, new name.")
				  score: Float
				}
				type Query { movie: Movie }
				""", "type-detail-test.graphqls");

		String written = of(schema, "Movie", true);

		assertThat(written).isEqualTo("""
				type Movie {
				  rating: Float @deprecated(reason: "Use \\"score\\" instead.\\nSame scale, new name.")
				  score: Float
				}""");
		assertThat(deprecationReasonOf(written)).isEqualTo("Use \"score\" instead.\nSame scale, new name.");
	}

	@Test
	void of_theMutationRootWhileMutationsAreOff_shouldAnswerWithNothing() {
		// The reachable set is the caller's, and a caller with mutations off hands over
		// one without the mutation root, so the tool refuses a type the model cannot
		// write an operation against.
		GraphQLSchema writing = SdlSchemaFactory.schemaFrom("""
				type Query { movie: String }
				type Mutation { touch: String }
				""", "type-detail-test.graphqls");

		assertThat(TypeDetail.of(writing, "Mutation", false, ReachableTypes.of(writing, false, false))).isNull();
		assertThat(TypeDetail.of(writing, "Mutation", false, ReachableTypes.of(writing, false, true))).isEqualTo("""
				type Mutation {
				  touch: String
				}""");
	}

	// The reason the parser reads back out of the first field of what was written.
	private static String deprecationReasonOf(String written) {
		ObjectTypeDefinition type = (ObjectTypeDefinition) new Parser().parseDocument(written)
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

	private static String detail(String typeName) {
		String written = of(schema(), typeName, false);
		assertThat(written).as("detail of %s", typeName).isNotNull();
		return written;
	}

	// The reachable set is computed once by the tool and handed in, so the tests compute
	// it here the way DynamicOperationTools does, with mutations allowed.
	private static @Nullable String of(GraphQLSchema schema, String typeName, boolean includeDeprecated) {
		return TypeDetail.of(schema, typeName, includeDeprecated, ReachableTypes.of(schema, includeDeprecated, true));
	}

	private static GraphQLSchema schema() {
		return SdlSchemaFactory.schemaFrom(SDL, "type-detail-test.graphqls");
	}

}
