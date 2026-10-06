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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import graphql.language.Document;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import org.jspecify.annotations.Nullable;

import io.gatool.core.model.RequestLimits;

/**
 * Prepares and parses the GraphQL text an operator wrote, which is the text of an
 * operation file, of a shared fragment file and of the schema, and the document GATool
 * prints from them.
 *
 * <p>
 * The operation parser and the schema factory both come through here, so a file reads the
 * same way whichever of the two reads it.
 *
 * @author Željko Kozina
 */
public final class TrustedText {

	// The depth of nested rules the parser follows in an operation, which is the depth
	// graphql-java sets for a request. It comes to 165 selection sets, one inside the
	// other.
	private static final int MAX_RULE_DEPTH = 500;

	/**
	 * What the parser captures of an operation file, of a shared fragment file and of the
	 * printed document, and how much of one it reads: all of it, to a depth of
	 * {@value #MAX_RULE_DEPTH} nested rules.
	 */
	// graphql-java keeps a set of options for a request and a set for SDL. The set for a
	// request guards a server against a caller it does not know, and stops reading at
	// 15,000 tokens, at 1,048,576 characters, at 200,000 whitespace tokens and at rules
	// nested 500 deep. The set for SDL lifts all four, because a schema file arrives with
	// the application, and an operation file arrives the same way: the operator wrote it.
	// Read under the limits of a request, a large valid file is refused as a syntax
	// error. The printed document holds the operation and every shared fragment it
	// spreads, each field indented on a line of its own, so it can pass a limit that each
	// of its files stays below.
	// The three limits on length are lifted here, and the one on depth stays. A parser
	// and a printer both follow nesting by calling themselves, so depth costs stack where
	// length costs heap. With the depth lifted, a file nested 2,000 deep parses and then
	// ends startup with a StackOverflowError inside graphql-java's printer, without a
	// line that names the file. With the limit in place the file is refused by name.
	// The values are written out here. The defaults of graphql-java sit in statics that
	// any code in the JVM may replace, and what GATool accepts at startup would then
	// depend on what ran ahead of it.
	// Comments are captured, because the description of a tool can come from the #
	// lines above its operation.
	public static final ParserOptions OPTIONS = ParserOptions.newParserOptions()
		.captureIgnoredChars(false)
		.captureSourceLocation(true)
		.captureLineComments(true)
		.readerTrackData(true)
		.maxCharacters(Integer.MAX_VALUE)
		.maxTokens(Integer.MAX_VALUE)
		.maxWhitespaceTokens(Integer.MAX_VALUE)
		.maxRuleDepth(MAX_RULE_DEPTH)
		.build();

	/**
	 * What the parser captures of the schema, and how much of it the parser reads, which
	 * is all of it.
	 */
	// The four values graphql-java's own options for SDL hold in 25.0, so the schema file
	// is read as graphql-java's own options read it. A schema nests in the wrappers of a
	// type and in a default value, and GATool does not print it, so the depth stays
	// lifted here.
	public static final ParserOptions SCHEMA_OPTIONS = ParserOptions.newParserOptions()
		.captureIgnoredChars(false)
		.captureSourceLocation(true)
		.captureLineComments(true)
		.readerTrackData(true)
		.maxCharacters(Integer.MAX_VALUE)
		.maxTokens(Integer.MAX_VALUE)
		.maxWhitespaceTokens(Integer.MAX_VALUE)
		.maxRuleDepth(Integer.MAX_VALUE)
		.build();

	private TrustedText() {
	}

