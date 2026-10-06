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

import io.gatool.core.naming.ToolNamingStrategy;

/**
 * Returns the GraphQL name as the operation file writes it, so {@code TopRatedMovies}
 * stays {@code TopRatedMovies}.
 *
 * <p>
 * This strategy suits an operations folder whose operation names already follow the
 * application's own house style for tool names. The GraphQL grammar allows letters,
 * digits and underscores in a name, so the result reaches the portability check of
 * {@link ToolNameRules} with the length as the one rule left to fail.
 *
 * @author Željko Kozina
 */
public final class AsWrittenToolNamingStrategy implements ToolNamingStrategy {

	/**
	 * The single instance of this stateless strategy.
	 */
	public static final AsWrittenToolNamingStrategy INSTANCE = new AsWrittenToolNamingStrategy();

	private AsWrittenToolNamingStrategy() {
	}

	@Override
	public String toolName(String graphQlName) {
		return ToolNameRules.requireNameable(graphQlName, graphQlName);
	}

}
