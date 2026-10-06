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

package io.gatool.core.search;

import org.jspecify.annotations.Nullable;

/**
 * One field of the schema, as the search layer holds it.
 *
 * <p>
 * The text and its token count travel together, because the search budget is spent by
 * walking ranked entries and stopping before the next one would cross it. Counting once,
 * here, is what makes that walk exact.
 *
 * <p>
 * The path is read by a model, and nothing searches it: it holds the field names of other
 * types, which would match questions unrelated to this entry. So it sits beside the text
 * instead of inside it, and the count covers both, because both are read.
 *
 * @param coordinate the type name and the field name written {@code Type.field}, which
 * points at exactly one field of the schema
 * @param text the rendering a search index holds and a result shows, which
 * {@link CorpusFormat} chooses
 * @param path how an operation reaches this field's owner type from a root, from
 * {@code SchemaPaths}, or {@code null} for a root field and for a type beyond the reach
 * of every root
 * @param tokens what reading the text and the path costs a model, at four characters to a
 * token, which is the rule the startup listing already uses for a tool
 * @author Željko Kozina
 */
public record CorpusEntry(String coordinate, String text, @Nullable String path, int tokens) {

	private static final int CHARACTERS_PER_TOKEN = 4;

	/**
	 * Creates an entry, counting the tokens of what a model reads.
	 * @param coordinate the {@code Type.field} coordinate
	 * @param text the rendering
	 * @param path the way to the owner type, or {@code null}
	 * @return the entry, carrying the count
	 */
	public static CorpusEntry of(String coordinate, String text, @Nullable String path) {
		int length = text.length() + ((path != null) ? path.length() : 0);
		return new CorpusEntry(coordinate, text, path, length / CHARACTERS_PER_TOKEN);
	}
}
