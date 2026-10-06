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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NullArgumentsTests {

	private static final Set<String> FIRST_HAS_A_DEFAULT = Set.of("first");

	// ReviewFilterInput declares minScore: Int = 1 and genres without a default, so one
	// input object carries both cases.
	private static final Map<String, GraphQLInputType> FILTER_IS_A_REVIEW_FILTER = Map.of("filter",
			(GraphQLInputType) TestSchemas.movies().getType("ReviewFilterInput"));

	@Test
	void prepare_nullForAVariableWithADefault_shouldLeaveItOutSoTheDefaultApplies() {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("first", null);

		assertThat(NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), false)).isEmpty();
	}

	@Test
	void prepare_nullForAVariableWithoutADefault_shouldSendItAsTheModelWroteIt() {
		// GraphQL separates an explicit null from a missing value, so a model clearing a
		// value keeps that meaning.
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("tagline", null);

		assertThat(NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), false)).containsOnlyKeys("tagline")
			.containsEntry("tagline", null);
	}

	@Test
	void prepare_valueForAVariableWithADefault_shouldSendTheValue() {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("first", 2);

		assertThat(NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), false)).containsEntry("first", 2);
	}

	@Test
	void prepare_argumentTheModelLeftOut_shouldStayOut() {
		assertThat(NullArguments.prepare(Map.of(), FIRST_HAS_A_DEFAULT, Map.of(), false)).isEmpty();
	}

	@Test
	void prepare_nullForAnInputFieldWithADefault_shouldLeaveItOutSoTheDefaultApplies() {
		// Strict function calling sends null for every value the model would leave out,
		// inside an input object as much as at the top level. GraphQL reads an explicit
		// null as a value, so this null would defeat the default the schema declares.
		Map<String, Object> filter = new LinkedHashMap<>();
		filter.put("minScore", null);
		filter.put("genres", List.of("DRAMA"));

		Map<String, Object> prepared = NullArguments.prepare(Map.of("filter", filter), Set.of(),
				FILTER_IS_A_REVIEW_FILTER, false);

		assertThat(prepared).containsOnlyKeys("filter");
		assertThat(asMap(prepared.get("filter"))).containsOnly(Map.entry("genres", List.of("DRAMA")));
	}

	@Test
	void prepare_nullForAnInputFieldWithoutADefault_shouldSendItAsTheModelWroteIt() {
		// A model clearing a value the schema leaves undefaulted is a real request, and
		// GraphQL separates that null from an absent field.
		Map<String, Object> filter = new LinkedHashMap<>();
		filter.put("genres", null);

		Map<String, Object> prepared = NullArguments.prepare(Map.of("filter", filter), Set.of(),
				FILTER_IS_A_REVIEW_FILTER, false);

		Map<String, Object> walkedFilter = asMap(prepared.get("filter"));
		assertThat(walkedFilter).containsOnlyKeys("genres");
		assertThat(walkedFilter.get("genres")).isNull();
	}

	@Test
	void prepare_nullInsideAListOfInputObjects_shouldFollowTheSameRule() {
		// A list of input objects reaches the same fields, so the walk goes through the
		// list.
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("minScore", null);
		Map<String, GraphQLInputType> listOfFilters = Map.of("filters",
				GraphQLList.list(TestSchemas.movies().getTypeAs("ReviewFilterInput")));

		Map<String, Object> prepared = NullArguments.prepare(Map.of("filters", List.of(first)), Set.of(), listOfFilters,
				false);

		assertThat((List<?>) prepared.get("filters")).singleElement()
			.satisfies((element) -> assertThat(asMap(element)).isEmpty());
	}

	@Test
	void prepare_sendExplicitNulls_shouldSendEveryArgumentUnchanged() {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("first", null);
		arguments.put("tagline", null);

		assertThat(NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), true)).containsOnlyKeys("first",
				"tagline");
	}

	@Test
	void prepare_sendExplicitNulls_shouldReturnAMapOfItsOwn() {
		// The unchanged branch returns a copy, so a caller changing its own map after the
		// call leaves the variables the executor holds unchanged.
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("first", 2);

		Map<String, Object> prepared = NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), true);
		arguments.put("first", 3);

		assertThat(prepared).isNotSameAs(arguments).containsExactly(Map.entry("first", 2));
	}

	@Test
	void prepare_emptyArguments_shouldReturnAMapOfItsOwn() {
		Map<String, Object> arguments = new LinkedHashMap<>();

		Map<String, Object> prepared = NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), false);
		arguments.put("first", 3);

		assertThat(prepared).isNotSameAs(arguments).isEmpty();
	}

	@Test
	void prepare_severalArguments_shouldKeepTheOrderTheCallerSent() {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("after", "cursor");
		arguments.put("first", null);
		arguments.put("genre", "SCIFI");

		assertThat(NullArguments.prepare(arguments, FIRST_HAS_A_DEFAULT, Map.of(), false))
			.containsExactly(Map.entry("after", "cursor"), Map.entry("genre", "SCIFI"));
	}

	// One cast, named, so each assertion above reads as the value the model sent.
	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}

}
