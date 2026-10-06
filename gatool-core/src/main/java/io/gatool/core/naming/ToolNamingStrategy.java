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

package io.gatool.core.naming;

import io.gatool.core.internal.naming.AsWrittenToolNamingStrategy;
import io.gatool.core.internal.naming.CamelCaseToolNamingStrategy;
import io.gatool.core.internal.naming.SnakeCaseToolNamingStrategy;

/**
 * Decides the tool name for a GraphQL name, such as the name of an operation.
 *
 * <p>
 * Tool names are part of the public tool contract. Agents and client configurations refer
 * to them, so changing the strategy of a running application is a breaking change.
 *
 * @author Željko Kozina
 */
@FunctionalInterface
public interface ToolNamingStrategy {

	/**
	 * Returns the tool name for a GraphQL name.
	 * @param graphQlName a GraphQL name, such as {@code TopRatedMovies}
	 * @return the tool name, such as {@code topRatedMovies}
	 * @throws IllegalArgumentException if the strategy cannot build a tool name from the
	 * GraphQL name
	 */
	String toolName(String graphQlName);

	/**
	 * Returns the default strategy, which converts names to camelCase.
	 * @return the camelCase strategy
	 */
	static ToolNamingStrategy camelCase() {
		return CamelCaseToolNamingStrategy.INSTANCE;
	}

	/**
	 * Returns the strategy that converts names to snake_case with Jackson 3's own
	 * translation, so {@code TopRatedMovies} becomes {@code top_rated_movies}.
	 * @return the snake_case strategy
	 */
	static ToolNamingStrategy snakeCase() {
		return SnakeCaseToolNamingStrategy.INSTANCE;
	}

	/**
	 * Returns the strategy that keeps the GraphQL name as the operation file writes it.
	 * @return the as-written strategy
	 */
	static ToolNamingStrategy asWritten() {
		return AsWrittenToolNamingStrategy.INSTANCE;
	}

}
