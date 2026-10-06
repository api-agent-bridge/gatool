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
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.gatool.core.naming.ToolNamingStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// @gatool applies to QUERY and MUTATION, and a GraphQL API declares its own directives
// alone, so these tests prove that an operation carrying it still validates and that what
// it said reaches the tool.
class ToolDirectiveTests {

	private final OperationCatalogFactory builder = OperationCatalogFactory
		.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
		.build();

	@Test
	void build_directiveWithANameAndATitle_shouldUseBothAndStillValidate() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies($first: Int = 10)
				  @gatool(name: "movies_top_rated", title: "Top rated movies")
				{
				  topRatedMovies(first: $first) { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.toolName()).isEqualTo("movies_top_rated");
			assertThat(tool.title()).isEqualTo("Top rated movies");
			// The document the executor sends leaves the directive behind, because a
			// GraphQL API answers it with UnknownDirective.
			assertThat(tool.printedDocument()).doesNotContain("gatool");
		});
	}

	@Test
	void build_directiveWithATitleAlone_shouldKeepTheNamingStrategysName() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "Top rated movies") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.toolName()).isEqualTo("topRatedMovies");
			assertThat(tool.title()).isEqualTo("Top rated movies");
		});
	}

	@Test
	void build_withoutTheDirective_shouldLeaveTheTitleUnset() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).singleElement().satisfies((tool) -> assertThat(tool.title()).isNull());
	}

	@Test
	void build_directiveSettingOpenWorld_shouldCarryTheHintToTheTool() {
		// An application knows what sits behind its GraphQL API and the starter does not,
		// so this hint travels from the operation file alone.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(openWorld: true) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> assertThat(tool.openWorld()).isTrue());
	}

	@Test
	void build_withoutTheDirective_shouldLeaveOpenWorldUnset() {
		// An absent hint stays out of the tool definition, which is what keeps the
		// starter from stating something it cannot know.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).singleElement().satisfies((tool) -> assertThat(tool.openWorld()).isNull());
	}

	@Test
	void build_openWorldThatIsNotABoolean_shouldReportTheProblem() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(openWorld: "yes") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("gatool", "true or false"));
	}

	@Test
	void build_explicitNameOutsideThePortableSet_shouldReportTheProblem() {
		// An explicit name faces the same check as a generated one, because every tool
		// name reaches the same clients.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: "top rated movies") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("'top rated movies'"));
	}

	@Test
	void build_directiveArgumentThatIsNotALiteral_shouldReportTheProblem() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: 42) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("gatool", "literal values"));
	}

	@Test
	void build_directiveArgumentThisReleaseLeavesUnread_shouldReportTheProblem() {
		// An argument the code leaves unread is a problem, because a tool that ignored it
		// would start without a word.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(destructive: true) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("destructive", "name", "title", "scopes",
					"openWorld"));
	}

	@Test
	void build_directiveListingScopes_shouldCarryEveryScopeToTheTool() {
		// The list is flat: every scope listed is required, in the order the file wrote
		// them.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(scopes: ["movies:read", "movies:ratings"]) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.scopes()).containsExactly("movies:read", "movies:ratings"));
	}

	@Test
	void build_directiveListingZeroScopes_shouldMarkTheToolOpenToEveryCaller() {
		// An empty list is a statement, so it stays apart from a file without the
		// argument.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(scopes: []) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> assertThat(tool.scopes()).isEmpty());
	}

	@Test
	void build_withoutScopes_shouldLeaveThemUnset() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).singleElement().satisfies((tool) -> assertThat(tool.scopes()).isNull());
	}

	@Test
	void build_scopesWrittenAsOneString_shouldReportTheProblem() {
		// GraphQL would coerce a single value into a list of one, and the file's intent
		// is the one thing the check cannot guess, so the shape is refused.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(scopes: "movies:read") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("scopes", "list of strings"));
	}

	@Test
	void build_scopeHoldingASpace_shouldReportTheProblem() {
		// A space separates scopes in a challenge, so the value would arrive as two.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(scopes: ["movies read"]) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("scopes", "holds a space"));
	}

	@Test
	void build_scopesHoldingANumber_shouldReportTheProblem() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(scopes: ["movies:read", 42]) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("scopes", "one scope"));
	}

	@Test
	void build_explicitNameOfSeparatorsAlone_shouldReportTheProblem() {
		// The portable set admits '-', so "---" passes it. An explicit name and a
		// strategy-built name go through the same two rules, and the second refuses a
		// name of separators alone.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: "---") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies(
					(problem) -> assertThat(problem.message()).contains("'---'", "holds at least one letter or digit"));
	}

	@Test
	void build_directiveRepeatingAnArgument_shouldReportTheArgument() {
		// graphql-java's UniqueArgumentNames rule runs after the parser has taken the
		// directive out, so the repeat is checked in the parser, where the first value
		// would otherwise win in silence.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: "a", name: "b") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("sets @gatool(name:) more than once"));
	}

	@Test
	void build_directiveOnAField_shouldReportWhereItBelongs() {
		// Validation would answer "Unknown directive 'gatool'" for this, a message that
		// reads as if the directive were foreign to GATool.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies @gatool(name: "movies") { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies(
					(problem) -> assertThat(problem.message()).startsWith("carries @gatool on the field topRatedMovies")
						.contains("belongs on the operation alone"));
	}

	@Test
	void build_directiveOnTheFilesOwnFragment_shouldReportWhereItBelongs() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies { ...MovieCard }
				}
				fragment MovieCard on Movie @gatool(title: "Cards") { id title }
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies(
					(problem) -> assertThat(problem.message()).startsWith("carries @gatool on the fragment MovieCard"));
	}

	@Test
	void build_misspelledDirectiveArgument_shouldReportTheProblem() {
		// graphql-java validates a document against the API's own schema, which does not
		// declare a directive of GATool's, so a misspelling reaches the parser
		// unchallenged.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(nmae: "movies_top_rated") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).contains("nmae"));
	}

	@Test
	void build_directiveArgumentThatIsNotALiteral_shouldNameTheLineAndColumn() {
		// Every node of the directive carries its source location, so the problem names
		// the line and the column beside the file.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: 42) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).endsWith("(line 2, column 30)"));
	}

	@Test
	void build_directiveOnAField_shouldNameTheLineAndColumn() {
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies {
				  topRatedMovies @gatool(name: "movies") { id title }
				}
				""")));

		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).endsWith("(line 3, column 18)"));
	}

	@Test
	void build_directiveWithABlankTitle_shouldReportTheProblem() {
		// A blank name is refused, and a blank title would reach the tool definition as
		// an empty string, which a client shows in place of the name.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("sets @gatool(title:) to a blank string"));
	}

	@ParameterizedTest(name = "a title of {0}")
	@ValueSource(strings = { "\\u00A0", "\\u200B", "\\u2007\\u202F", "\\u3000", "\\u2028", "\\u2029", "\\u0085",
			"\\uFEFF", "\\u00AD", "\\u2060", " \\u00A0\\t\\u200B" })
	void build_directiveWithATitleOfInvisibleCharactersAlone_shouldReportTheProblem(String title) {
		// isBlank() knows Java's whitespace, which leaves the no-break spaces out, and
		// the zero width space is a format character, so a title made of those passes it,
		// and a client renders such a title as an empty label. Each value is written into
		// the file as the escape it names.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "%s") {
				  topRatedMovies { id title }
				}
				""".formatted(title))));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message()).startsWith("sets @gatool(title:) to a blank string")
				.contains("the argument takes text a client reads")
				.endsWith("(line 2, column 30)"));
	}

	@Test
	void build_directiveWithATitleOfANoBreakSpaceAndAZeroWidthSpace_shouldNameTheCharactersItHolds() {
		// The file shows a pair of quotes around a space, or around what looks like an
		// empty string, so the problem says which characters the string holds. They are
		// written raw here, the way an editor pastes them.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "\u00A0\u200B\u00A0") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.startsWith("sets @gatool(title:) to a blank string, holding U+00A0 and U+200B, and the "
						+ "argument takes text a client reads"));
	}

	@Test
	void build_directiveWithANameOfANoBreakSpace_shouldReportItAsBlank() {
		// The name and the title are read by one method, so the name takes the same rule.
		// A name of U+00A0 would otherwise go on to the rules of a tool name, where the
		// problem speaks of letters and digits about a string that shows as a blank.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: "\\u00A0") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.startsWith("sets @gatool(name:) to a blank string, holding U+00A0, and the argument takes"));
	}

	@ParameterizedTest(name = "a title of U+{0}")
	@ValueSource(strings = { "3164", "115F", "1160", "FFA0", "2800", "034F", "17B4", "180B", "180F", "2065", "FE00",
			"FE0F", "FFF0", "FFF8", "1D159", "E0000", "E0100", "E0FFF" })
	void build_directiveWithATitleOfACharacterThatRendersEmpty_shouldReportTheProblem(String codePoint) {
		// Unicode classes the Hangul fillers as letters, the blank Braille pattern as a
		// symbol and the variation selectors as marks, so a title made of one of them
		// passes a rule that reads the class alone, and a client renders it as an empty
		// label. Each character is written raw, the way an editor pastes it.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "%s") {
				  topRatedMovies { id title }
				}
				""".formatted(Character.toString(Integer.parseInt(codePoint, 16))))));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.startsWith("sets @gatool(title:) to a blank string, holding U+" + codePoint
						+ ", and the argument takes text a client reads")
				.endsWith("(line 2, column 30)"));
	}

	@Test
	void build_directiveWithATitleOfFillersBesideSpaces_shouldReportTheProblem() {
		// The characters that render empty count the same way in any mix, so a filler
		// between a no-break space and a zero width space leaves the title blank.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "\u00A0\u3164\u200B\u2800 ") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.startsWith("sets @gatool(title:) to a blank string, holding U+00A0 and U+3164 and U+200B and "
						+ "U+2800, and the argument takes text a client reads"));
	}

	@Test
	void build_directiveWithANameOfAHangulFiller_shouldReportItAsBlank() {
		// The name takes the rule the title takes. A name of U+3164 would otherwise go on
		// to the rules of a tool name, where the problem speaks of letters and digits
		// about a string that shows as a blank.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(name: "\\u3164") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.tools()).isEmpty();
		assertThat(catalog.problems()).singleElement()
			.satisfies((problem) -> assertThat(problem.message())
				.startsWith("sets @gatool(name:) to a blank string, holding U+3164, and the argument takes"));
	}

	@ParameterizedTest(name = "a title of {0}")
	@ValueSource(strings = { "\\u00A0Top rated\\u200B", "\u6620\u753B", "\\uD83C\\uDFAC", "-",
			"\\u3164Top rated\\u2800", "\\uD55C", "\\u2801", "\\u2764\\uFE0F" })
	void build_directiveWithATitleHoldingAVisibleCharacter_shouldKeepTheTitleAsWritten(String title) {
		// One visible character is enough, in any script, and the title travels as the
		// file wrote it, with the invisible characters around it.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "%s") {
				  topRatedMovies { id title }
				}
				""".formatted(title))));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.title()).isNotBlank().isEqualTo(unescaped(title)));
	}

	// Reads the Unicode escapes of a GraphQL string, which is what the parser does with
	// the file.
	private static String unescaped(String text) {
		return Pattern.compile("\\\\u([0-9A-F]{4})")
			.matcher(text)
			.replaceAll((match) -> String.valueOf((char) Integer.parseInt(match.group(1), 16)));
	}

	@Test
	void build_directiveBesideACommentDescription_shouldKeepBoth() {
		// The description is read from the comments the lexer attached to the operation,
		// and the directive is taken off the same operation, so each leaves the other
		// alone.
		OperationCatalog catalog = this.builder.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(title: "Top rated movies") {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement().satisfies((tool) -> {
			assertThat(tool.description()).isEqualTo("Returns the highest-rated movies, best first.");
			assertThat(tool.title()).isEqualTo("Top rated movies");
		});
	}

	@Test
	void build_outputSchemaTrueWhereThePropertyIsOff_shouldPublishOne() {
		OperationCatalogFactory propertyOff = OperationCatalogFactory
			.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
			.build();

		OperationCatalog catalog = propertyOff.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(outputSchema: true) {
				  topRatedMovies { id title }
				}
				""")));

		assertThat(catalog.problems()).isEmpty();
		assertThat(catalog.tools()).singleElement()
			.satisfies((tool) -> assertThat(tool.outputSchema()).contains("\"topRatedMovies\""));
	}

	@Test
	void build_outputSchemaFalseWhereThePropertyIsOn_shouldHoldOneBack() {
		OperationCatalogFactory propertyOn = OperationCatalogFactory
			.builder(TestSchemas.movies(), ToolNamingStrategy.camelCase())
			.outputSchemaByDefault(true)
			.build();

		OperationCatalog catalog = propertyOn.create(List.of(sided("""
				# Returns the highest-rated movies, best first.
				query TopRatedMovies @gatool(outputSchema: false) {
				  topRatedMovies { id title }
				}
				"""), new OperationSource("file:/mcp/AllMovies.graphql", """
				# Returns every movie.
				query AllMovies { allMovies { id title } }
				""", Set.of(ToolExposureType.MCP))));

		assertThat(catalog.problems()).isEmpty();
		// The file that says false holds its schema back, and the file that stays quiet
		// publishes one, which is what the property decides for it.
		assertThat(catalog.tools()).extracting(ToolOperation::toolName, (tool) -> tool.outputSchema() != null)
			.containsExactlyInAnyOrder(tuple("topRatedMovies", false), tuple("allMovies", true));
	}

	private static OperationSource sided(String text) {
		return new OperationSource("file:/mcp/TopRatedMovies.graphql", text, Set.of(ToolExposureType.MCP));
	}

}
