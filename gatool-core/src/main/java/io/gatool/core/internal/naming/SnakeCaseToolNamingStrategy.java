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

import tools.jackson.databind.util.NamingStrategyImpls;

import io.gatool.core.naming.ToolNamingStrategy;

/**
 * Converts GraphQL names to snake_case, so {@code TopRatedMovies} and
 * {@code topRatedMovies} both become {@code top_rated_movies}.
 *
 * <p>
 * The translation comes from Jackson 3's public {@link NamingStrategyImpls#SNAKE_CASE},
 * which gatool-core already carries for the JSON Schema writer. The rule is therefore the
 * one Jackson documents, and one library answers for one name. Writing a second splitter
 * here would let GATool and Jackson disagree about the same GraphQL name.
 *
 * <p>
 * Jackson reads a run of capitals as one word, so {@code HTTPStatusCode} becomes
 * {@code httpstatus_code}. That differs from the camelCase default, which produces
 * {@code httpStatusCode}.
 *
 * @author Željko Kozina
 */
public final class SnakeCaseToolNamingStrategy implements ToolNamingStrategy {

	/**
	 * The single instance of this stateless strategy.
	 */
	public static final SnakeCaseToolNamingStrategy INSTANCE = new SnakeCaseToolNamingStrategy();

	private SnakeCaseToolNamingStrategy() {
	}

	@Override
	public String toolName(String graphQlName) {
		return ToolNameRules.requireNameable(graphQlName, NamingStrategyImpls.SNAKE_CASE.translate(graphQlName));
	}

}
