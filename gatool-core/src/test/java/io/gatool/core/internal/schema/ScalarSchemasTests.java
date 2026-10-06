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

import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.internal.operation.OperationDiagnostics;
import io.gatool.core.internal.operation.OperationProblem;
import io.gatool.core.internal.operation.OperationWarning;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a scalar fragment may hold, and what startup says about the rest.
 *
 * <p>
 * The keyword set is closed instead of checked against the JSON Schema meta-schema,
 * because a fragment is read by clients with narrower rules than the meta-schema has.
 * Each refusal names the client that forces it, so the message teaches the fix.
 */
class ScalarSchemasTests {

	private static final GraphQLSchema SCHEMA = SdlSchemaFactory.schemaFrom("""
			scalar DateTime
			scalar Long
			scalar JSON

			type Query { reviews(since: DateTime, id: Long, payload: JSON): String! }
			""", "scalars.graphqls");

	// GraphQL names are case-sensitive, so a schema may declare a custom scalar beside
	// the specified one it shares its letters with.
	private static final GraphQLSchema ID_NAMESAKE = SdlSchemaFactory.schemaFrom("""
			scalar Id

			type Query { find(id: Id): String! }
			""", "id-namesake.graphqls");

	private static final GraphQLSchema STRING_NAMESAKE = SdlSchemaFactory.schemaFrom("""
			scalar string

			type Query { find(text: string): String! }
			""", "string-namesake.graphqls");

