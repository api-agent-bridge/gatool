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
import java.util.Set;

import graphql.language.Comment;
import graphql.language.Field;
import graphql.language.StringValue;
import graphql.parser.ParserOptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OperationFileParserTests {

	private static final String LOCATION = "file:/operations/Test.graphql";

	// U+1F600, which a Java string holds as the surrogate pair D83D DE00.
	private static final String EMOJI = "\uD83D\uDE00";

	private final OperationFileParser parser = new OperationFileParser();

	private final OperationDiagnostics diagnostics = new OperationDiagnostics();

	@Test
	void parse_namedQuery_shouldReturnOperationFile() {
		ParsedFile file = parse("""
				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(file).isInstanceOfSatisfying(OperationFile.class, (operationFile) -> {
			assertThat(operationFile.operationName()).isEqualTo("TopRatedMovies");
			assertThat(operationFile.operationType()).isEqualTo(OperationType.QUERY);
		});
		assertThat(this.diagnostics.problems()).isEmpty();
	}

	@Test
	void parse_namedMutation_shouldReturnAnOperationFileOfTypeMutation() {
		ParsedFile file = parse("""
				mutation AddReview {
				  addReview(input: { movieId: "movie-1", score: 9 }) { review { id } }
				}
				""");

		assertThat(file).isInstanceOfSatisfying(OperationFile.class,
				(operationFile) -> assertThat(operationFile.operationType()).isEqualTo(OperationType.MUTATION));
	}

	@Test
	void parse_onlyFragments_shouldReturnFragmentFile() {
		ParsedFile file = parse("""
				fragment MovieCard on Movie {
				  id
				  title
				}
				""");

		assertThat(file).isInstanceOf(FragmentFile.class);
		assertThat(this.diagnostics.problems()).isEmpty();
	}

	@Test
	void parse_subscription_shouldReportProblem() {
		ParsedFile file = parse("""
				subscription ReviewAdded {
				  reviewAdded(movieId: "movie-1") { score }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("holds a subscription");
	}

	@Test
	void parse_queryWithDefer_shouldReportProblem() {
		// Incremental delivery breaks the rule that refuses a subscription: a remote API
		// answers with multipart/mixed, which the transport refuses, and an embedded one
		// answers with the first payload and hasNext true, which reaches the model as a
		// whole result missing its deferred fields.
		ParsedFile file = parse("""
				query MovieWithDeferredReviews {
				  topRatedMovies {
				    title
				    ... @defer { rating }
				  }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("uses @defer").contains("single result");
	}

	@Test
	void parse_queryWithStreamOnANestedField_shouldReportProblem() {
		ParsedFile file = parse("""
				query MoviesStreamed {
				  topRatedMovies {
				    title
				    directors @stream(initialCount: 2) { name }
				  }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("uses @stream");
	}

	@Test
	void parse_deferInsideAFragmentFile_shouldReportTheSameProblemNamingTheFragmentFile() {
		// A shared fragment joins the document of every operation that spreads it, so a
		// directive an operation file cannot carry is refused in a fragment file as well,
		// and the problem names the file that holds it. graphql-java declares @defer in
		// every schema it builds, so without this the assembled document would validate
		// and the directive would reach the API.
		ParsedFile file = parse("""
				fragment Extra on Movie {
				  ... @defer { rating }
				}
				""");

		assertThat(file).isNull();
		assertThat(this.diagnostics.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo(LOCATION);
			assertThat(problem.message()).startsWith("uses @defer").contains("single result");
		});
	}

	@Test
	void parse_streamInsideAFragmentFile_shouldReportProblem() {
		ParsedFile file = parse("""
				fragment Credits on Movie {
				  directors @stream(initialCount: 1) { name }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("uses @stream");
	}

	@Test
	void parse_directiveOnAField_shouldReportWhereItBelongs() {
		// Validation would answer "Unknown directive 'gatool'", which reads as if the
		// directive were foreign to GATool, so the parser says where it goes.
		ParsedFile file = parse("""
				query TopRatedMovies {
				  topRatedMovies @gatool(name: "movies") { title }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("carries @gatool on the field topRatedMovies")
			.contains("belongs on the operation alone");
	}

	@Test
	void parse_directiveOnAFragmentDefinitionInAFragmentFile_shouldReportWhereItBelongs() {
		ParsedFile file = parse("""
				fragment MovieCard on Movie @gatool(title: "Cards") { id title }
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("carries @gatool on the fragment MovieCard");
	}

	@Test
	void parse_directiveOnTheOperationAndOnAnInlineFragment_shouldReportTheMisplacedOneAlone() {
		ParsedFile file = parse("""
				query TopRatedMovies @gatool(title: "Top rated") {
				  topRatedMovies { ... @gatool(openWorld: true) { title } }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("carries @gatool on an inline fragment");
	}

	@Test
	void parse_fileStartingWithAByteOrderMark_shouldReadTheContentWithoutIt() {
		// The lexer ignores U+FEFF and String.strip() keeps it, so with the mark left in,
		// the first line of the file compares unequal to the comment the lexer captured
		// and the first line of the description is dropped. The mark is an encoding
		// artefact, and every reader of the content below the parser sees the file
		// without it.
		ParsedFile file = parse(
				"﻿# Returns the highest-rated movies.\nquery TopRatedMovies { topRatedMovies { title } }\n");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(file).isInstanceOfSatisfying(OperationFile.class, (operationFile) -> {
			assertThat(operationFile.source().content()).startsWith("# Returns");
			assertThat(operationFile.operation().getComments()).hasSize(1);
		});
	}

	@Test
	void parse_queryWithAnOrdinaryDirective_shouldParse() {
		// @include and @skip answer in one piece, so they stay welcome.
		ParsedFile file = parse("""
				query TopRatedMovies($withRating: Boolean! = false) {
				  topRatedMovies {
				    title
				    rating @include(if: $withRating)
				  }
				}
				""");

		assertThat(file).isInstanceOf(OperationFile.class);
		assertThat(this.diagnostics.problems()).isEmpty();
	}

	@Test
	void parse_anonymousQuery_shouldTakeItsNameFromTheFile() {
		// GraphQL allows a lone anonymous operation, and every layer below the parser
		// reads a non-null operation name, so the file supplies one: Test.graphql becomes
		// Test, which the naming strategy then turns into the tool name.
		ParsedFile file = parse("""
				{
				  topRatedMovies { title }
				}
				""");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(file).isInstanceOf(OperationFile.class);
		assertThat(((OperationFile) file).operationName()).isEqualTo("Test");
		// The name reaches the printed document as well, so the request carries an
		// operationName and the API's own telemetry can group the call under it.
		assertThat(((OperationFile) file).operation().getName()).isEqualTo("Test");
	}

	@Test
	void parse_anonymousQueryInAFileNamedWithSeparators_shouldJoinTheWordsWithUnderscores() {
		// A hyphen cannot sit in a GraphQL name and an underscore can, and the camelCase
		// and snake_case strategies both read an underscore as a word break, so the tool
		// is named the way a written operation TopRatedMovies is. The case stays the
		// file's own: capitalising each word would give the as-written strategy a tool
		// named TopRatedMovies for a file that spells top-rated-movies.
		ParsedFile file = this.parser.parse(new OperationSource("file:/operations/top-rated_movies.graphql", """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(((OperationFile) file).operationName()).isEqualTo("top_rated_movies");
	}

	@Test
	void parse_anonymousQueryInAFileNamedInCamelCase_shouldKeepTheFilesOwnCase() {
		ParsedFile file = this.parser.parse(new OperationSource("file:/operations/topRatedMovies.graphql", """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(((OperationFile) file).operationName()).isEqualTo("topRatedMovies");
	}

	@Test
	void parse_anonymousQueryInAFileWhoseNameHoldsNoLetter_shouldReportProblem() {
		ParsedFile file = this.parser.parse(new OperationSource("file:/operations/123.graphql", """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(file).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.extracting(OperationProblem::message)
			.asString()
			.startsWith("holds an anonymous operation, and its file name does not yield a GraphQL name");
	}

	@Test
	void parse_anonymousQueryInAFileNamedBeyondAscii_shouldReportProblem() {
		// GraphQL's Name grammar is ASCII, and Character.isLetter accepts every Unicode
		// letter, so a name carrying the character would build a document graphql-java's
		// own parser refuses: "token recognition error at: 'Ü'". Startup validates the
		// AST it holds instead of a re-parse of the text it prints, so that document
		// would reach the API.
		ParsedFile file = this.parser.parse(new OperationSource("file:/operations/über-filme.graphql", """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(file).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.extracting(OperationProblem::message)
			.asString()
			.contains("a GraphQL name is ASCII");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "file:/operations/\u00FCber-filme.graphql", "file:/operations/u\u0308ber-filme.graphql",
			"file:/operations/%C3%BCber-filme.graphql", "file:/operations/u%CC%88ber-filme.graphql" })
	void parse_anonymousQueryInAFileNamedWithAnUmlautInEitherUnicodeForm_shouldReportTheSameProblem(String location) {
		// The name is composed in the first location, where the umlaut is the one letter
		// U+00FC, and decomposed in the second, where it is u followed by the combining
		// mark U+0308, which is the form macOS can hand a file name over in. The last two
		// are the same names as a URL escapes them. Left decomposed, the name would read
		// as the letter u and a word break, so a checkout on a Mac would name the tool
		// u_ber_filme while a checkout on Linux refuses the file.
		ParsedFile file = this.parser.parse(new OperationSource(location, """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(file).isNull();
		assertThat(this.diagnostics.problems()).singleElement()
			.extracting(OperationProblem::message)
			.asString()
			.startsWith("holds an anonymous operation, and its file name does not yield a GraphQL name")
			.contains("a GraphQL name is ASCII");
	}

	@Test
	void parse_anonymousQueryInAFileNamedInAsciiThatComposingLeavesAlone_shouldKeepItsName() {
		// Composing changes a name that holds a combining mark, which is outside ASCII,
		// so an ASCII name is left as it is.
		ParsedFile file = this.parser.parse(new OperationSource("file:/operations/uber-filme_2.graphql", """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(((OperationFile) file).operationName()).isEqualTo("uber_filme_2");
	}

	@Test
	void parse_anonymousQueryInAFileNamedWithPunctuation_shouldStillTakeAName() {
		// Punctuation is a word break, which is the case a file called movies (copy) or
		// movies.v2 hits, and every letter stays. A run of breaks is one underscore, and
		// a break at either end is dropped, so the name starts with a letter.
		ParsedFile file = this.parser.parse(new OperationSource("file:/operations/movies (copy).graphql", """
				{ topRatedMovies { title } }
				""", Set.of(ToolExposureType.MCP)), this.diagnostics);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(((OperationFile) file).operationName()).isEqualTo("movies_copy");
	}

	@Test
	void parse_aFileNameCarryingPercentEscapesOrDots_shouldReadAsTheNameItSpells() {
		// A resource URL escapes what a path cannot carry, and reading the stem to the
		// first dot would collapse movies.v1 and movies.v2 into one name.
		ParsedFile escaped = this.parser.parse(
				new OperationSource("file:/mcp/movies%20copy.graphql", "{ tagline }", Set.of(ToolExposureType.MCP)),
				this.diagnostics);
		ParsedFile dotted = this.parser.parse(
				new OperationSource("file:/mcp/movies.v2.graphql", "{ tagline }", Set.of(ToolExposureType.MCP)),
				this.diagnostics);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(((OperationFile) escaped).operationName()).isEqualTo("movies_copy");
		assertThat(((OperationFile) dotted).operationName()).isEqualTo("movies_v2");
	}

	@Test
	void parse_twoOperations_shouldReportProblem() {
		ParsedFile file = parse("""
				query TopRatedMovies { topRatedMovies { title } }
				query AllMovies { allMovies { title } }
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("holds 2 operations");
	}

	@Test
	void parse_typeDefinition_shouldReportProblem() {
		ParsedFile file = parse("""
				type Movie { id: ID! }
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("holds a type system definition");
	}

	@Test
	void parse_invalidSyntax_shouldReportProblemForTheFile() {
		ParsedFile file = parse("""
				query TopRatedMovies {
				  topRatedMovies { title
				""");

		assertThat(file).isNull();
		assertThat(this.diagnostics.problems()).singleElement().satisfies((problem) -> {
			assertThat(problem.location()).isEqualTo(LOCATION);
			assertThat(problem.message()).startsWith("has invalid GraphQL syntax");
		});
	}

	@Test
	void parse_commentAboveOperation_shouldKeepTheComment() {
		ParsedFile file = parse("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(file).isInstanceOfSatisfying(OperationFile.class,
				(operationFile) -> assertThat(operationFile.operation().getComments()).extracting(Comment::getContent)
					.containsExactly(" Returns the highest-rated movies, best first."));
	}

	@Test
	void parse_descriptionStringAheadOfTheOperation_shouldSayWhichSyntaxCarriesTheDescription() {
		// The September 2025 edition of GraphQL lets a string describe an operation, and
		// graphql-java 25.0 parses the edition before it, so the file fails at the
		// string, and the problem adds which syntax GATool reads a description from.
		ParsedFile file = parse("""
				\"""Returns the highest-rated movies.\"""
				query TopRatedMovies {
				  topRatedMovies { title }
				}
				""");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("has invalid GraphQL syntax")
			.contains("graphql-java 25.0 predates the September 2025 syntax")
			.contains("# comment line directly above the operation");
	}

	@Test
	void parse_descriptionStringAheadOfAFragment_shouldGiveTheSameHint() {
		// The edition describes a fragment the same way, and the one-line form of the
		// string is a description too.
		ParsedFile file = parse("\"The fields a card shows.\"\nfragment MovieCard on Movie { id title }\n");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).contains("graphql-java 25.0 predates the September 2025 syntax");
	}

	@Test
	void parse_stringLiteralOutOfPlaceInsideTheOperation_shouldKeepTheSyntaxMessageAlone() {
		// The hint is for a description alone, so a string the parser refuses somewhere
		// else keeps graphql-java's own sentence.
		ParsedFile file = parse("query TopRatedMovies { topRatedMovies(first: 1 \"x\") { title } }");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("has invalid GraphQL syntax").doesNotContain("September 2025");
	}

	@Test
	void parse_emptyFile_shouldSayTheFileIsEmpty() {
		// graphql-java answers an empty document with "offending token '<EOF>'", which
		// sends a reader to look for a syntax error in an empty file.
		ParsedFile file = parse("");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("is empty").doesNotContain("<EOF>");
	}

	@Test
	void parse_fileHoldingCommentsAlone_shouldSayTheFileIsEmpty() {
		ParsedFile file = parse("# Returns the highest-rated movies.\n\n# Nothing follows the comments.\n");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("is empty").doesNotContain("<EOF>");
	}

	@Test
	void parse_fileCutOffBeforeItsClosingBrace_shouldKeepTheEofSyntaxMessage() {
		// A truncated file ends on <EOF> as well, and that file holds an operation, so
		// the empty-file sentence stays out of it.
		ParsedFile file = parse("query TopRatedMovies { topRatedMovies { title }");

		assertThat(file).isNull();
		assertThat(onlyProblemMessage()).startsWith("has invalid GraphQL syntax").contains("<EOF>");
	}

	@Test
	void parse_fileWithCrlfLineEndings_shouldParseAndKeepTheCommentsWithoutTheCarriageReturns() {
		// An editor on Windows writes \r\n, and the lexer ends a comment at either line
		// terminator, so the comment content arrives without the carriage return and the
		// directive on the same line reads as usual.
		ParsedFile file = parse(
				"# Returns the highest-rated movies.\r\nquery TopRatedMovies @gatool(title: \"Top\") {\r\n"
						+ "  topRatedMovies { title }\r\n}\r\n");

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(file).isInstanceOfSatisfying(OperationFile.class, (operationFile) -> {
			assertThat(operationFile.directive().title()).isEqualTo("Top");
			assertThat(operationFile.operation().getComments()).extracting(Comment::getContent)
				.containsExactly(" Returns the highest-rated movies.");
		});
	}

	@Test
	void parse_syntaxErrorInAFileWithCrlfOrLoneCrLineEndings_shouldReportTheLineAndColumnOfTheLfFile() {
		// The lexer counts a line at \n alone, so a file written with lone \r would be
		// one long line to it, and a problem would name line 1 and a column an editor
		// does not show. The text is read with \n for every line terminator, and one
		// terminator stays one character, so the line and the column are the ones of the
		// file.
		String written = """
				# Returns the highest-rated movies.
				query TopRatedMovies {
				  topRatedMovies {
				    title )
				  }
				}
				""";

		String lineFeed = onlyProblemMessageOf(written);

		assertThat(lineFeed).startsWith("has invalid GraphQL syntax").contains("line 4 column 11");
		assertThat(onlyProblemMessageOf(written.replace("\n", "\r\n"))).isEqualTo(lineFeed);
		assertThat(onlyProblemMessageOf(written.replace('\n', '\r'))).isEqualTo(lineFeed);
	}

	@Test
	void parse_fileWithCrlfOrLoneCrLineEndings_shouldKeepTheContentWithLineFeedsAlone() {
		// DescriptionResolver finds the # lines in the content the parsed file carries,
		// so that content is the text the parser read.
		String written = "# Returns the highest-rated movies.\nquery TopRatedMovies {\n  topRatedMovies { title }\n}\n";

		for (String text : List.of(written.replace("\n", "\r\n"), written.replace('\n', '\r'))) {
			ParsedFile file = this.parser.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)),
					new OperationDiagnostics());

			assertThat(file).isInstanceOfSatisfying(OperationFile.class,
					(operationFile) -> assertThat(operationFile.source().content()).isEqualTo(written));
		}
	}

	@ParameterizedTest(name = "high surrogate at index {0}")
	@ValueSource(ints = { 4095, 8191, 12287 })
	void parse_emojiInAStringLiteralWhoseSurrogatePairTwoReadsSplit_shouldKeepTheValue(int index) {
		// ANTLR 4.13.2 fills its buffer 4,096 chars at a time and breaks a surrogate pair
		// that two reads split, so the lexer would meet a high surrogate on its own and
		// refuse the file with "token recognition error", at a line whose text is fine.
		String head = "query Search {\n  search(text: \"";
		String value = "y".repeat(index - head.length()) + EMOJI;
		String text = head + value + "\") { __typename }\n}\n";
		assertThat(text.indexOf(EMOJI)).isEqualTo(index);

		ParsedFile file = parse(text);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(file).isInstanceOfSatisfying(OperationFile.class, (operationFile) -> {
			Field search = (Field) operationFile.operation().getSelectionSet().getSelections().getFirst();
			assertThat(((StringValue) search.getArguments().getFirst().getValue()).getValue()).isEqualTo(value);
		});
	}

	@Test
	void parse_emojiInACommentWhoseSurrogatePairTwoReadsSplit_shouldKeepTheComment() {
		// The # lines above the operation are the description of the tool, so an emoji
		// is ordinary there.
		String head = "# Finds the movies and the people that match.\n#";
		String comment = " " + "x".repeat(4095 - head.length() - 1) + EMOJI;
		String text = head + comment + "\nquery Search {\n  search(text: \"a\") { __typename }\n}\n";
		assertThat(text.indexOf(EMOJI)).isEqualTo(4095);

		ParsedFile file = parse(text);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(file).isInstanceOfSatisfying(OperationFile.class,
				(operationFile) -> assertThat(operationFile.operation().getComments()).extracting(Comment::getContent)
					.containsExactly(" Finds the movies and the people that match.", comment));
	}

	@Test
	void parse_emojiInAFragmentFileWhoseSurrogatePairTwoReadsSplit_shouldReturnTheFragmentFile() {
		String head = "fragment Found on Query {\n  search(text: \"";
		String text = head + "y".repeat(4095 - head.length()) + EMOJI + "\") { __typename }\n}\n";
		assertThat(text.indexOf(EMOJI)).isEqualTo(4095);

		ParsedFile file = parse(text);

		assertThat(this.diagnostics.problems()).isEmpty();
		assertThat(file).isInstanceOf(FragmentFile.class);
	}

	@Test
	void parse_fileTheParserStopsReadingAtALimit_shouldNameTheLimitAndTheSizeOfTheFile() {
		// The limits of a request are lifted for a file, so a test reaches one by handing
		// the parser small options. graphql-java raises a limit as an
		// InvalidSyntaxException, and the problem names the limit and leaves the syntax
		// unjudged.
		String text = "query TopRatedMovies {\n  topRatedMovies { id title rating }\n}\n";
		OperationFileParser limited = new OperationFileParser(ParserOptions.newParserOptions().maxTokens(5).build());

		ParsedFile file = limited.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)),
				this.diagnostics);

		assertThat(file).isNull();
		assertThat(onlyProblemMessage())
			.startsWith("holds 62 characters, and graphql-java's parser stopped reading it at a limit")
			.contains("More than 5 'grammar' tokens have been presented")
			.doesNotContain("invalid GraphQL syntax");
	}

	@Test
	void parse_fileLongerThanTheCharacterLimit_shouldNameTheLimitAndTheSizeOfTheFile() {
		String text = "query TopRatedMovies {\n  topRatedMovies { id title rating }\n}\n";
		assertThat(text).hasSize(62);
		OperationFileParser limited = new OperationFileParser(
				ParserOptions.newParserOptions().maxCharacters(61).build());

		ParsedFile file = limited.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)),
				this.diagnostics);

		assertThat(file).isNull();
		assertThat(onlyProblemMessage())
			.startsWith("holds 62 characters, and graphql-java's parser stopped reading it at a limit")
			.contains("More than 61 characters have been presented")
			.doesNotContain("invalid GraphQL syntax");
	}

	private @Nullable ParsedFile parse(String text) {
		return this.parser.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)), this.diagnostics);
	}

	private String onlyProblemMessageOf(String text) {
		OperationDiagnostics own = new OperationDiagnostics();
		ParsedFile file = this.parser.parse(new OperationSource(LOCATION, text, Set.of(ToolExposureType.MCP)), own);

		assertThat(file).isNull();
		assertThat(own.problems()).hasSize(1);
		return own.problems().getFirst().message();
	}

	private String onlyProblemMessage() {
		assertThat(this.diagnostics.problems()).hasSize(1);
		return this.diagnostics.problems().getFirst().message();
	}

}
