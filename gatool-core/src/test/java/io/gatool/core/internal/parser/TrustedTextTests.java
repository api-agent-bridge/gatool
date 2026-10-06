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

package io.gatool.core.internal.parser;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.ParserOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class TrustedTextTests {

	// U+1F600, which a Java string holds as the surrogate pair D83D DE00.
	private static final String EMOJI = "\uD83D\uDE00";

	@Test
	void withLineFeeds_everyLineTerminatorGraphQlReads_shouldBecomeOneLineFeedEach() {
		// \r\n is one terminator, so it becomes one line feed, and the lone \r beside it
		// becomes one of its own: the text keeps its four lines.
		assertThat(TrustedText.withLineFeeds("one\r\ntwo\rthree\nfour\r\n")).isEqualTo("one\ntwo\nthree\nfour\n");
	}

	@Test
	void withLineFeeds_aBlankLineWrittenWithCarriageReturns_shouldStayABlankLine() {
		assertThat(TrustedText.withLineFeeds("one\r\n\r\ntwo\r\rthree")).isEqualTo("one\n\ntwo\n\nthree");
	}

	@Test
	void withLineFeeds_textWrittenWithLineFeeds_shouldComeBackAsItWas() {
		String text = "query TopRatedMovies {\n  topRatedMovies { title }\n}\n";

		assertThat(TrustedText.withLineFeeds(text)).isSameAs(text);
	}

	@Test
	void parse_emojiAtEveryIndexAroundTheReadsOfAntlr_shouldKeepTheValue() {
		// ANTLR reads 4,096 chars at a time, so the pair is split where its high
		// surrogate is the last char of a read, at index 4,095 and every 4,096 after.
		// The indexes on both sides of each boundary show that the reader leaves every
		// other read alone.
		String head = "{ search(text: \"";
		for (int boundary = 4096; boundary <= 16384; boundary += 4096) {
			for (int index = boundary - 4; index <= boundary + 2; index++) {
				String value = "y".repeat(index - head.length()) + EMOJI + " and the text behind it";

				Document document = TrustedText.parse(head + value + "\") { __typename } }", null);

				assertThat(valueOfTheFirstArgument(document)).as("high surrogate at index %s", index).isEqualTo(value);
			}
		}
	}

	@Test
	void parse_twoEmojiInARow_shouldKeepBothWhereTheSecondStartsARead() {
		// The first read stops ahead of the pair on its last char, so the next read
		// starts with that pair, and the pair behind it sits inside the same read.
		String head = "{ search(text: \"";
		String value = "y".repeat(4095 - head.length()) + EMOJI + EMOJI;

		Document document = TrustedText.parse(head + value + "\") { __typename } }", null);

		assertThat(valueOfTheFirstArgument(document)).isEqualTo(value);
	}

	@Test
	void parse_syntaxErrorBehindAPairThatTwoReadsWouldSplit_shouldNameItsLineAndColumn() {
		// A read that ends one char early moves where the next one starts, and the
		// lexer counts lines and columns over the whole text, so the position stays.
		String head = "{ search(text: \"";
		String text = head + "y".repeat(4095 - head.length()) + EMOJI + "\") {\n  __typename )\n}";

		InvalidSyntaxException refused = catchThrowableOfType(InvalidSyntaxException.class,
				() -> TrustedText.parse(text, "file:/mcp/Search.graphql"));

		assertThat(refused.getLocation()).isEqualTo(new SourceLocation(2, 14, "file:/mcp/Search.graphql"));
	}

	@Test
	void parse_textWithASourceName_shouldPutTheNameOnEverySourceLocation() {
		Document document = TrustedText.parse("{ search(text: \"a\") { __typename } }", "file:/mcp/Search.graphql");

		OperationDefinition operation = (OperationDefinition) document.getDefinitions().getFirst();
		assertThat(operation.getSourceLocation().getSourceName()).isEqualTo("file:/mcp/Search.graphql");
		assertThat(operation.getSelectionSet().getSelections().getFirst().getSourceLocation().getSourceName())
			.isEqualTo("file:/mcp/Search.graphql");
	}

	@Test
	void parse_textOfMoreTokensAndCharactersThanARequestMayHold_shouldParse() {
		// 20,000 fields are 60,000 tokens, and the comment takes the text past
		// 1,048,576 characters, which are the limits graphql-java puts on a request.
		StringBuilder text = new StringBuilder("# ").append("x".repeat(1_048_576)).append("\n{ movie {");
		for (int number = 1; number <= 20_000; number++) {
			text.append(" a").append(number).append(": id");
		}
		text.append(" } }");

		Document document = TrustedText.parse(text.toString(), null);

		OperationDefinition operation = (OperationDefinition) document.getDefinitions().getFirst();
		Field movie = (Field) operation.getSelectionSet().getSelections().getFirst();
		assertThat(movie.getSelectionSet().getSelections()).hasSize(20_000);
		assertThat(operation.getComments()).hasSize(1);
	}

	@Test
	void options_theLimitsOfARequestOnLength_shouldBeLiftedAndTheOneOnDepthKept() {
		assertThat(TrustedText.OPTIONS.getMaxTokens()).isEqualTo(Integer.MAX_VALUE);
		assertThat(TrustedText.OPTIONS.getMaxCharacters()).isEqualTo(Integer.MAX_VALUE);
		assertThat(TrustedText.OPTIONS.getMaxWhitespaceTokens()).isEqualTo(Integer.MAX_VALUE);
		assertThat(TrustedText.OPTIONS.getMaxRuleDepth()).isEqualTo(500);
		assertThat(TrustedText.OPTIONS.isCaptureLineComments()).isTrue();
	}

	@Test
	void schemaOptions_everyLimit_shouldBeLiftedAsGraphQlJavaLiftsThemForSdl() {
		ParserOptions forSdl = ParserOptions.getDefaultSdlParserOptions();

		assertThat(TrustedText.SCHEMA_OPTIONS.getMaxTokens()).isEqualTo(forSdl.getMaxTokens());
		assertThat(TrustedText.SCHEMA_OPTIONS.getMaxCharacters()).isEqualTo(forSdl.getMaxCharacters());
		assertThat(TrustedText.SCHEMA_OPTIONS.getMaxWhitespaceTokens()).isEqualTo(forSdl.getMaxWhitespaceTokens());
		assertThat(TrustedText.SCHEMA_OPTIONS.getMaxRuleDepth()).isEqualTo(forSdl.getMaxRuleDepth());
		assertThat(TrustedText.SCHEMA_OPTIONS.isCaptureLineComments()).isEqualTo(forSdl.isCaptureLineComments());
	}

	@Test
	void describeLimit_everyLimitGraphQlJavaHas_shouldNameTheSizeOfTheTextAndTheLimit() {
		String text = "{ movie { id title directors { name } } }";

		assertThat(limitOf(text, ParserOptions.newParserOptions().maxTokens(5).build()))
			.isEqualTo("holds 41 characters, and graphql-java's parser stopped reading it at a limit: "
					+ "More than 5 'grammar' tokens have been presented. To prevent Denial Of Service attacks, "
					+ "parsing has been cancelled.");
		assertThat(limitOf(text, ParserOptions.newParserOptions().maxWhitespaceTokens(5).build()))
			.contains("More than 5 'whitespace' tokens have been presented");
		assertThat(limitOf(text, ParserOptions.newParserOptions().maxCharacters(40).build()))
			.contains("More than 40 characters have been presented");
		assertThat(limitOf(text, ParserOptions.newParserOptions().maxRuleDepth(5).build()))
			.contains("More than 5 deep 'grammar' rules have been entered");
	}

	@Test
	void describeLimit_textOfAMillionCharacters_shouldGroupTheDigitsTheWayGraphQlJavaDoes() {
		String text = "# " + "x".repeat(1_048_576) + "\n{ movie }";

		assertThat(limitOf(text, ParserOptions.newParserOptions().build()))
			.startsWith("holds 1,048,588 characters, and")
			.contains("More than 1,048,576 characters have been presented");
	}

	@Test
	void describeLimit_syntaxError_shouldReturnNull() {
		InvalidSyntaxException refused = catchThrowableOfType(InvalidSyntaxException.class,
				() -> TrustedText.parse("{ movie { id ", null));

		assertThat(TrustedText.describeLimit(refused, "{ movie { id ")).isNull();
	}

	private static String limitOf(String text, ParserOptions options) {
		InvalidSyntaxException refused = catchThrowableOfType(InvalidSyntaxException.class,
				() -> TrustedText.parse(text, null, options));

		return String.valueOf(TrustedText.describeLimit(refused, text));
	}

	private static String valueOfTheFirstArgument(Document document) {
		OperationDefinition operation = (OperationDefinition) document.getDefinitions().getFirst();
		Field field = (Field) operation.getSelectionSet().getSelections().getFirst();
		return ((StringValue) field.getArguments().getFirst().getValue()).getValue();
	}

}
