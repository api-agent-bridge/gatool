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

package io.gatool.core.internal.naming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ToolNameRulesTests {

	@ParameterizedTest
	@ValueSource(strings = { "topRatedMovies", "top_rated_movies", "top-rated-movies", "movie2" })
	void isPortable_lettersDigitsUnderscoresAndHyphens_shouldAcceptName(String name) {
		assertThat(ToolNameRules.isPortable(name)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "top rated", "top.rated", "films/top", "najljepší" })
	void isPortable_spacesPunctuationOrAccents_shouldRejectName(String name) {
		assertThat(ToolNameRules.isPortable(name)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 64 })
	void isPortable_oneTo64Characters_shouldAcceptName(int length) {
		assertThat(ToolNameRules.isPortable("a".repeat(length))).isTrue();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 65, 128 })
	void isPortable_emptyOrLongerThan64Characters_shouldRejectName(int length) {
		assertThat(ToolNameRules.isPortable("a".repeat(length))).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "topRatedMovies", "top_rated_movies", "movie2", "_1" })
	void problemWith_nameAClientCanCall_shouldReturnNull(String name) {
		assertThat(ToolNameRules.problemWith(name)).isNull();
	}

	@Test
	void problemWith_nameOutsideThePortableSet_shouldNameThePortableSet() {
		assertThat(ToolNameRules.problemWith("top rated"))
			.isEqualTo("uses only letters, digits, '_' and '-', with at most 64 characters");
	}

	@ParameterizedTest
	@ValueSource(strings = { "---", "_", "-_-" })
	void problemWith_separatorsAlone_shouldNameTheLetterOrDigitRule(String name) {
		// These pass the portable set, so the second rule is what keeps "---" from
		// becoming a tool name.
		assertThat(ToolNameRules.problemWith(name)).isEqualTo("holds at least one letter or digit");
	}

}
