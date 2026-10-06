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
import org.junit.jupiter.params.provider.CsvSource;

import io.gatool.core.naming.ToolNamingStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SnakeCaseToolNamingStrategyTests {

	private final ToolNamingStrategy strategy = ToolNamingStrategy.snakeCase();

	@ParameterizedTest(name = "{0} becomes {1}")
	@CsvSource({ "TopRatedMovies, top_rated_movies", "topRatedMovies, top_rated_movies",
			"top_rated_movies, top_rated_movies",
			// Jackson reads a run of capitals as one word.
			"HTTPStatusCode, httpstatus_code" })
	void toolName_graphQlName_shouldConvertToSnakeCase(String graphQlName, String expectedToolName) {
		assertThat(this.strategy.toolName(graphQlName)).isEqualTo(expectedToolName);
	}

	@Test
	void toolName_onlyUnderscores_shouldThrowIllegalArgumentException() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.strategy.toolName("___"))
			.withMessageContaining("'___'");
	}

}
