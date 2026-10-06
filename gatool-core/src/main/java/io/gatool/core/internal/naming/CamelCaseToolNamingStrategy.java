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

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import io.gatool.core.naming.ToolNamingStrategy;

/**
 * Converts GraphQL names to camelCase with the word rules of the Google Java Style Guide,
 * so {@code TopRatedMovies} and {@code top_rated_movies} both become
 * {@code topRatedMovies}, and {@code HTTPStatusCode} becomes {@code httpStatusCode}.
 *
 * @author Željko Kozina
 */
public final class CamelCaseToolNamingStrategy implements ToolNamingStrategy {

	/**
	 * The single instance of this stateless strategy.
	 */
	public static final CamelCaseToolNamingStrategy INSTANCE = new CamelCaseToolNamingStrategy();

	private CamelCaseToolNamingStrategy() {
	}

	@Override
	public String toolName(String graphQlName) {
		StringBuilder toolName = new StringBuilder();
		// Underscores separate words, and splitByCharacterTypeCamelCase finds the rest:
		// it starts a word wherever the character type changes, and keeps an acronym
		// together up to the capital that opens the next word, as in HTTP|Status.
		for (String part : StringUtils.split(graphQlName, '_')) {
			for (String word : StringUtils.splitByCharacterTypeCamelCase(part)) {
				appendWord(toolName, word);
			}
		}
		return ToolNameRules.requireNameable(graphQlName, toolName.toString());
	}

	private static void appendWord(StringBuilder toolName, String word) {
		if (toolName.isEmpty()) {
			toolName.append(word.toLowerCase(Locale.ROOT));
			return;
		}
		toolName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
	}

}
