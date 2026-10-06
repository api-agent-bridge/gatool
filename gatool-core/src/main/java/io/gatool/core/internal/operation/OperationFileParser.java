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

package io.gatool.core.internal.operation;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Set;

import graphql.language.Document;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.language.SourceLocation;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.ParserOptions;
import org.jspecify.annotations.Nullable;

import io.gatool.core.internal.parser.TrustedText;

/**
 * Parses an operation file and checks that it holds one query or mutation, or only
 * fragments.
 *
 * <p>
 * An operation without a name is named after its file, because every layer below reads a
 * non-null operation name and the tool name comes from it. GraphQL allows a lone
 * anonymous operation, so refusing the file would demand a name GATool can write itself.
 *
 * @author Željko Kozina
 */
public final class OperationFileParser {

	// The definitions the September 2025 edition lets a string describe.
	private static final Set<String> DEFINITION_KEYWORDS = Set.of("query", "mutation", "subscription", "fragment");

	// The byte order mark an editor on Windows writes at the start of a UTF-8 file.
	private static final char BYTE_ORDER_MARK = '\uFEFF';

	private final ParserOptions options;

	/**
	 * Creates a parser that reads a file however long it is, as
	 * {@link TrustedText#OPTIONS} says.
	 */
	public OperationFileParser() {
		this(TrustedText.OPTIONS);
	}

	/**
	 * Creates a parser under options of the caller's own, which is how a test reaches a
	 * limit with a short file.
	 * @param options the parser options every file is read under
	 */
	OperationFileParser(ParserOptions options) {
		this.options = options;
	}

	/**
	 * Parses one operation file.
	 * @param source the file to parse
	 * @param diagnostics receives every problem the file has
	 * @return the parsed file, or {@code null} when the file has a problem
	 */
	public @Nullable ParsedFile parse(OperationSource source, OperationDiagnostics diagnostics) {
		source = withLineFeeds(withoutByteOrderMark(source));
		Document document;
		try {
			// The location goes on every source location the nodes carry. A shared
			// fragment keeps the nodes its own file parsed when it joins an operation's
			// document, so a validation error inside it carries that file's line
			// numbers, and the name on the node is what lets OperationValidator say
			// which file a line number belongs to.
			document = TrustedText.parse(source.content(), source.location(), this.options);
		}
		catch (InvalidSyntaxException ex) {
			diagnostics.problem(source.location(), describeSyntaxProblem(source.content(), ex));
			return null;
		}
		List<OperationDefinition> operations = document.getDefinitionsOfType(OperationDefinition.class);
		List<FragmentDefinition> fragments = document.getDefinitionsOfType(FragmentDefinition.class);
		if (operations.size() + fragments.size() < document.getDefinitions().size()) {
			diagnostics.problem(source.location(),
					"holds a type system definition, and operation files hold only an operation and fragments");
			return null;
		}
		int problemsBefore = diagnostics.problemCount();
		// A shared fragment joins the document of every operation that spreads it, so a
		// directive the parser refuses in an operation file is refused here as well, and
		// the problem names the fragment file, which is the file to change. graphql-java
		// declares @defer in every schema it builds, so validation of the assembled
		// document lets the directive through.
		for (String directive : IncrementalDelivery.directivesIn(document)) {
			diagnostics.problem(source.location(), describeIncrementalDelivery(directive));
		}
		ToolDirective.reportMisplaced(document, source.location(), diagnostics);
		if (operations.isEmpty()) {
			return (diagnostics.problemCount() == problemsBefore) ? new FragmentFile(source, document) : null;
		}
		if (operations.size() > 1) {
			diagnostics.problem(source.location(), "holds " + operations.size()
					+ " operations, and an operation file holds exactly one, because one file makes one tool");
		}
		OperationDefinition operation = operations.getFirst();
		if (operation.getOperation() == OperationDefinition.Operation.SUBSCRIPTION) {
			diagnostics.problem(source.location(),
					"holds a subscription; a tool call returns a single result, so only a query or a mutation becomes a tool");
		}
		if (diagnostics.problemCount() != problemsBefore) {
			return null;
		}
		// The settings are read while the directive is still there, and the document
		// leaves here without it, so validation and the executor both see the document a
		// GraphQL API accepts. graphql-java answers @gatool with UnknownDirective.
		ToolDirective directive = ToolDirective.read(operation, source.location(), diagnostics);
		if (diagnostics.problemCount() != problemsBefore) {
			return null;
		}
		return toOperationFile(source, document, operation, directive, diagnostics);
	}

