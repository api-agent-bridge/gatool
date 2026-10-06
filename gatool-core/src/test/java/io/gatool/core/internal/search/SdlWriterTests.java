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

import graphql.Scalars;
import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.parser.Parser;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three pieces of SDL the corpus, the paths and the type detail share.
 *
 * <p>
 * The cases that matter are the ones a parser refuses: a deprecation reason is the schema
 * author's text, and it reaches a model as SDL, so what is written has to read back as
 * what the schema holds.
 */
class SdlWriterTests {

	private static final String SDL = """
			enum Genre { DRAMA NOIR @deprecated(reason: "Use DRAMA.") }
			input Filter { title: String name: String @deprecated }
			type Query {
			  movies(first: Int = 10, genre: Genre): [String!]!
			  paged(limit: Int = 10 @deprecated(reason: "Use first."), first: Int): [String!]!
			  legacy(limit: Int @deprecated(reason: "Use paged.")): [String!]!
			  filtered(by: Filter): [String!]!
			  ping: String
			  old: String @deprecated(reason: "Use ping.")
			  blank: String @deprecated(reason: " ")
			}
			""";

	@Test
	void arguments_aFieldWithoutArguments_shouldWriteNothing() {
		assertThat(SdlWriter.arguments(field("ping").getArguments(), true, false)).isEmpty();
	}

	@Test
	void arguments_withoutDefaults_shouldWriteNamesAndTypesAlone() {
		assertThat(SdlWriter.arguments(field("movies").getArguments(), false, false))
			.isEqualTo("(first: Int, genre: Genre)");
	}

	@Test
	void arguments_withDefaults_shouldWriteEachDeclaredDefault() {
		assertThat(SdlWriter.arguments(field("movies").getArguments(), true, false))
			.isEqualTo("(first: Int = 10, genre: Genre)");
	}

	@Test
	void arguments_aDeprecatedArgument_shouldFollowTheSettingTheWayAFieldDoes() {
		// Left out while the setting is off, because a search result leaves it out as
		// well, and marked with the schema's own reason while it is on.
		assertThat(SdlWriter.arguments(field("paged").getArguments(), true, false)).isEqualTo("(first: Int)");
		assertThat(SdlWriter.arguments(field("paged").getArguments(), true, true))
			.isEqualTo("(limit: Int = 10 @deprecated(reason: \"Use first.\"), first: Int)");
	}

	@Test
	void arguments_everyArgumentDeprecatedAndTheSettingOff_shouldWriteNothing() {
		// An empty pair of brackets is a syntax error in SDL, so a field whose every
		// argument is hidden is written as one without arguments.
		assertThat(SdlWriter.arguments(field("legacy").getArguments(), true, false)).isEmpty();
	}

	@Test
	void deprecationMarker_anEnumValueAndAnInputField_shouldWriteTheSchemasOwnReason() {
		GraphQLSchema schema = schema();
		GraphQLEnumType genre = (GraphQLEnumType) schema.getType("Genre");
		GraphQLInputObjectType filter = (GraphQLInputObjectType) schema.getType("Filter");

		assertThat(SdlWriter.deprecationMarker(genre.getValue("DRAMA"))).isEmpty();
		assertThat(SdlWriter.deprecationMarker(genre.getValue("NOIR")))
			.isEqualTo(" @deprecated(reason: \"Use DRAMA.\")");
		assertThat(SdlWriter.deprecationMarker(filter.getFieldDefinition("title"))).isEmpty();
		// graphql-java fills in the reason the specification gives a bare @deprecated.
		assertThat(SdlWriter.deprecationMarker(filter.getFieldDefinition("name")))
			.isEqualTo(" @deprecated(reason: \"No longer supported\")");
	}

	@Test
	void defaultOf_aProgrammaticDefault_shouldBeLeftOut() {
		// A schema built in code holds a Java value, and printing one faithfully is the
		// input schema writer's job.
		GraphQLArgument argument = GraphQLArgument.newArgument()
			.name("first")
			.type(Scalars.GraphQLInt)
			.defaultValueProgrammatic(10)
			.build();

		assertThat(SdlWriter.defaultOf(argument.getArgumentDefaultValue())).isNull();
	}

	@Test
	void deprecationMarker_aCurrentField_shouldWriteNothing() {
		assertThat(SdlWriter.deprecationMarker(field("ping"))).isEmpty();
	}

	@Test
	void deprecationMarker_aReason_shouldWriteItAsTheDirectiveArgument() {
		assertThat(SdlWriter.deprecationMarker(field("old"))).isEqualTo(" @deprecated(reason: \"Use ping.\")");
	}

	@Test
	void deprecationMarker_aBlankReason_shouldWriteTheDirectiveAlone() {
		assertThat(SdlWriter.deprecationMarker(field("blank"))).isEqualTo(" @deprecated");
	}

	@Test
	void deprecationMarker_aReasonWithAQuoteABackslashAndALineBreak_shouldWriteSdlTheParserReadsBack() {
		// Written between quotes as the schema holds it, the quotation mark would close
		// the string early and the parser would read the rest of the line as structure.
		GraphQLSchema schema = SdlSchemaFactory.schemaFrom("""
				type Query {
				  old: String @deprecated(reason: "Use \\"ping\\" or C:\\\\path.\\nSame answer.")
				}
				""", "sdl-writer-test.graphqls");
		String marker = SdlWriter.deprecationMarker(schema.getQueryType().getFieldDefinition("old"));

		assertThat(marker).isEqualTo(" @deprecated(reason: \"Use \\\"ping\\\" or C:\\\\path.\\nSame answer.\")");
		assertThat(reasonReadBack(marker)).isEqualTo("Use \"ping\" or C:\\path.\nSame answer.");
	}

	@Test
	void stringLiteral_aControlCharacter_shouldBecomeAnEscapeWhileATabStays() {
		// A tab is a source character in GraphQL and the rest of the control range is
		// not, so a reason holding one needs the escape the grammar names for it.
		String literal = SdlWriter.stringLiteral("a\tb\u0001c");

		assertThat(literal).isEqualTo("\"a\tb\\u0001c\"");
		assertThat(reasonReadBack(" @deprecated(reason: " + literal + ")")).isEqualTo("a\tb\u0001c");
	}

	// The reason the parser reads back from a field carrying the marker, which is what a
	// model reading the SDL takes the reason to be.
	private static String reasonReadBack(String marker) {
		ObjectTypeDefinition type = (ObjectTypeDefinition) new Parser()
			.parseDocument("type Query {\n  old: String" + marker + "\n}")
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

	private static GraphQLFieldDefinition field(String name) {
		return schema().getQueryType().getFieldDefinition(name);
	}

	private static GraphQLSchema schema() {
		return SdlSchemaFactory.schemaFrom(SDL, "sdl-writer-test.graphqls");
	}

}