	/**
	 * Returns the text with a line feed for every line terminator.
	 *
	 * <p>
	 * GraphQL reads a carriage return followed by a line feed, a line feed, and a lone
	 * carriage return each as one line terminator. Each one becomes one line feed here,
	 * so a line keeps its number and a token keeps its column.
	 * @param text the text as the file holds it
	 * @return the text with {@code \n} where the file wrote {@code \r\n} or a lone
	 * {@code \r}
	 */
	// graphql-java 25.0 to 26.1 split a block string on \n alone, in
	// StringValueParsing.removeIndentation, where the specification splits it on every
	// line terminator and joins the lines with \n. A file checked out with \r\n keeps a
	// carriage return on every line of a block string, keeps the blank first and last
	// lines, and keeps the indentation of every line where the value holds a blank line.
	// A default would reach the API and the input schema as "\r\nblock string\r\n...\r",
	// and a schema description would reach a tool as "\r\nThe title.\r", so a Windows
	// checkout and a Linux checkout of one repository would publish different tool
	// contracts.
	// The text is rewritten ahead of the parser, and the alternative is to repair each
	// value behind it. That means walking every string value of a document and every
	// description of a schema, and it leaves the lexer counting a file written with lone
	// carriage returns as one line, so a problem in such a file names line 1.
	// The lexer refuses a raw carriage return inside a single-line string and ends a
	// comment at one, so the values of block strings are the only ones this changes.
	public static String withLineFeeds(String text) {
		if (text.indexOf('\r') < 0) {
			return text;
		}
		return text.replace("\r\n", "\n").replace('\r', '\n');
	}

	/**
	 * Parses the text of an operation or of fragments into a document, however long the
	 * text is.
	 * @param text the text, with a line feed for every line terminator
	 * @param sourceName the name every source location of the document carries, which is
	 * the location of the file, or {@code null} for a text without a file
	 * @return the parsed document
	 * @throws InvalidSyntaxException if the text is not a GraphQL document
	 */
	public static Document parse(String text, @Nullable String sourceName) {
		return parse(text, sourceName, OPTIONS);
	}

	/**
	 * Parses the text into a document under the given options, which are {@link #OPTIONS}
	 * everywhere outside a test.
	 * @param text the text, with a line feed for every line terminator
	 * @param sourceName the name every source location of the document carries, which is
	 * the location of the file, or {@code null} for a text without a file
	 * @param options what the parser captures and how much text it reads
	 * @return the parsed document
	 * @throws InvalidSyntaxException if the text is not a GraphQL document, or if the
	 * parser stopped reading at a limit of the options
	 */
	// graphql-java reads a source name from a MultiSourceReader alone, and Parser uses
	// the reader it is handed when it is one of those, so the reader is built here. It
	// tracks the text it has read, which is where the excerpt of a syntax error comes
	// from, and which is what Parser does for a text it wraps itself. The text reaches it
	// through a WholeCodePointReader, which keeps a surrogate pair in one read. The
	// syntax messages are translated, and the default locale would give a German laptop
	// German text where CI prints English, so the locale is pinned.
	public static Document parse(String text, @Nullable String sourceName, ParserOptions options) {
		MultiSourceReader reader = MultiSourceReader.newMultiSourceReader()
			.reader(new WholeCodePointReader(text), sourceName)
			.trackData(true)
			.build();
		return Parser.parse(ParserEnvironment.newParserEnvironment()
			.document(reader)
			.parserOptions(options)
			.locale(Locale.ROOT)
			.build());
	}

