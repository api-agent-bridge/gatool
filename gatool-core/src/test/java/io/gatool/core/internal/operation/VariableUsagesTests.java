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
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.gatool.core.internal.schema.SdlSchemaFactory;

import static org.assertj.core.api.Assertions.assertThat;

class VariableUsagesTests {

	// A schema of its own, because the movie schema reaches every argument through an
	// object type. A union declares members and leaves the fields to them, an interface
	// declares fields of its own, and the walk has to reach an argument under both.
	private static final GraphQLSchema RESULTS = SdlSchemaFactory.schemaFrom("""
			type Query {
			  one: Result
			  search: [Result!]!
			  node: Node
			}

			union Result = A | B

			interface Node {
			  id: ID!
			}

			type A implements Node {
			  id: ID!
			  f(
			    "Whether to include the current stage."
			    flag: Boolean! = false
			  ): String
			}

			type B {
			  g(flag: Boolean): String
			}
			""", "file:/schema/results.graphqls");

	// The three places a variable's value can land: an argument, an input field of an
	// object literal, and an item of a list literal. Each is declared once Non-Null and
	// once nullable.
	private static final GraphQLSchema PLACES = SdlSchemaFactory.schemaFrom("""
			type Query {
			  newest(first: Int! = 20, after: String): [String!]!
			  paged(paging: Paging): [String!]!
			  tagged(required: [String!], optional: [String], grid: [[String!]]): [String!]!
			}

			input Paging {
			  first: Int! = 20
			  after: String
			  tags: [String!]
			}
			""", "file:/schema/places.graphqls");

	// A schema of its own, because the argument of a directive is a place a value lands
	// as well, and the schemas above do not declare a directive. One argument declares
	// a default, one is Non-Null with a default, one takes an input object, and the
	// first directive takes every location of a document a variable can be written at.
	private static final GraphQLSchema DIRECTIVES = SdlSchemaFactory.schemaFrom("""
			directive @audience(
			  "Who the answer is written for."
			  status: String = "public"
			  note: String
			) on QUERY | FIELD | FRAGMENT_DEFINITION | FRAGMENT_SPREAD | INLINE_FRAGMENT

			directive @cache(ttl: Int! = 60) on FIELD

			directive @window(range: Range, tags: [String!]) on FIELD

			input Range {
			  size: Int! = 10
			  after: String
			}

			type Query {
			  newest(
			    "How many movies to return."
			    first: Int = 20
			  ): [Movie!]!
			}

			type Movie {
			  id: ID!
			  title: String
			}
			""", "file:/schema/directives.graphqls");

	@Test
	void of_variableInAnInlineFragmentUnderAUnionField_shouldFindTheArgumentItFills() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(RESULTS, file("""
				query One($flag: Boolean) {
				  one {
				    __typename
				    ... on A { f(flag: $flag) }
				  }
				}
				"""));

