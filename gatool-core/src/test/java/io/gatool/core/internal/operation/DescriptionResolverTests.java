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

import java.util.Set;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionResolverTests {

	private static final String LOCATION = "file:/operations/Test.graphql";

	private final OperationFileParser parser = new OperationFileParser();

	private final OperationDiagnostics diagnostics = new OperationDiagnostics();

	@Test
	void describe_commentLinesDirectlyAbove_shouldJoinThem() {
		String description = describe("""
				# Returns the highest-rated movies,
				# best first.
				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(description).isEqualTo("Returns the highest-rated movies,\nbest first.");
		assertThat(this.diagnostics.warnings()).isEmpty();
	}

	@Test
	void describe_fileStartingWithAByteOrderMark_shouldKeepTheFirstCommentLine() {
		// The lexer ignores U+FEFF and String.strip() keeps it, so with the mark left in,
		// the first line of the file compares unequal to the comment the lexer captured,
		// and the description loses its first line.
		String description = describe("﻿# Returns the highest-rated movies,\n# best first.\n"
				+ "query TopRatedMovies {\n  topRatedMovies { title }\n}\n");

		assertThat(description).isEqualTo("Returns the highest-rated movies,\nbest first.");
	}

	@Test
	void describe_commentsSeparatedByBlankLine_shouldUseOnlyTheLinesAboveTheOperation() {
		String description = describe("""
				# Operation files for the movie agent.

				# Lists the best movies for a recommendation.
				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(description).isEqualTo("Lists the best movies for a recommendation.");
	}

	@Test
	void describe_oneRootFieldWithoutComments_shouldUseTheSchemaDescription() {
		String description = describe("""
				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(description).isEqualTo("Returns the highest-rated movies, best first.");
		assertThat(this.diagnostics.warnings()).isEmpty();
	}

	@Test
	void describe_headerCommentAndBlankLine_shouldUseTheSchemaDescription() {
		String description = describe("""
				# Operation files for the movie agent.

				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(description).isEqualTo("Returns the highest-rated movies, best first.");
	}

	@Test
	void describe_mutationWithoutComments_shouldUseTheMutationFieldDescription() {
		String description = describe("""
				mutation AddReview {
				  addReview(input: { movieId: "movie-1", score: 9 }) { review { id } }
				}
				""");

		assertThat(description).isEqualTo("Adds a review to a movie and returns the stored review.");
	}

	@Test
	void describe_twoRootFieldsWithoutComments_shouldWarnAndReturnNull() {
		String description = describe("""
				query MoviesAndPeople {
				  topRatedMovies { title }
				  search(text: "no") { __typename }
				}
				""");

		assertThat(description).isNull();
		assertThat(this.diagnostics.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).startsWith("lacks a description"));
	}

	@Test
	void describe_fileWithCrlfLineEndings_shouldJoinTheCommentLinesWithoutTheCarriageReturns() {
		// The lines of the file and the comments the lexer captured are compared one by
		// one, and both readers end a line at \r\n, so a file an editor on Windows wrote
		// describes its tool the way one written with \n does.
		String description = describe("# Returns the highest-rated movies,\r\n# best first.\r\n"
				+ "query TopRatedMovies {\r\n  topRatedMovies { title }\r\n}\r\n");

		assertThat(description).isEqualTo("Returns the highest-rated movies,\nbest first.");
	}

	@ParameterizedTest(name = "a comment line of U+{0}")
	@ValueSource(strings = { "00A0", "200B", "2007", "FEFF", "3164", "2800" })
	void describe_commentLineOfAnInvisibleCharacterAlone_shouldUseTheSchemaDescription(String codePoint) {
		// strip() knows Java's whitespace, which leaves these characters out, so a rule
		// built on it would count the line as a description and pass over the schema's
		// own sentence.
		String description = describe("# " + Character.toString(Integer.parseInt(codePoint, 16)) + "\n"
				+ "query TopRatedMovies {\n  topRatedMovies { title }\n}\n");

		assertThat(description).isEqualTo("Returns the highest-rated movies, best first.");
		assertThat(this.diagnostics.warnings()).isEmpty();
	}

	@Test
	void describe_commentLinesOfInvisibleCharactersAboveTwoRootFields_shouldWarnAndReturnNull() {
		// Two root fields leave the schema without one sentence to offer, so the file is
		// the one source, and lines that render empty count as a file that stays silent.
		String description = describe("# \u00A0\n# \u200B\u3164\n"
				+ "query MoviesAndPeople {\n  topRatedMovies { title }\n  search(text: \"no\") { __typename }\n}\n");

		assertThat(description).isNull();
		assertThat(this.diagnostics.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).startsWith("lacks a description; write # lines"));
	}

	@Test
	void describe_commentHoldingWordsBesideInvisibleCharacters_shouldKeepTheLineAsWritten() {
		// One visible character makes a description, and the line travels as the file
		// wrote it.
		String description = describe(
				"# \u00A0Returns the best movies.\u200B\n" + "query TopRatedMovies {\n  topRatedMovies { title }\n}\n");

		assertThat(description).isEqualTo("\u00A0Returns the best movies.\u200B");
	}

	@ParameterizedTest(name = "a root field described as \"{0}\"")
	@ValueSource(strings = { "", " ", "\\u00A0", "\\u200B", "\\u3164\\u2800" })
	void describe_rootFieldDescribedWithoutAVisibleCharacter_shouldWarnAndReturnNull(String schemaDescription) {
		// The schema's sentence is the second source, and one that renders empty counts
		// as absent, so startup warns about a tool a model cannot choose by its words.
		// Each value is written into the schema as the escape it names.
		GraphQLSchema schema = SchemaGenerator.createdMockedSchema("""
				type Query {
				  "%s"
				  newest: [String!]!
				}
				""".formatted(schemaDescription));

		String description = describe(schema, "query Newest { newest }");

		assertThat(description).isNull();
		assertThat(this.diagnostics.warnings()).singleElement()
			.satisfies((warning) -> assertThat(warning.message()).startsWith("lacks a description; write # lines"));
	}

	private @Nullable String describe(String text) {
		return describe(TestSchemas.movies(), text);
	}

	private @Nullable String describe(GraphQLSchema schema, String text) {
		ParsedFile file = this.parser.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)),
				this.diagnostics);
		ValidatedOperation operation = new OperationValidator(schema).validate((OperationFile) file, this.diagnostics);
		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
		return new DescriptionResolver(schema).describe(operation, this.diagnostics);
	}

}
