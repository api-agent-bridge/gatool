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

import java.util.List;
import java.util.Locale;
import java.util.Set;

import graphql.parser.ParserOptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class OperationValidatorTests {

	private static final String LOCATION = "file:/operations/Test.graphql";

	// A schema of its own, because the movie schema reaches its OneOf object through a
	// Non-Null argument alone, and the rule for a nullable one differs.
	private static final OperationValidator ONE_OF_VALIDATOR = new OperationValidator(SdlSchemaFactory.schemaFrom("""
			input Lookup @oneOf { id: ID  title: String }
			input Criteria { lookup: Lookup }
			type Movie { id: ID! }
			type Query {
			  movie(by: Lookup): Movie
			  movieByLookup(by: Lookup!): Movie
			  movieByCriteria(criteria: Criteria!): Movie
			}
			""", "file:/schema/oneof.graphqls"));

	private final OperationFileParser parser = new OperationFileParser();

	private final OperationValidator validator = new OperationValidator(TestSchemas.movies());

	private final OperationDiagnostics diagnostics = new OperationDiagnostics();

	@Test
	void validate_validQuery_shouldReturnPrintedDocumentWithoutComments() {
		ValidatedOperation operation = validate("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies($first: Int = 10) {
				  topRatedMovies(first: $first) {
				    id
				    title
				  }
				}
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
		assertThat(operation.printedDocument()).startsWith("query TopRatedMovies($first: Int = 10)")
			.doesNotContain("#");
	}

	@Test
	void validate_mutationWithOneOfLiteral_shouldPassValidation() {
		ValidatedOperation operation = validate("""
				mutation AddReview {
				  addReview(input: { movieId: "movie-1", score: 9 }) {
				    review { id }
				  }
				}
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
	}

	@Test
	void validate_nullableVariableSupplyingAOneOfField_shouldReportTheVariable() {
		// The GraphQL specification asks a variable that supplies a OneOf field to be
		// declared Non-Null, because a nullable one could carry null at call time and
		// a OneOf field always holds a value. graphql-java 25.0 leaves that rule out,
		// so GATool adds it, and a conforming API would refuse the same call.
		ValidatedOperation operation = validate("""
				query MovieById($id: ID) {
				  movie(by: { id: $id }) {
				    id
				    title
				  }
				}
				""");

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("fails validation against the schema:")
				.contains("$id", "MovieLookupInput", "Non-Null"));
	}

	@Test
	void validate_nullableVariableWithADefaultSupplyingAOneOfField_shouldPassValidation() {
		// The specification's IsVariableUsageAllowed lets a nullable variable fill a
		// Non-Null position, which a OneOf field is, when its definition carries a
		// default other than null. The argument here is MovieLookupInput!, and that is
		// where the exception is kept: graphql-js 16 and 17 accept this document too.
		ValidatedOperation operation = validate("""
				query MovieById($id: ID = "1") {
				  movie(by: { id: $id }) {
				    id
				  }
				}
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
	}

	@Test
	void validate_nullableVariableWithADefaultSupplyingAOneOfFieldThroughANonNullArgument_shouldPassValidation() {
		// The companion of the case below, on a schema that declares both shapes: the
		// same variable reaches the same OneOf object, and the Non-Null argument is what
		// keeps the default-value exception.
		ValidatedOperation operation = validate(ONE_OF_VALIDATOR, """
				query MovieById($id: ID = "1") { movieByLookup(by: { id: $id }) { id } }
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
	}

	@Test
	void validate_nullableVariableWithADefaultSupplyingAOneOfFieldThroughANullableArgument_shouldReportTheVariable() {
		// graphql-js applies the specification's sentence, "Variables used for OneOf
		// Input Object fields must be non-nullable", where the enclosing position is the
		// bare object type, so movie(by: Lookup) refuses $id: ID = "1" whatever the
		// default. GATool follows it there, because a default fills a variable the caller
		// leaves out and a caller can still send null for it.
		ValidatedOperation operation = validate(ONE_OF_VALIDATOR, """
				query MovieById($id: ID = "1") { movie(by: { id: $id }) { id } }
				""");

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("$id", "Lookup",
					"nullable argument or input field", "declares it ID!"));
	}

	@Test
	void validate_nullableVariableWithADefaultSupplyingAOneOfFieldThroughANullableInputField_shouldReportTheVariable() {
		// The input field lookup: Lookup is a nullable position the same way the
		// argument above is, so the same rule applies one level down.
		ValidatedOperation operation = validate(ONE_OF_VALIDATOR, """
				query MovieById($id: ID = "1") { movieByCriteria(criteria: { lookup: { id: $id } }) { id } }
				""");

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("$id", "nullable argument or input field",
					"declares it ID!"));
	}

	@Test
	void validate_nonNullVariableSupplyingAOneOfFieldThroughANullableArgument_shouldPassValidation() {
		ValidatedOperation operation = validate(ONE_OF_VALIDATOR, """
				query MovieById($id: ID!) { movie(by: { id: $id }) { id } }
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
	}

	@Test
	void validate_stringLiteralHoldingAControlCharacter_shouldReportTheCharacterAndWhereItSits() {
		// The file writes the escape \\u0001, the parser turns it into the character, and
		// AstPrinter writes that character raw, because it escapes seven characters and
		// this one is outside that set. graphql-java's own lexer reads the printed
		// document, so the re-parse does not catch it, and a lexer written to the
		// grammar's SourceCharacter refuses the whole document.
		ValidatedOperation operation = validate("""
				query Search {
				  search(text: "a\\u0001b") { __typename }
				}
				""");

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.startsWith("holds the control character U+0001 in the string literal at line 2, column 16")
				.contains("SourceCharacter"));
	}

	@Test
	void validate_stringLiteralHoldingATab_shouldPassValidation() {
		// U+0009 is a SourceCharacter, and AstPrinter escapes it as \\t anyway.
		ValidatedOperation operation = validate("""
				query Search {
				  search(text: "a\\tb") { __typename }
				}
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
	}

	@Test
	void validate_nullLiteralForAOneOfField_shouldReportTheField() {
		// graphql-java 25.0 counts the keys of a OneOf literal and accepts null as a
		// value, where the specification wants the one field to hold a value, and a
		// conforming API refuses the call.
		ValidatedOperation operation = validate("""
				query MovieById {
				  movie(by: { id: null }) {
				    id
				  }
				}
				""");

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("'id'", "MovieLookupInput", "null"));
	}

	@Test
	void validate_nonNullVariableSupplyingAOneOfField_shouldPassValidation() {
		ValidatedOperation operation = validate("""
				query MovieById($id: ID!) {
				  movie(by: { id: $id }) {
				    id
				    title
				  }
				}
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
	}

	@Test
	void validate_unknownFields_shouldReportEveryValidationError() {
		ValidatedOperation operation = validate("""
				query TopRatedMovies {
				  topRatedMovies {
				    tagline
				    budget
				  }
				}
				""");

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("fails validation against the schema:")
				.contains("tagline", "budget", "(line 3, column 5)", "(line 4, column 5)")
				// A line in the operation file itself prints plain, and only a line in a
				// shared fragment names its file.
				.doesNotContain("shared fragment file"));
	}

	@Test
	void validate_onAJvmDefaultingToGerman_shouldReportTheProblemInEnglish() {
		// Startup problems are read beside GATool's own English sentences and diffed
		// against what another machine printed, so the language stays the same wherever
		// the application runs.
		Locale wasDefault = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			ValidatedOperation operation = validate("""
					query TopRatedMovies {
					  topRatedMovies { tagline }
					}
					""");

			assertThat(operation).isNull();
			assertThat(this.diagnostics.problems()).singleElement()
				.satisfies((problem) -> assertThat(problem.message()).contains("Validation error")
					.doesNotContain("Validierungsfehler"));
		}
		finally {
			Locale.setDefault(wasDefault);
		}
	}

	@Test
	void parse_onAJvmDefaultingToGerman_shouldReportASyntaxProblemInEnglish() {
		Locale wasDefault = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try {
			this.parser.parse(
					new OperationSource(LOCATION, "query TopRatedMovies { topRated", Set.of(ToolExposureType.MCP)),
					this.diagnostics);

			assertThat(this.diagnostics.problems()).singleElement()
				.satisfies((problem) -> assertThat(problem.message()).contains("Invalid syntax")
					.doesNotContain("Ungültige Syntax"));
		}
		finally {
			Locale.setDefault(wasDefault);
		}
	}

	@Test
	void validate_onTheGraphQlJavaTheBuildManages_shouldRunTheOneOfRulesOnEveryDocument() {
		// graphql-java 25 calls the createRules hook on every validateDocument, ahead of
		// the traversal, so the OneOf rule joins the run for a valid document, a
		// document with problems and a document without an operation alike. 26 removed
		// the hook, and a validator built on it then runs graphql-java's rules alone.
		for (String text : new String[] { "query Q { topRatedMovies { id } }", "query Q { topRatedMovies { tagline } }",
				"fragment F on Movie { id }" }) {
			OperationValidator.WithOneOfRule oneOf = new OperationValidator.WithOneOfRule();
			oneOf.validateDocument(TestSchemas.movies(), OperationFileParser.parseDocument(text), Locale.ROOT);

			assertThat(oneOf.rulesCreated()).as(text).isTrue();
		}
	}

	@Test
	void validate_whereGraphQlJavaSkippedTheHook_shouldStopNamingGraphQlJava26() {
		// A consumer that resolves graphql-java 26 through its own override is outside
		// the build that refuses 26, so the guard is on their side: the document must not
		// pass as validated while the OneOf checks were left out.
		assertThatIllegalStateException().isThrownBy(() -> OperationValidator.requireOneOfRulesRan(false))
			.withMessageContaining("@oneOf")
			.withMessageContaining("graphql-java 26")
			.withMessageContaining("Spring Boot manages");
	}

	@Test
	void validate_printedDocumentWithAnEmojiWhoseSurrogatePairTwoReadsSplit_shouldPass() {
		// The file writes the emoji as an escape and AstPrinter writes the character
		// itself, so a file of ASCII alone prints a document with the pair on the
		// boundary, which the validator has to read back whole.
		String printedHead = "query Search {\n  search(text: \"";
		String padding = "y".repeat(4095 - printedHead.length());

		ValidatedOperation operation = validate(
				"query Search { search(text: \"" + padding + "\\uD83D\\uDE00\") { __typename } }");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(operation).isNotNull();
		assertThat(operation.printedDocument().indexOf("\uD83D\uDE00")).isEqualTo(4095);
	}

	@Test
	void validate_printedDocumentTheParserStopsReadingAtALimit_shouldNameTheLimitAndTheSizeOfTheDocument() {
		// A limit reached while reading the printed document back is a matter of size,
		// and the problem says so.
		OperationValidator limited = new OperationValidator(TestSchemas.movies(),
				ParserOptions.newParserOptions().maxTokens(5).build());

		ValidatedOperation operation = validate(limited, "query TopRatedMovies { topRatedMovies { id title } }");

		assertThat(operation).isNull();
		String printed = "query TopRatedMovies {\n  topRatedMovies {\n    id\n    title\n  }\n}\n";
		assertThat(this.diagnostics.problems()).singleElement()
			.extracting(OperationProblem::message)
			.asString()
			.startsWith("prints a document that holds " + printed.length() + " characters, and graphql-java's "
					+ "parser stopped reading it at a limit")
			.contains("More than 5 'grammar' tokens have been presented")
			.doesNotContain("invalid syntax")
			.doesNotContain("bug in GATool");
	}

	@Test
	void validate_printedDocumentWithSharedFragmentsTheParserStopsReading_shouldNameTheFragmentFiles() {
		// The shared fragments are where a printed document gets text its operation file
		// does not hold, so the problem names the files they came from.
		OperationValidator limited = new OperationValidator(TestSchemas.movies(),
				ParserOptions.newParserOptions().maxTokens(5).build());
		OperationFile file = (OperationFile) this.parser.parse(new OperationSource(LOCATION,
				"query TopRatedMovies { topRatedMovies { ...Card } }", Set.of(ToolExposureType.MCP)), this.diagnostics);
		FragmentFile fragments = (FragmentFile) this.parser.parse(new OperationSource("file:/operations/Card.graphql",
				"fragment Card on Movie { id title }", Set.of(ToolExposureType.MCP)), this.diagnostics);
		OperationFile assembled = SharedFragments.of(List.of(fragments), this.diagnostics)
			.attachTo(file, this.diagnostics);

		ValidatedOperation operation = limited.validate(assembled, this.diagnostics);

		assertThat(operation).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.extracting(OperationProblem::message)
			.asString()
			.startsWith("prints a document that holds ")
			.contains("More than 5 'grammar' tokens have been presented")
			.endsWith("The document holds the shared fragments of file:/operations/Card.graphql.");
	}

	private @Nullable ValidatedOperation validate(String text) {
		return validate(this.validator, text);
	}

	private @Nullable ValidatedOperation validate(OperationValidator schemaValidator, String text) {
		ParsedFile file = this.parser.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)),
				this.diagnostics);
		assertThat(file).isInstanceOf(OperationFile.class);
		return schemaValidator.validate((OperationFile) file, this.diagnostics);
	}

}
