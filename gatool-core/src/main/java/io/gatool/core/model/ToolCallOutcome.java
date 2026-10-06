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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The result of one tool call, as plain Java. The MCP adapter turns it into a
 * {@code CallToolResult}, and the Spring AI adapter returns its text.
 *
 * @param text the result text that the caller receives
 * @param isError whether the caller reads the result as an error
 * @param structuredContent the same response as a map, which the MCP adapter returns
 * beside the text for a tool that publishes an output schema, or {@code null} for a tool
 * without an output schema. A value inside it can be null, because {@code data} is null
 * when the API answers with errors alone. MCP asks a server that returns structured
 * content to send the serialized JSON as text as well, which is the text above
 * @param kind what kind of answer this is, which an observation reads as its outcome tag.
 * The error flag says whether the caller reads an error, and the kind says why: a success
 * carries {@link Kind#SUCCESS}, and an error carries one of the other kinds
 * @author Željko Kozina
 */
public record ToolCallOutcome(String text, boolean isError, @Nullable Map<String, @Nullable Object> structuredContent,
		Kind kind) {

	/**
	 * The kinds of answer a tool call ends in.
	 */
	public enum Kind {

		/**
		 * The call ran and the caller reads the result as a success.
		 */
		SUCCESS,

		/**
		 * The call ran and the caller reads the result as an error: the response holds
		 * GraphQL errors under the partial result rule the application configured, or the
		 * tool refused the call in its own words. This is the kind an outcome built from
		 * the error flag alone carries.
		 */
		GRAPHQL_ERRORS,

		/**
		 * The API answered, and what came back is a refusal or lacks a GraphQL response:
		 * a status outside 2xx, a credential the API refused, a redirect, an empty body,
		 * or a body that is not a GraphQL response.
		 */
		API_REFUSED,

		/**
		 * The credential strategy could not supply the credential it sends, so the call
		 * did not go out.
		 */
		CREDENTIAL_UNAVAILABLE,

		/**
		 * The API sent more than {@code gatool.api.max-response-size}, so the response
		 * was left unread.
		 */
		RESPONSE_TOO_LARGE,

		/**
		 * The result text is longer than {@code gatool.results.max-characters}, so it was
		 * held back.
		 */
		RESULT_TOO_LARGE;

		/**
		 * Returns the kind as a lower-case word with hyphens, such as
		 * {@code graphql-errors}, for a metric tag or a log line.
		 * @return the label
		 */
		public String label() {
			return name().toLowerCase(Locale.ROOT).replace('_', '-');
		}

	}

	/**
	 * Creates an outcome and copies the structured content, so the adapter sends what the
	 * call produced however the caller changes its own map afterwards. A null map stays
	 * null, because a tool without an output schema returns text alone.
	 */
	// The map is copied, so the outcome holds what the call produced and the adapter
	// sends the same content however the caller changes its own map afterwards.
	// LinkedHashMap keeps the order the writer chose, which is the order the text
	// carries, and Map.copyOf refuses the null a data member holds, so the copy is made
	// by hand.
	//
	// The canonical constructor is written out, because the parameters a compact
	// constructor gets lose the type-use annotation inside the map type, and NullAway
	// then reads a parameter that differs from the component the header declares.
	public ToolCallOutcome(String text, boolean isError, @Nullable Map<String, @Nullable Object> structuredContent,
			Kind kind) {
		// The flag and the kind describe one answer, and a pair that disagrees would
		// put one thing in the tool result and another on the dashboard.
		if (isError == (kind == Kind.SUCCESS)) {
			throw new IllegalArgumentException("A success carries the kind SUCCESS and an error carries another "
					+ "kind, and this outcome has isError " + isError + " with the kind " + kind);
		}
		this.text = text;
		this.isError = isError;
		this.structuredContent = (structuredContent != null)
				? Collections.unmodifiableMap(new LinkedHashMap<>(structuredContent)) : null;
		this.kind = kind;
	}

	/**
	 * Creates an outcome whose kind follows the error flag: {@link Kind#SUCCESS} for a
	 * success and {@link Kind#GRAPHQL_ERRORS} for an error.
	 * @param text the result text
	 * @param isError whether the caller reads the result as an error
	 * @param structuredContent the same response as a map, or {@code null}
	 */
	public ToolCallOutcome(String text, boolean isError, @Nullable Map<String, @Nullable Object> structuredContent) {
		this(text, isError, structuredContent, isError ? Kind.GRAPHQL_ERRORS : Kind.SUCCESS);
	}

	/**
	 * Creates an outcome without structured content, which is what a tool without an
	 * output schema returns, and whose kind follows the error flag.
	 * @param text the result text
	 * @param isError whether the caller reads the result as an error
	 */
	public ToolCallOutcome(String text, boolean isError) {
		this(text, isError, null);
	}
}