		// A union holds members and leaves the fields to them, so a walk that asks for a
		// type with fields would skip the union field whole and return the usages of the
		// variable empty.
		assertThat(usages).containsOnlyKeys("flag");
		assertThat(usages.get("flag")).singleElement().satisfies((usage) -> {
			assertThat(usage.description()).isEqualTo("Whether to include the current stage.");
			assertThat(usage.hasDefault()).isTrue();
		});
	}

	@Test
	void of_variableInAFragmentSpreadUnderAUnionField_shouldFindTheArgumentItFills() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(RESULTS, file("""
				query One($flag: Boolean) {
				  one { ...StageOfA }
				}

				fragment StageOfA on A {
				  f(flag: $flag)
				}
				"""));

		assertThat(usages).containsOnlyKeys("flag");
		assertThat(usages.get("flag")).singleElement().satisfies((usage) -> assertThat(usage.hasDefault()).isTrue());
	}

	@Test
	void of_variableUnderAListOfUnions_shouldFindTheArgumentItFills() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(RESULTS, file("""
				query Search($flag: Boolean) {
				  search {
				    ... on A { f(flag: $flag) }
				  }
				}
				"""));

		assertThat(usages).containsOnlyKeys("flag");
	}

	@Test
	void of_variableUnderTwoMembersOfAUnion_shouldFindBothArguments() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(RESULTS, file("""
				query One($flag: Boolean) {
				  one {
				    ... on A { f(flag: $flag) }
				    ... on B { g(flag: $flag) }
				  }
				}
				"""));

		// Each inline fragment names its own member, so each argument is read on the
		// type that declares it: f declares a default, and g leaves it out.
		assertThat(usages.get("flag")).extracting(VariableUsages.Usage::hasDefault).containsExactly(true, false);
	}

	@Test
	void of_variableInAnInlineFragmentUnderAnInterfaceField_shouldFindTheArgumentItFills() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(RESULTS, file("""
				query Node($flag: Boolean) {
				  node {
				    id
				    ... on A { f(flag: $flag) }
				  }
				}
				"""));

		assertThat(usages).containsOnlyKeys("flag");
		assertThat(usages.get("flag")).singleElement().satisfies((usage) -> assertThat(usage.hasDefault()).isTrue());
	}

	@Test
	void of_variableFillingAnArgument_shouldRecordWhetherTheArgumentIsNonNull() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(PLACES, file("""
				query Newest($first: Int, $after: String) {
				  newest(first: $first, after: $after)
				}
				"""));

		assertThat(usages.get("first")).containsExactly(new VariableUsages.Usage(null, true, true));
		assertThat(usages.get("after")).containsExactly(new VariableUsages.Usage(null, false, false));
	}

	@Test
	void of_variableInsideAnObjectLiteral_shouldRecordWhetherTheInputFieldIsNonNull() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(PLACES, file("""
				query Paged($first: Int, $after: String) {
				  paged(paging: { first: $first, after: $after })
				}
				"""));

		// The value lands in the input field, so the field's type is the one a null
		// meets, whatever the argument above it declares.
		assertThat(usages.get("first")).containsExactly(new VariableUsages.Usage(null, true, true));
		assertThat(usages.get("after")).containsExactly(new VariableUsages.Usage(null, false, false));
	}

	@Test
	void of_variableInsideAListLiteral_shouldRecordWhetherTheItemIsNonNull() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(PLACES, file("""
				query Tagged($required: String!, $optional: String, $cell: String!) {
				  tagged(required: [$required], optional: [$optional], grid: [[$cell]])
				}
				"""));

		// The value lands in an item of the list, so the item type is the one a null
		// meets: the three arguments are nullable and two of them refuse a null item.
		// An item cannot declare a default, so these usages cannot protect a variable.
		assertThat(usages.get("required")).containsExactly(new VariableUsages.Usage(null, false, true));
		assertThat(usages.get("optional")).containsExactly(new VariableUsages.Usage(null, false, false));
		assertThat(usages.get("cell")).containsExactly(new VariableUsages.Usage(null, false, true));
	}

	@Test
	void of_variableInsideAListInsideAnObjectLiteral_shouldRecordTheItemOfThatInputField() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(PLACES, file("""
				query Paged($tag: String!) {
				  paged(paging: { tags: [$tag] })
				}
				"""));

		assertThat(usages.get("tag")).containsExactly(new VariableUsages.Usage(null, false, true));
	}

	@Test
	void of_variableFillingTheIfOfInclude_shouldRecordTheArgumentOfTheDirective() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(DIRECTIVES, file("""
				query Newest($withTitle: Boolean! = false) {
				  newest { id title @include(if: $withTitle) }
				}
				"""));

		// A variable written at a directive has a usage there. The argument is Boolean!
		// without a default, and its sentence is the one graphql-java gives the built-in
		// directive.
		assertThat(usages.get("withTitle"))
			.containsExactly(new VariableUsages.Usage("Included when true.", false, true));
	}

	@Test
	void of_variableFillingTheIfOfSkip_shouldRecordTheArgumentOfTheDirective() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(DIRECTIVES, file("""
				query Newest($brief: Boolean! = true) {
				  newest { id title @skip(if: $brief) }
				}
				"""));

		assertThat(usages.get("brief")).containsExactly(new VariableUsages.Usage("Skipped when true.", false, true));
	}

	// One operation for each place a file can write a directive that takes a variable:
	// the operation, a root field, a nested field, an inline fragment, a fragment
	// spread and a fragment definition.
	@ParameterizedTest
	@ValueSource(strings = { "query Newest($status: String) @audience(status: $status) { newest { id } }",
			"query Newest($status: String) { newest @audience(status: $status) { id } }",
			"query Newest($status: String) { newest { id @audience(status: $status) } }",
			"query Newest($status: String) { newest { ... on Movie @audience(status: $status) { id } } }",
			"query Newest($status: String) { newest { ...MovieFields @audience(status: $status) } } "
					+ "fragment MovieFields on Movie { id }",
			"query Newest($status: String) { newest { ...MovieFields } } "
					+ "fragment MovieFields on Movie @audience(status: $status) { id }" })
	void of_variableFillingADirectiveArgument_shouldFindItWhereverTheFileWritesTheDirective(String operation) {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(DIRECTIVES, file(operation));

		assertThat(usages).containsOnlyKeys("status");
		assertThat(usages.get("status"))
			.containsExactly(new VariableUsages.Usage("Who the answer is written for.", true, false));
	}

	@Test
	void of_variableFillingANonNullDirectiveArgument_shouldRecordItsDefaultAndThatItIsNonNull() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(DIRECTIVES, file("""
				query Newest($ttl: Int) {
				  newest @cache(ttl: $ttl) { id }
				}
				"""));

		assertThat(usages.get("ttl")).containsExactly(new VariableUsages.Usage(null, true, true));
	}

	@Test
	void of_variableInsideALiteralOfADirectiveArgument_shouldRecordThePlaceItLands() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(DIRECTIVES, file("""
				query Newest($size: Int, $tag: String!) {
				  newest @window(range: { size: $size }, tags: [$tag]) { id }
				}
				"""));

		// A literal under a directive is walked the way one under a field is: the value
		// lands in the input field or in the item of the list.
		assertThat(usages.get("size")).containsExactly(new VariableUsages.Usage(null, true, true));
		assertThat(usages.get("tag")).containsExactly(new VariableUsages.Usage(null, false, true));
	}

	@Test
	void of_variableFillingAFieldArgumentAndADirectiveArgument_shouldRecordBothPlaces() {
		Map<String, List<VariableUsages.Usage>> usages = VariableUsages.of(DIRECTIVES, file("""
				query Newest($first: Int) {
				  newest(first: $first) @cache(ttl: $first) @audience(note: "page") { id }
				}
				"""));

		// The field's own arguments come first and its directives after them, in the
		// order the file reads.
		assertThat(usages.get("first")).containsExactly(
				new VariableUsages.Usage("How many movies to return.", true, false),
				new VariableUsages.Usage(null, true, true));
	}

	private static OperationFile file(String text) {
		OperationDiagnostics diagnostics = new OperationDiagnostics();
		ParsedFile parsed = new OperationFileParser()
			.parse(new OperationSource("file:/mcp/Operation.graphql", text, Set.of(ToolExposureType.MCP)), diagnostics);
		assertThat(diagnostics.problems()).isEmpty();
		assertThat(parsed).isInstanceOf(OperationFile.class);
		return (OperationFile) parsed;
	}

}
