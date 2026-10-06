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

package io.gatool.boot.internal.execution;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the response GATool reads itself, over an {@code errors} member in each shape
 * an API or a gateway in front of it writes.
 */
class MapGraphQlResponseTests {

	@Test
	void getErrors_oneObjectWhereTheListBelongs_shouldReadItAsOneError() {
		// One object where the list belongs counts as one error, so a response with data
		// beside it is not a clean success.
		MapGraphQlResponse response = new MapGraphQlResponse(responseWith(Map.of("message", "Rate limit exceeded")));

		assertThat(response.getErrors()).singleElement()
			.satisfies((error) -> assertThat(error.getMessage()).isEqualTo("Rate limit exceeded"));
	}

	@Test
	void getErrors_oneStringWhereTheListBelongs_shouldReadItAsOneErrorCarryingTheString() {
		MapGraphQlResponse response = new MapGraphQlResponse(responseWith("Rate limit exceeded"));

		assertThat(response.getErrors()).singleElement()
			.satisfies((error) -> assertThat(error.getMessage()).isEqualTo("Rate limit exceeded"));
		assertThat(response.toMap()).containsEntry("errors", "Rate limit exceeded");
	}

	@Test
	void getErrors_aNullMember_shouldHoldNoErrors() {
		MapGraphQlResponse response = new MapGraphQlResponse(responseWith(null));

		assertThat(response.getErrors()).isEmpty();
	}

	@Test
	void getErrors_entriesThatAreAStringANumberAndNull_shouldReadEachAsOneError() {
		MapGraphQlResponse response = new MapGraphQlResponse(
				responseWith(Arrays.asList("Rate limit exceeded", 429, null)));

		assertThat(response.getErrors()).hasSize(3);
		assertThat(response.getErrors().getFirst().getMessage()).isEqualTo("Rate limit exceeded");
		assertThat(response.getErrors().get(1).getMessage()).isEqualTo("429");
		assertThat(response.toMap()).containsEntry("errors", Arrays.asList("Rate limit exceeded", 429, null));
	}

	@Test
	void getErrors_anEntryWithoutAMessage_shouldReadItWithANullMessage() {
		MapGraphQlResponse response = new MapGraphQlResponse(responseWith(List.of(Map.of("code", "UNAUTHENTICATED"))));

		assertThat(response.getErrors()).singleElement().satisfies((error) -> assertThat(error.getMessage()).isNull());
	}

	@Test
	void holdsSpecifiedErrors_theShapesTheSpecificationGives_shouldSayYes() {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("message", "The rating service is down.");
		error.put("locations", List.of(Map.of("line", 2, "column", 3)));
		error.put("path", List.of("movies", 0, "rating"));
		error.put("extensions", Map.of("code", "DOWNSTREAM"));
		Map<String, Object> nulls = new LinkedHashMap<>();
		nulls.put("message", null);
		nulls.put("locations", null);
		nulls.put("path", null);
		nulls.put("extensions", null);

		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(Map.of("data", Map.of()))).isTrue();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(null))).isTrue();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of()))).isTrue();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(error, nulls)))).isTrue();
		// An entry without a message is an object all the same, which is what the
		// transport's own response reads.
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(Map.of("code", "DENIED"))))).isTrue();
	}

	@Test
	void holdsSpecifiedErrors_anyOtherShape_shouldSayNo() {
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith("Rate limit exceeded"))).isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(Map.of("message", "Rate limit exceeded"))))
			.isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of("Rate limit exceeded")))).isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(429)))).isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(Arrays.asList((Object) null)))).isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(Map.of("message", 42))))).isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(Map.of("locations", "line 2")))))
			.isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(
				responseWith(List.of(Map.of("locations", List.of(Map.of("line", "2", "column", 3)))))))
			.isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(Map.of("path", "movies"))))).isFalse();
		assertThat(MapGraphQlResponse.holdsSpecifiedErrors(responseWith(List.of(Map.of("extensions", "none")))))
			.isFalse();
	}

	// A null member is a value Map.of refuses, so the map is built by hand.
	private static Map<String, Object> responseWith(Object errors) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("data", Map.of("topRatedMovies", List.of()));
		map.put("errors", errors);
		return map;
	}

}