	/**
	 * Returns the limits of a request that a document passes.
	 * @param document the document a tool sends, which parsed under {@link #OPTIONS}
	 * @param limits the limits the application expects of its API
	 * @return one entry for each limit the document passes, in the order characters,
	 * tokens and whitespace tokens, and an empty list for a document within all three
	 */
	// GATool reads a file of any length, and the API that receives the document applies
	// limits of its own, which the GraphQL specification leaves out of introspection, so
	// GATool cannot ask for them and the application states what it expects. The
	// defaults are the ones of graphql-java, because this code can apply them exactly,
	// by reading the document under each of them. Apollo Router stops at 15,000 tokens
	// by default as well, and counts the ignored tokens among them.
	// The tokens are counted by the parser, one limit at a time with the others lifted,
	// so the answer says which limit the document passed. A document shorter than a
	// limit holds fewer tokens than it, and the parse is skipped for it, which is every
	// document of ordinary size. The nesting of a request is the nesting OPTIONS
	// allows, so a document that parsed is within it. A limit below 1 is switched off.
	public static List<PassedLimit> findRequestLimitsPassed(String document, RequestLimits limits) {
		List<PassedLimit> passed = new ArrayList<>();
		if (limits.maxCharacters() > 0 && document.length() > limits.maxCharacters()) {
			passed.add(new PassedLimit(String.format(Locale.ROOT, "more than %,d characters", limits.maxCharacters()),
					RequestLimits.PROPERTY + ".max-characters"));
		}
		if (limits.maxTokens() > 0 && document.length() > limits.maxTokens()
				&& stopsAtALimit(document, lifted().maxTokens(limits.maxTokens()).build())) {
			passed.add(new PassedLimit(String.format(Locale.ROOT, "more than %,d tokens", limits.maxTokens()),
					RequestLimits.PROPERTY + ".max-tokens"));
		}
		if (limits.maxWhitespaceTokens() > 0 && document.length() > limits.maxWhitespaceTokens()
				&& stopsAtALimit(document, lifted().maxWhitespaceTokens(limits.maxWhitespaceTokens()).build())) {
			passed.add(new PassedLimit(
					String.format(Locale.ROOT, "more than %,d whitespace tokens", limits.maxWhitespaceTokens()),
					RequestLimits.PROPERTY + ".max-whitespace-tokens"));
		}
		return passed;
	}

	private static ParserOptions.Builder lifted() {
		return ParserOptions.newParserOptions()
			.captureIgnoredChars(false)
			.captureSourceLocation(false)
			.captureLineComments(false)
			.maxCharacters(Integer.MAX_VALUE)
			.maxTokens(Integer.MAX_VALUE)
			.maxWhitespaceTokens(Integer.MAX_VALUE)
			.maxRuleDepth(MAX_RULE_DEPTH);
	}

	private static boolean stopsAtALimit(String document, ParserOptions options) {
		try {
			parse(document, null, options);
			return false;
		}
		catch (InvalidSyntaxException ex) {
			return describeLimit(ex, document) != null;
		}
	}

	/**
	 * Returns what to tell the operator about a text the parser stopped reading at a
	 * limit, or {@code null} where the parser read the text and refused its syntax.
	 *
	 * <p>
	 * The sentence follows the name of the text, as in
	 * {@code Wide.graphql holds 89,987 characters, and ...}, and it names the limit in
	 * graphql-java's own words.
	 * @param ex what the parser raised
	 * @param text the text the parser was reading
	 * @return the size of the text and the limit that stopped the parser, or {@code null}
	 * for a syntax error
	 */
	// graphql-java raises a limit as an InvalidSyntaxException, so a caller that writes
	// "invalid syntax" ahead of the message sends the operator looking for a mistake in a
	// file whose syntax the parser has not judged.
	// The options here lift the three limits on length that 25.0 has, so this is for a
	// file nested deeper than the parser follows, and for a limit a later graphql-java
	// adds, as 26 did with the 100 characters of a numeric literal.
	// The limits are ParseCancelledException, ParseCancelledTooDeepException and
	// ParseCancelledTooManyCharsException in 25.0. InvalidSyntaxException is the one type
	// they share, and their name starts the same way, so the name is what is read. A
	// limit class this code was compiled without is recognised that way as well.
	// The size is given in characters, which GATool counts without reading the text
	// again. A count of tokens takes graphql-java's lexer, which is internal to it.
	public static @Nullable String describeLimit(InvalidSyntaxException ex, String text) {
		if (!ex.getClass().getSimpleName().startsWith("ParseCancelled")) {
			return null;
		}
		return "holds " + String.format(Locale.ROOT, "%,d", text.length()) + " characters, and graphql-java's "
				+ "parser stopped reading it at a limit: " + ex.getMessage();
	}

	/**
	 * One limit a document passes.
	 *
	 * @param size what the document holds, such as {@code more than 15,000 tokens}
	 * @param property the configuration property that sets the limit
	 */
	public record PassedLimit(String size, String property) {
	}

}
