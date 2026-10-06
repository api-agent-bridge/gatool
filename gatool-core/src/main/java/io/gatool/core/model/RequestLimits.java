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

package io.gatool.core.model;

/**
 * The limits the application expects its GraphQL API to apply to a request, which a
 * tool's document is compared with at startup and in the build check.
 *
 * <p>
 * GATool reads an operation file however long it is, and the API that receives the
 * document reads a request under limits of its own, so a document can start here and be
 * refused there on every call. GraphQL's introspection describes types and leaves such
 * limits out, which is why GATool cannot read them from the API and the application
 * states them. A tool whose document passes one earns a warning, and the tool is served
 * all the same, because the limit is what the application expects and the API is the one
 * that decides.
 *
 * <p>
 * The defaults are what graphql-java reads of a request, so they hold for an API built on
 * it that left its parser options alone. A value below 1 leaves that size unchecked.
 *
 * @param maxCharacters the characters the API reads of a document, matching
 * {@code gatool.api.request-limits.max-characters}
 * @param maxTokens the tokens the API reads of a document, matching
 * {@code gatool.api.request-limits.max-tokens}
 * @param maxWhitespaceTokens the whitespace tokens the API reads of a document, matching
 * {@code gatool.api.request-limits.max-whitespace-tokens}
 * @author Željko Kozina
 */
public record RequestLimits(int maxCharacters, int maxTokens, int maxWhitespaceTokens) {

	/**
	 * The configuration property that holds the three limits. A warning about a document
	 * names the limit it passed under this prefix.
	 */
	public static final String PROPERTY = "gatool.api.request-limits";

	/**
	 * The characters graphql-java reads of a request by default.
	 */
	public static final int DEFAULT_MAX_CHARACTERS = 1_048_576;

	/**
	 * The tokens graphql-java reads of a request by default.
	 */
	public static final int DEFAULT_MAX_TOKENS = 15_000;

	/**
	 * The whitespace tokens graphql-java reads of a request by default.
	 */
	public static final int DEFAULT_MAX_WHITESPACE_TOKENS = 200_000;

	/**
	 * Returns the limits of an application that leaves all three unset.
	 * @return what graphql-java reads of a request by default
	 */
	public static RequestLimits defaults() {
		return new RequestLimits(DEFAULT_MAX_CHARACTERS, DEFAULT_MAX_TOKENS, DEFAULT_MAX_WHITESPACE_TOKENS);
	}
}