	// Returns the operation file, with the operation named after its file where the
	// operation is anonymous, or null where the file name cannot yield a name.
	private static @Nullable OperationFile toOperationFile(OperationSource source, Document document,
			OperationDefinition operation, ToolDirective directive, OperationDiagnostics diagnostics) {
		// An anonymous operation is named after its file, because the tool name comes
		// from the operation name and every layer below here reads a non-null one.
		// GraphQL allows a lone anonymous operation, so refusing the file would demand a
		// name GATool can write itself, and naming it also gives the API's own telemetry
		// something to group the call under.
		if (operation.getName() == null) {
			String nameFromFile = nameFrom(source.location());
			if (nameFromFile == null) {
				diagnostics.problem(source.location(),
						"holds an anonymous operation, and its file name does not "
								+ "yield a GraphQL name to take: a GraphQL name is ASCII, matching "
								+ "/[_A-Za-z][_0-9A-Za-z]*/, and it cannot start with a digit. Name the operation, or "
								+ "rename the file.");
				return null;
			}
			String operationName = nameFromFile;
			OperationDefinition namedOperation = operation.transform((builder) -> builder.name(operationName));
			document = ToolDirective.replace(document, operation, namedOperation);
			operation = namedOperation;
		}
		return new OperationFile(source, ToolDirective.withoutDirective(document, operation),
				ToolDirective.withoutDirective(operation), directive);
	}

	// Returns the problem for a file graphql-java cannot parse: the parser's own
	// sentence, with what GATool knows about the two shapes the parser describes badly.
	//
	// An empty file, or one holding comments alone, fails on '<EOF>', a message that
	// sends a reader to look for a syntax error in an empty file. A string ahead of a
	// definition keyword is the description the September 2025 edition of GraphQL added
	// to operations and fragments. graphql-java 25.0 parses the edition before it and
	// blames the string, so the sentence says which syntax carries the description GATool
	// reads. Once graphql-java parses the edition, the description belongs in the AST and
	// this sentence goes.
	private static String describeSyntaxProblem(String content, InvalidSyntaxException ex) {
		// A limit arrives as the same exception a syntax error does, and the parser
		// stopped ahead of the end of the file, so the syntax goes unmentioned.
		String limit = TrustedText.describeLimit(ex, content);
		if (limit != null) {
			return limit;
		}
		if (holdsCommentsAndWhitespaceAlone(content)) {
			return "is empty, holding comments and whitespace at most, and an operation file holds one query or "
					+ "mutation, or fragments alone; write the operation into it, or delete the file";
		}
		String problem = "has invalid GraphQL syntax: " + ex.getMessage();
		if (isDescriptionAheadOfADefinition(content, ex)) {
			return problem + ". graphql-java 25.0 predates the September 2025 syntax for describing an operation "
					+ "or a fragment with a string ahead of it, and a # comment line directly above the operation "
					+ "carries the description GATool reads";
		}
		return problem;
	}

	private static boolean holdsCommentsAndWhitespaceAlone(String content) {
		return content.lines().map(String::strip).allMatch((line) -> line.isEmpty() || line.startsWith("#"));
	}

	// Returns whether the token the parser refused is a string literal followed, past
	// whitespace and comments, by a definition keyword: the place the September 2025
	// edition puts a description.
	//
	// The token's text is found at the parser's line and column, or, where the two
	// count characters differently, by a search from the start of that line.
	private static boolean isDescriptionAheadOfADefinition(String content, InvalidSyntaxException ex) {
		String token = ex.getOffendingToken();
		SourceLocation location = ex.getLocation();
		if (token == null || !token.startsWith("\"") || location == null) {
			return false;
		}
		int lineStart = startOfLine(content, location.getLine());
		int offset = lineStart + location.getColumn() - 1;
		if (offset < 0 || offset > content.length() || !content.startsWith(token, offset)) {
			offset = content.indexOf(token, Math.max(lineStart, 0));
		}
		if (offset < 0) {
			return false;
		}
		String rest = withoutIgnoredTokens(content.substring(offset + token.length()));
		return DEFINITION_KEYWORDS.stream()
			.anyMatch((keyword) -> rest.startsWith(keyword)
					&& (rest.length() == keyword.length() || !isNameCharacter(rest.charAt(keyword.length()))));
	}

	// The parser counts lines from one and ends each at a line feed.
	private static int startOfLine(String content, int line) {
		int start = 0;
		for (int current = 1; current < line; current++) {
			int lineFeed = content.indexOf('\n', start);
			if (lineFeed < 0) {
				return -1;
			}
			start = lineFeed + 1;
		}
		return start;
	}

	// GraphQL ignores whitespace, line terminators, commas and # comments between tokens.
	private static String withoutIgnoredTokens(String text) {
		int index = 0;
		while (index < text.length()) {
			char character = text.charAt(index);
			if (character == '#') {
				while (index < text.length() && text.charAt(index) != '\n' && text.charAt(index) != '\r') {
					index++;
				}
			}
			else if (Character.isWhitespace(character) || character == ',') {
				index++;
			}
			else {
				break;
			}
		}
		return text.substring(index);
	}

	private static boolean isNameCharacter(char character) {
		return isAsciiLetterOrDigit(character) || character == '_';
	}

	// Returns the problem an operation file or a fragment file gets for one incremental
	// delivery directive, which is the same sentence in both places because the fragment
	// ends up inside an operation.
	//
	// Incremental delivery breaks the same rule a subscription does, and
	// IncrementalDelivery says what each API does with such an operation.
	private static String describeIncrementalDelivery(String directive) {
		return "uses @" + directive
				+ "; a tool call returns a single result, so an operation that asks for its answer in pieces "
				+ "cannot become a tool";
	}

