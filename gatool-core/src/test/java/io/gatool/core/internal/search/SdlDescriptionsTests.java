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

import graphql.language.ObjectTypeDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The block string a description becomes, read back by graphql-java's own parser.
 *
 * <p>
 * A description is the schema author's text, and the two tools publish it as SDL, so
 * every shape the grammar makes awkward has to come back as the text the schema holds.
 */
class SdlDescriptionsTests {

	@Test
	void blockString_aDescriptionEndingInABackslash_shouldWriteSdlTheParserReads() {
		// Written before the closing delimiter, the backslash makes \""", which the
		// grammar reads as the one escape a block string has. The lexer takes the longest
		// block string it can, so with a second description further down it runs on to
		// that one's delimiter and reads the declaration between as text.
		String sdl = SdlDescriptions.blockString("ends with a backslash \\") + "\ntype Movie {\n  "
				+ SdlDescriptions.blockString("The id.") + "\n  id: ID\n}";

		assertThatCode(() -> new Parser().parseDocument(sdl)).doesNotThrowAnyException();
		assertThat(descriptionOf(sdl)).isEqualTo("ends with a backslash \\");
	}

	@Test
	void blockString_aDescriptionEndingInABackslashAndNothingAfterIt_shouldWriteSdlTheParserReads() {
		// The lexer closes at the only delimiter there is, so this shape parses either
		// way; it stays here so that the closing stays the same for it.
		String sdl = SdlDescriptions.blockString("ends with a backslash \\") + "\ntype Movie { id: ID }";

		assertThat(descriptionOf(sdl)).isEqualTo("ends with a backslash \\");
	}

	@Test
	void blockString_aDescriptionEndingInAQuote_shouldWriteSdlTheParserReads() {
		String sdl = SdlDescriptions.blockString("the operator \"eq\"") + "\ntype Movie { id: ID }";

		assertThat(descriptionOf(sdl)).isEqualTo("the operator \"eq\"");
	}

	@Test
	void blockString_aDescriptionHoldingTheDelimiter_shouldWriteSdlTheParserReads() {
		String sdl = SdlDescriptions.blockString("send \"\"\" to reset") + "\ntype Movie { id: ID }";

		assertThat(descriptionOf(sdl)).isEqualTo("send \"\"\" to reset");
	}

	// The description the parser reads back for the one type the document declares.
	private static String descriptionOf(String sdl) {
		ObjectTypeDefinition type = (ObjectTypeDefinition) new Parser().parseDocument(sdl).getDefinitions().getFirst();
		return type.getDescription().getContent();
	}

}
