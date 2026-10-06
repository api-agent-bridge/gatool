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
import java.util.Locale;

import graphql.ParseAndValidate;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLUnionType;
import org.junit.jupiter.api.Test;

import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SdlSchemaFactoryTests {

	private static final String MOVIES_LOCATION = "classpath:" + MoviesSchema.RESOURCE;

	// U+1F600, which a Java string holds as the surrogate pair D83D DE00.
	private static final String EMOJI = "\uD83D\uDE00";

	// A second schema, because every argument in the movie schema takes a built-in type,
	// and a literal reaches a scalar's coercion only through an argument.
	private static final String REVIEWS_SDL = """
			scalar DateTime @specifiedBy(url: "https://scalars.graphql.org/andimarek/date-time")

			type Query {
			  reviewsSince(at: DateTime!): [String!]!
			}
			""";

	private final GraphQLSchema movies = SdlSchemaFactory.schemaFrom(MoviesSchema.sdl(), MOVIES_LOCATION);

	@Test
	void schemaFrom_scalarWithSpecifiedBy_shouldKeepTheUrlAndTheSchemaDescription() {
		GraphQLScalarType dateTime = this.movies.getTypeAs("DateTime");

		assertThat(dateTime.getSpecifiedByUrl()).isEqualTo("https://scalars.graphql.org/andimarek/date-time");
		assertThat(dateTime.getDescription()).isEqualTo("An instant with a UTC offset, such as 2026-09-15T18:30:00Z.");
	}

	@Test
	void schemaFrom_scalarWithoutSpecifiedBy_shouldReturnNullUrl() {
		GraphQLScalarType countryCode = this.movies.getTypeAs("CountryCode");

		assertThat(countryCode.getSpecifiedByUrl()).isNull();
		assertThat(countryCode.getDescription()).isEqualTo("A two-letter ISO 3166-1 country code, such as SI.");
	}

	@Test
	void schemaFrom_interfaceAndUnion_shouldBuildBothWithoutImplementations() {
		assertThat(this.movies.getType("Node")).isInstanceOf(GraphQLInterfaceType.class);
		assertThat(this.movies.getType("SearchResult")).isInstanceOf(GraphQLUnionType.class);
	}

	@Test
	void schemaFrom_documentForTheMovieSchema_shouldPassValidation() {
		assertThat(ParseAndValidate.validate(this.movies,
				Parser.parse("query TopRatedMovies { topRatedMovies { id title rating } }")))
			.isEmpty();
	}

	@Test
	void schemaFrom_dateTimeLiteralTheApiAccepts_shouldPassValidation() {
		GraphQLSchema reviews = SdlSchemaFactory.schemaFrom(REVIEWS_SDL, "classpath:reviews.graphqls");

		assertThat(ParseAndValidate.validate(reviews,
				Parser.parse("query ReviewsSince { reviewsSince(at: \"2026-09-15T18:30:00-00:00\") }")))
			.isEmpty();
	}

	@Test
	void schemaFrom_integerLiteralForACustomScalar_shouldPassValidationBecauseACustomScalarAcceptsAnyLiteral() {
		GraphQLSchema reviews = SdlSchemaFactory.schemaFrom(REVIEWS_SDL, "classpath:reviews.graphqls");

		assertThat(ParseAndValidate.validate(reviews, Parser.parse("query ReviewsSince { reviewsSince(at: 42) }")))
			.isEmpty();
	}

	@Test
	void schemaFrom_sdlWithAnUnsatisfiableInputCycle_shouldFailAndNameTheLocationAndTheError() {
		// The generator's last step is schema validation, which raises a different
		// exception from the parser's, and that one carries the location as well.
		assertThatIllegalStateException().isThrownBy(() -> SdlSchemaFactory.schemaFrom("""
				input Tree { name: String!  children: [Tree!]! }
				type Query { plant(tree: Tree): String }
				""", "classpath:cycle.graphqls"))
			.withMessageContaining("classpath:cycle.graphqls is not a valid GraphQL schema:")
			.withMessageContaining("forms an unsatisfiable cycle");
	}

	@Test
	void schemaFrom_onAJvmDefaultingToGerman_shouldReportASyntaxErrorInEnglish() {
		// The operation parser and the validator are pinned to Locale.ROOT, and the
		// schema parser is pinned the same way, because with the JVM's default a schema
		// error would print in German where every other startup line prints in English.
		Locale wasDefault = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			assertThatIllegalStateException()
				.isThrownBy(() -> SdlSchemaFactory.schemaFrom("type Query { a: String", "classpath:cut.graphqls"))
				.withMessageContaining("classpath:cut.graphqls is not a valid GraphQL schema:")
				.withMessageContaining("Invalid syntax")
				.withMessageNotContaining("Ungültige Syntax");
		}
		finally {
			Locale.setDefault(wasDefault);
		}
	}

	@Test
	void schemaFrom_sdlWithCrlfOrLoneCrLineEndings_shouldGiveTheDescriptionsOfTheLfText() {
		// graphql-java splits a block string on \n alone, so a schema file checked out
		// with \r\n would describe a field as "\r\nThe title.\r", and a description
		// holding a blank line would keep every line at its indentation. Those
		// descriptions reach the tool description and the input schema, so the tool
		// contract would differ between a Windows checkout and a Linux one.
		String written = """
				\"""
				A movie in the catalog.

				  Its second paragraph is indented.
				\"""
				type Movie {
				  \"""
				  The title.
				  \"""
				  title: String
				}

				type Query {
				  \"""
				  Finds a movie
				  by its title.
				  \"""
				  movie(
				    \"""
				    The title to look for.
				    \"""
				    title: String
				  ): Movie
				}
				""";

		GraphQLSchema lineFeed = SdlSchemaFactory.schemaFrom(written, "classpath:lf.graphqls");
		GraphQLSchema carriageReturnLineFeed = SdlSchemaFactory.schemaFrom(written.replace("\n", "\r\n"),
				"classpath:crlf.graphqls");
		GraphQLSchema carriageReturn = SdlSchemaFactory.schemaFrom(written.replace('\n', '\r'),
				"classpath:cr.graphqls");

		assertThat(descriptionsOf(lineFeed)).containsExactly(
				"A movie in the catalog.\n\n  Its second paragraph is indented.", "The title.",
				"Finds a movie\nby its title.", "The title to look for.");
		assertThat(descriptionsOf(carriageReturnLineFeed)).isEqualTo(descriptionsOf(lineFeed));
		assertThat(descriptionsOf(carriageReturn)).isEqualTo(descriptionsOf(lineFeed));
	}

	// The description of the type, of its field, of the root field and of its argument.
	private static List<String> descriptionsOf(GraphQLSchema schema) {
		GraphQLObjectType movie = schema.getObjectType("Movie");
		GraphQLFieldDefinition rootField = schema.getQueryType().getFieldDefinition("movie");
		return List.of(String.valueOf(movie.getDescription()),
				String.valueOf(movie.getFieldDefinition("title").getDescription()),
				String.valueOf(rootField.getDescription()),
				String.valueOf(rootField.getArgument("title").getDescription()));
	}

	@Test
	void schemaFrom_emojiInADescriptionWhoseSurrogatePairTwoReadsSplit_shouldKeepTheDescription() {
		// ANTLR 4.13.2 fills its buffer 4,096 chars at a time and breaks a surrogate pair
		// that two reads split, so a schema whose emoji lands on that boundary would be
		// refused as invalid syntax.
		String head = "type Query {\n  \"";
		String description = "z".repeat(4095 - head.length()) + EMOJI;
		String sdl = head + description + "\"\n  movie(title: String): String\n}\n";
		assertThat(sdl.indexOf(EMOJI)).isEqualTo(4095);

		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(sdl, "classpath:emoji.graphqls");

		assertThat(schema.getQueryType().getFieldDefinition("movie").getDescription()).isEqualTo(description);
	}

	@Test
	void schemaFrom_emojiInABlockStringDescriptionWhoseSurrogatePairTwoReadsSplit_shouldKeepTheDescription() {
		// The boundary repeats every 4,096 chars, and this one is the second.
		String head = "type Query {\n  \"\"\"\n  Finds a movie.\n  ";
		String line = "z".repeat(8191 - head.length()) + EMOJI + " ends the line.";
		String sdl = head + line + "\n  \"\"\"\n  movie(title: String): String\n}\n";
		assertThat(sdl.indexOf(EMOJI)).isEqualTo(8191);

		GraphQLSchema schema = SdlSchemaFactory.schemaFrom(sdl, "classpath:emoji.graphqls");

		assertThat(schema.getQueryType().getFieldDefinition("movie").getDescription())
			.isEqualTo("Finds a movie.\n" + line);
	}

	@Test
	void schemaFrom_sdlTheParserStopsReadingAtALimit_shouldNameTheLimitAndTheSizeOfTheText() {
		// The schema is read without a limit, so a test reaches one by handing the parser
		// small options. A limit is a matter of size, and the message says so.
		String sdl = "type Query { movie(title: String): String }";
		assertThat(sdl).hasSize(43);

		assertThatIllegalStateException()
			.isThrownBy(() -> SdlSchemaFactory.schemaFrom(sdl, "classpath:large.graphqls",
					ParserOptions.newParserOptions().maxTokens(5).build()))
			.withMessageStartingWith("classpath:large.graphqls holds 43 characters, and graphql-java's parser "
					+ "stopped reading it at a limit")
			.withMessageContaining("More than 5 'grammar' tokens have been presented")
			.withMessageNotContaining("is not a valid GraphQL schema");
	}

	@Test
	void schemaFrom_implementingFieldWithAnotherArgumentDefault_shouldSayThatGraphQlAllowsItAndNameTheIssue() {
		// The specification asks an implementing field for the same argument type and
		// leaves the default alone. graphql-java compares the default as well, so a
		// schema another server runs in production is refused, and the message says what
		// the difference means.
		assertThatIllegalStateException().isThrownBy(() -> SdlSchemaFactory.schemaFrom("""
				type Query { account: Account }
				interface Account { memberOf(limit: Int! = 100, offset: Int = 0): [String] }
				type Host implements Account { memberOf(limit: Int! = 150, offset: Int = 0): [String] }
				type Bot implements Account { memberOf(limit: Int! = 150, offset: Int = 5): [String] }
				""", "classpath:accounts.graphqls"))
			.withMessageStartingWith(
					"classpath:accounts.graphqls is refused by graphql-java, which GATool " + "builds the schema with:")
			.withMessageContaining("The object type 'Host' [@3:1] has tried to redefine field 'memberOf'")
			.withMessageContaining("graphql-java refuses Host.memberOf(limit:), Bot.memberOf(limit:) and "
					+ "Bot.memberOf(offset:) because the default of each differs from the one its interface "
					+ "declares.")
			.withMessageContaining("https://github.com/graphql-java/graphql-java/issues/4480")
			.withMessageContaining(
					"a copy of the schema that gives each of those arguments the default of " + "its interface starts")
			.withMessageNotContaining("is not a valid GraphQL schema");
	}

	@Test
	void schemaFrom_defaultOnOneSideAloneOrWrittenAnotherWay_shouldBeNamedAsADifferenceOfTheDefault() {
		assertThatIllegalStateException().isThrownBy(() -> SdlSchemaFactory.schemaFrom("""
				type Query { node: Node }
				interface Node {
				  onTheInterface(first: Int = 10): String
				  onTheType(first: Int): String
				  writtenAnotherWay(ids: [Int] = 1): String
				}
				interface Named implements Node {
				  onTheInterface(first: Int): String
				  onTheType(first: Int = 10): String
				  writtenAnotherWay(ids: [Int] = [1]): String
				}
				""", "classpath:nodes.graphqls"))
			.withMessageStartingWith("classpath:nodes.graphqls is refused by graphql-java")
			.withMessageContaining("graphql-java refuses Named.onTheInterface(first:), Named.onTheType(first:) "
					+ "and Named.writtenAnotherWay(ids:) because the default of each differs");
	}

	@Test
	void schemaFrom_implementingFieldWithAnotherArgumentType_shouldKeepTheMessageOfAnInvalidSchema() {
		// The specification does ask for the same type, so this schema is invalid and the
		// message stays as it was.
		assertThatIllegalStateException().isThrownBy(() -> SdlSchemaFactory.schemaFrom("""
				type Query { account: Account }
				interface Account { memberOf(limit: Int = 100): [String] }
				type Host implements Account { memberOf(limit: String = "100"): [String] }
				""", "classpath:accounts.graphqls"))
			.withMessageStartingWith("classpath:accounts.graphqls is not a valid GraphQL schema:")
			.withMessageContaining("has tried to redefine field 'memberOf'")
			.withMessageNotContaining("issues/4480");
	}

	@Test
	void schemaFrom_differenceOfADefaultBesideAnotherError_shouldKeepTheHeaderAndExplainTheDefault() {
		assertThatIllegalStateException().isThrownBy(() -> SdlSchemaFactory.schemaFrom("""
				type Query { account: Account, movie: Missing }
				interface Account { memberOf(limit: Int! = 100): [String] }
				type Host implements Account { memberOf(limit: Int! = 150): [String] }
				""", "classpath:accounts.graphqls"))
			.withMessageStartingWith("classpath:accounts.graphqls is not a valid GraphQL schema:")
			.withMessageContaining("The field type 'Missing' is not present")
			.withMessageContaining("graphql-java refuses Host.memberOf(limit:) because its default differs "
					+ "from the one its interface declares.")
			.withMessageContaining("https://github.com/graphql-java/graphql-java/issues/4480");
	}

	@Test
	void schemaFrom_sdlWithAMissingType_shouldFailAndNameTheLocationAndTheError() {
		assertThatIllegalStateException()
			.isThrownBy(() -> SdlSchemaFactory.schemaFrom("type Query { movie: Missing }", "classpath:broken.graphqls"))
			.withMessageContaining("classpath:broken.graphqls")
			.withMessageContaining("The field type 'Missing' is not present when resolving type 'Query'");
	}

}