	// Returns the source with the byte order mark taken off the front of its content.
	//
	// The lexer reads U+FEFF as an ignored token, so the file parses, and String.strip()
	// keeps it, because Character.isWhitespace counts it as text. DescriptionResolver
	// compares the stripped first line of the file with the comment the lexer captured,
	// and the mark would make the two differ and drop the first line of the description.
	// The mark is an encoding artefact, and the whole pipeline reads the content without
	// it.
	private static OperationSource withoutByteOrderMark(OperationSource source) {
		String content = source.content();
		if (content.isEmpty() || content.charAt(0) != BYTE_ORDER_MARK) {
			return source;
		}
		return new OperationSource(source.location(), content.substring(1), source.toolExposureTypes());
	}

	// Returns the source with a line feed for every line terminator of its content.
	//
	// The parser, the excerpt a syntax problem is described from and DescriptionResolver
	// all read the content of the source, so the content is rewritten once, ahead of
	// them, and the three agree about where a line starts.
	private static OperationSource withLineFeeds(OperationSource source) {
		String content = TrustedText.withLineFeeds(source.content());
		if (content.equals(source.content())) {
			return source;
		}
		return new OperationSource(source.location(), content, source.toolExposureTypes());
	}

	private static String decode(String file) {
		try {
			return URLDecoder.decode(file, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			// A name holding a stray % is not an escape, and it reads as written.
			return file;
		}
	}

	// GraphQL's Name grammar is ASCII, /[_A-Za-z][_0-9A-Za-z]*/, and Character.isLetter
	// accepts every Unicode letter. A file named uber-filme with an umlaut would produce
	// the operation name UberFilme with one, which builds a document graphql-java's own
	// parser refuses: "token recognition error at: 'U'". Startup validates the AST it
	// holds instead of a re-parse of the text it prints, so that document would reach the
	// API.
	private static boolean isAsciiLetter(char character) {
		return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
	}

	private static boolean isAsciiLetterOrDigit(char character) {
		return isAsciiLetter(character) || (character >= '0' && character <= '9');
	}

	// Returns the file name, read as a GraphQL operation name, or null where the file
	// name cannot yield one.
	//
	// Letters and digits make the name, in the case the file spells them, and every other
	// character is a word break written as one underscore, so top-rated-movies.graphql
	// gives top_rated_movies, movies (copy).graphql gives movies_copy, and
	// TopRatedMovies.graphql stays TopRatedMovies. The camelCase and snake_case
	// strategies read an underscore as a word break, so the tool name is the one the
	// written operation TopRatedMovies gets. Capitalising each word instead would give
	// the as-written strategy a tool named TopRatedMovies for a file that spells
	// top-rated-movies, a name the file itself does not carry. An underscore is a legal
	// GraphQL name character and still reads as a word break here, because a file name is
	// being read as words.
	private static @Nullable String nameFrom(String location) {
		// A resource URL percent-escapes what a path cannot carry, so a file named with a
		// space arrives as movies%20copy. The name is then composed (NFC). macOS can hand
		// a file name over decomposed (NFD), where the umlaut of über-filme is u followed
		// by the combining mark U+0308, which the loop below would read as the letter u
		// and a word break, while Linux hands over the one letter U+00FC. Composed, both
		// are the letter GraphQL cannot spell. The escapes are decoded first, because a
		// URL carries the mark as %CC%88.
		String file = Normalizer.normalize(decode(location.substring(location.lastIndexOf('/') + 1)),
				Normalizer.Form.NFC);
		// The last dot: reading to the first would turn movies.v2.graphql into Movies, so
		// movies.v1 and movies.v2 would take one name and clash.
		int dot = file.lastIndexOf('.');
		String stem = (dot > 0) ? file.substring(0, dot) : file;
		StringBuilder name = new StringBuilder();
		boolean atBoundary = false;
		for (int index = 0; index < stem.length(); index++) {
			char character = stem.charAt(index);
			if (isAsciiLetterOrDigit(character)) {
				if (atBoundary && !name.isEmpty()) {
					name.append('_');
				}
				name.append(character);
				atBoundary = false;
			}
			else if (Character.isLetterOrDigit(character)) {
				// A letter GraphQL cannot spell. Dropping it silently would turn
				// über-filme.graphql into ber_filme, which reads as another file's name,
				// so the file is refused and the operation name has to be written in the
				// file.
				return null;
			}
			else {
				atBoundary = true;
			}
		}
		// A GraphQL name matches /[_A-Za-z][_0-9A-Za-z]*/, so a name starting with a
		// digit is refused, and so is the empty name a file name without a letter or a
		// digit gives.
		if (name.isEmpty() || !isAsciiLetter(name.charAt(0))) {
			return null;
		}
		return name.toString();
	}

	/**
	 * Parses a document without a file name, which is a document GATool printed.
	 * @param documentText the text of the document
	 * @return the parsed document
	 */
	static Document parseDocument(String documentText) {
		return TrustedText.parse(documentText, null);
	}

}