	@Test
	void of_fragmentUsingTheSixKeywords_shouldBeAccepted() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "format",
				"date-time", "description", "An RFC 3339 timestamp, for example 2026-09-18T10:00:00Z.")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("DateTime")).isNotNull();
	}

	@Test
	void of_anyOfOverTwoWireForms_shouldBeAccepted() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA,
				Map.of("Long",
						Map.of("anyOf",
								List.of(Map.of("type", "integer"),
										Map.of("type", "string", "pattern", "^-?(0|[1-9][0-9]{0,18})$")),
								"description", "A 64-bit signed integer. Send a large value as a quoted string.")),
				diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("Long")).isNotNull();
	}

	@Test
	void of_fragmentWithoutType_shouldBeAcceptedAsAnyJsonValue() {
		// Which is the right answer for a JSON scalar, and the one case where the empty
		// schema is the accurate description instead of a fallback.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA,
				Map.of("JSON", Map.of("description", "Arbitrary JSON, whose keys depend on the provider.")),
				diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("JSON")).isNotNull();
	}

	@Test
	void of_numericBound_shouldBeRefusedAndNameTheClient() {
		assertThat(problemFor(Map.of("type", "integer", "minimum", 0))).contains("minimum")
			.contains("Anthropic")
			.contains("HTTP 400")
			.contains("description");
	}

	@Test
	void of_oneOf_shouldBeRefusedAndPointAtAnyOf() {
		assertThat(problemFor(Map.of("oneOf", List.of(Map.of("type", "string"))))).contains("oneOf")
			.contains("Write anyOf");
	}

	@Test
	void of_keywordsOutsideTheSet_shouldEachBeRefusedWithTheirOwnReason() {
		assertThat(problemFor(Map.of("type", "string", "examples", List.of("x")))).contains("examples", "prose");
		assertThat(problemFor(Map.of("type", "string", "default", "x"))).contains("default", "GraphQL coerces");
		assertThat(problemFor(Map.of("type", "string", "const", "x"))).contains("const", "one-member enum");
		assertThat(problemFor(Map.of("type", "string", "minLength", 3))).contains("minLength");
		assertThat(problemFor(Map.of("type", "string", "nullable", true))).contains("nullable", "OpenAPI 3.0");
		assertThat(problemFor(Map.of("$ref", "#/$defs/x"))).contains("$ref", "inlines");
	}

	@Test
	void of_typeOutsideTheFour_shouldBeRefused() {
		assertThat(problemFor(Map.of("type", "object"))).contains("type").contains("Input Object");
		assertThat(problemFor(Map.of("type", "null"))).contains("null branch");
	}

	@Test
	void of_formatOutsideTheTen_shouldBeRefused() {
		assertThat(problemFor(Map.of("type", "string", "format", "country-code"))).contains("format", "date-time");
	}

	@Test
	void of_formatWithoutAStringType_shouldBeRefused() {
		assertThat(problemFor(Map.of("type", "integer", "format", "date-time")))
			.contains("format without type: string");
	}

	@Test
	void of_patternUsingAConstructAnthropicRefuses_shouldBeRefused() {
		assertThat(problemFor(Map.of("type", "string", "pattern", "^(?=.*a).+$"))).contains("lookaround")
			.contains("Invalid regex in pattern field");
		// A word boundary, written as one backslash in the regex.
		assertThat(problemFor(Map.of("type", "string", "pattern", "^\\b[a-z]+$"))).contains("word boundary");
	}

	@Test
	void of_patternUsingJavaOnlySyntax_shouldBeRefusedNamingTheConstruct() {
		// Each of these compiles in Java and means something else in ECMA 262, which is
		// what a client compiles the pattern as, so Pattern.compile alone would let a
		// fragment publish a pattern that refuses or accepts values outside what it
		// means.
		assertThat(problemFor(pattern("(?i)^abc$"))).contains("the inline flag (?i)").contains("ECMA 262");
		assertThat(problemFor(pattern("(?s)^a.b$"))).contains("the inline flag (?s)");
		assertThat(problemFor(pattern("(?m)^a$"))).contains("the inline flag (?m)");
		assertThat(problemFor(pattern("(?x) ^a$"))).contains("the inline flag (?x)");
		assertThat(problemFor(pattern("^(?i:a)b$"))).contains("the inline flag (?i)");
		assertThat(problemFor(pattern("^(?>a+)b$"))).contains("the atomic group (?>");
		assertThat(problemFor(pattern("^(?P<n>a)$"))).contains("the group (?P");
		assertThat(problemFor(pattern("\\Aabc$"))).contains("the anchor \\A");
		assertThat(problemFor(pattern("^abc\\z"))).contains("the anchor \\z");
		assertThat(problemFor(pattern("^abc\\Z"))).contains("the anchor \\Z");
		assertThat(problemFor(pattern("^\\Qa.b\\E$"))).contains("the quoting \\Q...\\E");
		assertThat(problemFor(pattern("^\\p{Alpha}+$"))).contains("the property class \\p{...}");
		assertThat(problemFor(pattern("^\\P{Digit}$"))).contains("the property class \\P{...}");
		assertThat(problemFor(pattern("^[a-z&&[^m]]+$"))).contains("the class intersection &&");
		assertThat(problemFor(pattern("^a*+$"))).contains("the possessive quantifier *+");
		assertThat(problemFor(pattern("^a++$"))).contains("the possessive quantifier ++");
		assertThat(problemFor(pattern("^a?+$"))).contains("the possessive quantifier ?+");
		assertThat(problemFor(pattern("^a{2}+$"))).contains("the possessive quantifier {n}+");
	}

	@Test
	void of_patternUsingAJavaOnlyEscape_shouldBeRefusedNamingTheConstruct() {
		// Each escape compiles in Java and reads as the plain letter in ECMA 262, where
		// an unknown escape is an identity escape, so a client's pattern would match the
		// letter where the fragment means a line break, a whitespace class or a code
		// point.
		assertThat(problemFor(pattern("^\\R$"))).contains("the linebreak matcher \\R").contains("ECMA 262");
		assertThat(problemFor(pattern("^\\h+$"))).contains("the horizontal whitespace class \\h");
		assertThat(problemFor(pattern("^\\H+$"))).contains("the horizontal whitespace class \\H");
		assertThat(problemFor(pattern("^\\v$"))).contains("the vertical whitespace class \\v");
		assertThat(problemFor(pattern("^\\V$"))).contains("the vertical whitespace class \\V");
		assertThat(problemFor(pattern("^\\X$"))).contains("the grapheme cluster \\X");
		assertThat(problemFor(pattern("^\\Gabc$"))).contains("the previous match anchor \\G");
		assertThat(problemFor(pattern("^\\e$"))).contains("the escape character \\e");
		assertThat(problemFor(pattern("^\\a$"))).contains("the bell character \\a");
		assertThat(problemFor(pattern("^\\N{DEGREE SIGN}$"))).contains("the named character \\N{...}");
		assertThat(problemFor(pattern("^\\x{41}$"))).contains("the code point \\x{...}");
	}

	@Test
	void of_patternUsingANestedClass_shouldBeRefused() {
		// Java reads a bracket inside a class as a nested class, and ECMA 262 reads it as
		// a literal bracket, so [a-z[0-9]] matches a digit in one engine and a bracket in
		// the other.
		assertThat(problemFor(pattern("^[a-z[0-9]]+$"))).contains("the nested class [...[...]]");
	}

	@Test
	void of_patternInsideTheSharedSubset_shouldBeAccepted() {
		// An escaped plus followed by a quantifier is one or more literal plus signs, and
		// the scan reads it that way. The rest are the ordinary spellings a fragment
		// uses, with the characters the scan watches for in places where they are
		// literals. The last four are the shared spellings closest to the Java-only
		// escapes: an escaped backslash before a letter, a two-digit hex escape, a
		// bracket escaped inside a class, and the whitespace escapes both engines define.
		for (String regex : List.of("^\\++$", "^[a-z]+$", "^(?:ab|cd)$", "^[+&?*]$", "^a{2,3}$", "^\\\\p$",
				"^[a-z]{1,3}&[0-9]$", "^\\\\R$", "^\\x41$", "^[a-z\\[\\]]+$", "^\\t\\n\\r\\f$")) {
			OperationDiagnostics diagnostics = new OperationDiagnostics();

			ScalarSchemas.of(SCHEMA, Map.of("DateTime", pattern(regex)), diagnostics);

			assertThat(diagnostics.problems()).as(regex).isEmpty();
		}
	}

	@Test
	void of_emptyAnyOfBranch_shouldBeRefused() {
		// An empty branch accepts every value, so the anyOf does, and the entry would
		// then silence the warning that names the scalar.
		assertThat(problemFor(Map.of("anyOf", List.of(Map.of(), Map.of("type", "string")))))
			.contains("empty anyOf branch")
			.contains("A schema says what the API takes for this scalar");
	}

	@Test
	void schemaFor_shouldReturnACopyTheCallerCannotChange() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string")), diagnostics);

		schemas.schemaFor("DateTime").put("type", "integer");

		// The writer sets the fragment's keywords into the property it builds, so a
		// shared node would let one tool's edit reach every later tool.
		assertThat(schemas.schemaFor("DateTime").path("type").asString()).isEqualTo("string");
	}

	@Test
	void of_patternThatDoesNotCompile_shouldBeRefused() {
		assertThat(problemFor(Map.of("type", "string", "pattern", "^[a-z"))).contains("does not compile");
	}

	@Test
	void of_enumHoldingAnObject_shouldBeRefused() {
		assertThat(problemFor(Map.of("type", "string", "enum", List.of(Map.of("a", 1)))))
			.contains("a string, a number or a boolean");
	}

	@Test
	void of_descriptionInsideABranch_shouldBeRefused() {
		assertThat(problemFor(Map.of("anyOf",
				List.of(Map.of("type", "integer", "description", "the number form"), Map.of("type", "string")))))
			.contains("description inside an anyOf branch");
	}

	@Test
	void of_anyOfWithOneBranch_shouldBeRefused() {
		assertThat(problemFor(Map.of("anyOf", List.of(Map.of("type", "string"))))).contains("branches for anyOf");
	}

	@Test
	void of_nameThatMatchesNoScalar_shouldBeRefusedAndListTheScalarsTheSchemaDeclares() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("DateTiem", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).singleElement()
			.extracting(OperationProblem::toString)
			.asString()
			.contains("DateTiem")
			.contains("does not name a custom scalar")
			.contains("DateTime, JSON, Long");
	}

	@Test
	void of_nameOfASpecifiedScalar_shouldBeRefused() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("ID", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).singleElement()
			.extracting(OperationProblem::toString)
			.asString()
			.contains("the GraphQL specification defines");
	}

	@Test
	void of_nameOfASpecifiedScalarInAnotherCase_shouldBeRefusedAsTheSpecifiedScalar() {
		// Relaxed binding lowercases a name that arrives from an environment variable,
		// and this schema leaves that name undeclared, so the key most likely means the
		// String the specification defines.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("string", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).singleElement()
			.extracting(OperationProblem::toString)
			.asString()
			.contains("the GraphQL specification defines");
	}

	@Test
	void of_customScalarNamedId_shouldTakeItsFragment() {
		// The custom scalars are looked up ahead of the comparison with the specified
		// names, so a fragment for a scalar the schema declares as Id reaches that
		// scalar.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(ID_NAMESAKE, Map.of("Id", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("Id")).isNotNull();
	}

	@Test
	void of_customScalarNamedStringInLowerCase_shouldTakeItsFragment() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(STRING_NAMESAKE, Map.of("string", Map.of("type", "string")),
				diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("string")).isNotNull();
	}

	@Test
	void of_customScalarNamedIdUnderALowercasedKey_shouldStillFindIt() {
		// GATOOL_INPUTS_SCALARSCHEMAS_ID_TYPE binds under "id", and the one custom scalar
		// that matches without regard to case is the Id this schema declares.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(ID_NAMESAKE, Map.of("id", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("Id")).isNotNull();
	}

	@Test
	void of_exactNameOfASpecifiedScalarBesideItsNamesake_shouldStillBeRefused() {
		// The key spells ID as the specification does, so it names the specified scalar,
		// whatever the schema declares beside it.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(ID_NAMESAKE, Map.of("ID", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).singleElement()
			.extracting(OperationProblem::toString)
			.asString()
			.contains("the GraphQL specification defines");
		assertThat(schemas.schemaFor("Id")).isNull();
	}

	@Test
	void of_nameThatMatchesNoScalarBesideANamesake_shouldBeRefusedAndListTheNamesake() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(STRING_NAMESAKE, Map.of("strnig", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).singleElement()
			.extracting(OperationProblem::toString)
			.asString()
			.contains("does not name a custom scalar")
			.contains("The schema declares: string.");
	}

	@Test
	void of_fragmentThatIsNotAnObject_shouldBeRefused() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("DateTime", "string"), diagnostics);

		assertThat(diagnostics.problems()).singleElement()
			.extracting(OperationProblem::toString)
			.asString()
			.contains("takes a JSON Schema object");
	}

	@Test
	void of_formatUri_shouldBePublishedWithAWarning() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "format", "uri")),
				diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(schemas.schemaFor("DateTime")).isNotNull();
		assertThat(diagnostics.warnings()).singleElement()
			.extracting(OperationWarning::message)
			.asString()
			.contains("OpenAI's strict subset leaves it out");
	}

	@Test
	void of_unanchoredPattern_shouldBePublishedWithAWarning() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "pattern", "[0-9]{4}")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(diagnostics.warnings()).singleElement()
			.extracting(OperationWarning::message)
			.asString()
			.contains("publishes a pattern that lacks ^ at its start or $ at its end")
			.endsWith("Write ^ at its start and $ at its end to match the whole value.");
	}

	@ParameterizedTest
	@ValueSource(strings = { "^[0-9]{4}", "[0-9]{4}$" })
	void of_patternAnchoredAtOneEndAlone_shouldWarnWithASentenceThatHoldsForIt(String pattern) {
		// The check asks for both anchors, so the sentence names both and holds for a
		// pattern that carries one.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "pattern", pattern)), diagnostics);

		assertThat(diagnostics.warnings()).singleElement()
			.extracting(OperationWarning::message)
			.asString()
			.contains("publishes a pattern that lacks ^ at its start or $ at its end")
			.doesNotContain("either end");
	}

	@Test
	void of_patternAnchoredAtBothEnds_shouldBePublishedWithoutAWarning() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "pattern", "^[0-9]{4}$")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(diagnostics.warnings()).isEmpty();
	}

	@Test
	void of_aFragmentWithAProblem_shouldBeLeftOutOfTheFragments() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "minimum", 1)),
				diagnostics);

		assertThat(diagnostics.problems()).isNotEmpty();
		// A refused fragment stops startup, and nothing half-applied reaches a tool.
		assertThat(schemas.schemaFor("DateTime")).isNull();
	}

	@Test
	void of_keywordsBesideAnyOf_shouldBeRefusedRatherThanDropped() {
		// The branches are what GATool publishes, so a pattern written beside them would
		// disappear, and pattern is the one allowed keyword that refuses a value.
		assertThat(problemFor(Map.of("type", "string", "pattern", "^[A-Z]+$", "anyOf",
				List.of(Map.of("type", "integer"), Map.of("type", "string")))))
			.contains("pattern and type beside anyOf")
			.contains("Move them into a branch");
	}

	@Test
	void of_emptyFragment_shouldBeRefused() {
		// What a half-written YAML block binds to, and what an entry stubbed out to be
		// filled in later leaves behind. It does not describe the scalar, and accepted it
		// would silence the warning that names it.
		assertThat(problemFor(Map.of())).contains("is empty");
	}

	@Test
	void of_enumMemberThatTheTypeRefuses_shouldBeRefused() {
		// No value satisfies both, so every call carrying the argument would be refused
		// inside GATool. A properties file makes this easy to write by accident, because
		// enum[0]=1 arrives as the string "1".
		assertThat(problemFor(Map.of("type", "integer", "enum", List.of("a", "b")))).contains("beside type integer")
			.contains("no value satisfies both");
		assertThat(problemFor(Map.of("type", "string", "enum", List.of(1, 2)))).contains("beside type string");
	}

	@Test
	void of_enumMembersMatchingTheType_shouldBeAccepted() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA, Map.of("Long", Map.of("type", "integer", "enum", List.of(1, 2, 3))), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
	}

	@Test
	void of_scalarNamedInAnotherCase_shouldStillFindIt() {
		// Relaxed binding lowercases a name that arrives from an environment variable, so
		// GATOOL_INPUTS_SCALARSCHEMAS_DATETIME_TYPE binds under "datetime".
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas schemas = ScalarSchemas.of(SCHEMA, Map.of("datetime", Map.of("type", "string")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		// Keyed by the name the schema spells, because that is what the writer looks up.
		assertThat(schemas.schemaFor("DateTime")).isNotNull();
	}

	@Test
	void of_formatUriInsideABranch_shouldWarnAboutTheUnanchoredPatternItDoesAtTheTop() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA,
				Map.of("DateTime",
						Map.of("anyOf", List.of(Map.of("type", "string", "format", "uri"), Map.of("type", "integer")))),
				diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(diagnostics.warnings()).singleElement()
			.extracting(OperationWarning::message)
			.asString()
			.contains("OpenAI's strict subset leaves it out");
	}

	@Test
	void of_unanchoredPatternInsideABranch_shouldWarnAboutTheUnanchoredPattern() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.of(SCHEMA,
				Map.of("Long",
						Map.of("anyOf",
								List.of(Map.of("type", "integer"), Map.of("type", "string", "pattern", "[0-9]{4}")))),
				diagnostics);

		assertThat(diagnostics.warnings()).singleElement()
			.extracting(OperationWarning::message)
			.asString()
			.contains("publishes a pattern that lacks ^ at its start or $ at its end");
	}

	@Test
	void ofResults_fragmentCarryingARefusedKeyword_shouldNameTheResultsProperty() {
		// The message points at the line to change, and an override is written under
		// gatool.results, so that is the property it has to name.
		assertThat(resultProblemFor("DateTime", Map.of("type", "integer", "minimum", 0)))
			.startsWith("The property gatool.results.scalar-schemas.DateTime carries the keyword 'minimum'");
	}

	@Test
	void ofResults_nameThatMatchesNoScalar_shouldNameTheResultsProperty() {
		assertThat(resultProblemFor("DateTiem", Map.of("type", "string")))
			.startsWith("The property gatool.results.scalar-schemas.DateTiem does not name a custom scalar")
			.contains("DateTime, JSON, Long");
	}

	@Test
	void ofResults_unanchoredPattern_shouldWarnNamingTheResultsProperty() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas.ofResults(SCHEMA, Map.of("DateTime", Map.of("type", "string", "pattern", "[0-9]{4}")),
				diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(diagnostics.warnings()).singleElement()
			.extracting(OperationWarning::toString)
			.asString()
			.startsWith("The property gatool.results.scalar-schemas.DateTime publishes a pattern");
	}

	@Test
	void ofResults_theProblemsOfAnInputFragment_shouldReadTheSameApartFromTheProperty() {
		// One set of rules checks both maps, so a fragment refused in one is refused in
		// the other for the same reason, and the input side's message stays as it was.
		Map<String, Object> fragment = Map.of("type", "string", "format", "date-time", "maxLength", 30);

		String input = problemFor(fragment);
		String result = resultProblemFor("DateTime", fragment);

		assertThat(input).startsWith("The property gatool.inputs.scalar-schemas.DateTime carries the keyword "
				+ "'maxLength', and GATool publishes anyOf, description, enum, format, pattern, type. The client "
				+ "subsets disagree about it, and no measurement settles it. State the limit in description.");
		assertThat(result).isEqualTo(input.replace("gatool.inputs.", "gatool.results."));
	}

	@Test
	void ofResults_fragmentHoldingADescriptionAlone_shouldBeAccepted() {
		// The smallest fragment the rules accept, and the one that switches the reuse of
		// the input fragment off for a scalar.
		OperationDiagnostics diagnostics = new OperationDiagnostics();

		ScalarSchemas overrides = ScalarSchemas.ofResults(SCHEMA,
				Map.of("DateTime", Map.of("description", "A moment, in one of several forms.")), diagnostics);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(String.valueOf(overrides.schemaFor("DateTime")))
			.isEqualTo("{\"description\":\"A moment, in one of several forms.\"}");
	}

	@Test
	void ofResults_emptyFragment_shouldSayWhatRemovingTheEntryDoesForAResult() {
		// Removing an entry from the input map leaves the scalar with the empty schema.
		// Removing one from the results map hands the scalar back to the input fragment.
		assertThat(resultProblemFor("DateTime", Map.of()))
			.startsWith("The property gatool.results.scalar-schemas.DateTime is empty. A schema says what the "
					+ "API returns for this scalar")
			.contains("or remove the entry and let the output schema reuse what "
					+ "gatool.inputs.scalar-schemas.DateTime says.");
	}

	@Test
	void ofResults_enumMemberThatTheTypeRefuses_shouldSayThatEveryResultFails() {
		assertThat(resultProblemFor("DateTime", Map.of("type", "integer", "enum", List.of("a", "b"))))
			.contains("beside type integer")
			.contains("so no value satisfies both and every result carrying this scalar fails the output "
					+ "schema after the API ran the call.");
	}

	@Test
	void of_emptyFragmentAndUnsatisfiableEnum_shouldKeepTheWordsTheInputSideHad() {
		assertThat(problemFor(Map.of()))
			.isEqualTo("The property gatool.inputs.scalar-schemas.DateTime is empty. A schema says what the "
					+ "API takes for this scalar, so give it a type, a format, a pattern, an enum, an anyOf "
					+ "or at least a description, or remove the entry and let the scalar keep the empty " + "schema.");
		assertThat(problemFor(Map.of("type", "integer", "enum", List.of("a", "b"))))
			.isEqualTo("The property gatool.inputs.scalar-schemas.DateTime has an enum holding \"a\" beside "
					+ "type integer, so no value satisfies both and every call carrying this argument is "
					+ "refused before it reaches the API. A value from a properties file arrives as a string, "
					+ "so write the enum in YAML to keep its type.");
	}

	@Test
	void forResults_anInputFragment_shouldKeepItsTypeItsFormatAndItsDescription() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas inputs = ScalarSchemas.of(SCHEMA, Map.of("DateTime", Map.of("type", "string", "format",
				"date-time", "pattern", "^[0-9T:Z.+-]+$", "description", "An RFC 3339 timestamp.")), diagnostics);

		ScalarSchemas published = inputs.forResults(ScalarSchemas.none());

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(String.valueOf(published.schemaFor("DateTime"))).isEqualTo(
				"{\"type\":\"string\"," + "\"format\":\"date-time\",\"description\":\"An RFC 3339 timestamp.\"}");
		// The input side keeps the fragment as the operator wrote it.
		assertThat(String.valueOf(inputs.schemaFor("DateTime"))).contains("\"pattern\"");
	}

	@Test
	void forResults_anOverride_shouldTakeThePlaceOfTheInputFragmentForThatScalarAlone() {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas inputs = ScalarSchemas.of(SCHEMA,
				Map.of("DateTime", Map.of("type", "string"), "Long", Map.of("type", "integer")), diagnostics);
		ScalarSchemas overrides = ScalarSchemas.ofResults(SCHEMA,
				Map.of("Long", Map.of("type", "string", "pattern", "^-?[0-9]+$")), diagnostics);

		ScalarSchemas published = inputs.forResults(overrides);

		assertThat(diagnostics.problems()).isEmpty();
		assertThat(String.valueOf(published.schemaFor("DateTime"))).isEqualTo("{\"type\":\"string\"}");
		// Compared as a tree, because the fragment keeps the order its map was written
		// in, and Map.of hands its entries over in an order that changes between runs.
		assertThat(published.schemaFor("Long"))
			.isEqualTo(JsonMapper.shared().readTree("{\"type\":\"string\",\"pattern\":\"^-?[0-9]+$\"}"));
		assertThat(published.schemaFor("JSON")).isNull();
	}

	private static Map<String, Object> pattern(String regex) {
		return Map.of("type", "string", "pattern", regex);
	}

	private static String problemFor(Map<String, Object> fragment) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas.of(SCHEMA, Map.of("DateTime", fragment), diagnostics);
		assertThat(diagnostics.problems()).isNotEmpty();
		return String.join(" | ", diagnostics.problems().stream().map(OperationProblem::toString).toList());
	}

	private static String resultProblemFor(String scalarName, Map<String, Object> fragment) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ScalarSchemas.ofResults(SCHEMA, Map.of(scalarName, fragment), diagnostics);
		assertThat(diagnostics.problems()).isNotEmpty();
		return String.join(" | ", diagnostics.problems().stream().map(OperationProblem::toString).toList());
	}

}
