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

package io.gatool.core.internal.search;

/**
 * Writes a schema description as the block string a GraphQL parser reads back unchanged.
 *
 * <p>
 * The text comes from the schema, so it can hold whatever the API's authors wrote, and
 * both {@link TypeDetail} and {@code io.gatool.core.search.SchemaCorpus} publish it as
 * SDL. Written as it is, a description holding {@code """} closes the block early, and
 * one ending with a quotation mark closes it with four quotes, which the lexer reads as a
 * closed string followed by a stray quote. In both shapes the model reads a truncated
 * description, and what follows sits beside a field declaration looking like structure.
 *
 * <p>
 * Public, because {@code SchemaCorpus} calls {@link #blockString} from the public search
 * package.
 *
 * @author Željko Kozina
 */
// The lexical grammar of GraphQL allows one escape inside a block string, \""", so that
// sequence is what a literal """ is written as. It is the only escape the grammar has, so
// a text ending in a quotation mark needs the closing delimiter on its own line, and so
// does a text ending in a backslash: written before the delimiter, the backslash makes
// \""", and the lexer takes the longest block string it could, running on to the next
// description's delimiter with the declaration between read as text. A block string drops
// a blank last line, so the text a parser reads back stays what the schema holds.
public final class SdlDescriptions {

	private static final String DELIMITER = "\"\"\"";

	private SdlDescriptions() {
	}

	/**
	 * Returns the description written as a block string, delimiters included.
	 * @param description the text the schema holds
	 * @return the SDL a parser reads back as this description
	 */
	public static String blockString(String description) {
		String escaped = description.replace(DELIMITER, "\\" + DELIMITER);
		String closing = (escaped.endsWith("\"") || escaped.endsWith("\\")) ? "\n" + DELIMITER : DELIMITER;
		return DELIMITER + escaped + closing;
	}

}
