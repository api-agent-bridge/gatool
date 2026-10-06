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

package io.gatool.core.internal.schema;

import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Finds the constructs of a regular expression that Java defines and ECMA 262 leaves out,
 * which is the dialect a client compiles a JSON Schema {@code pattern} as.
 *
 * @author Željko Kozina
 */
final class JavaOnlyRegexConstructs {

	// The two group forms the scan reads past a "(?": a named group, which both engines
	// define, and the inline flags Java alone defines, such as (?i) and (?i-m:.
	private static final Pattern NAMED_GROUP_OPENER = Pattern.compile("<[A-Za-z][A-Za-z0-9]*>");

	// What follows "(?" in a non-capturing group and the four lookarounds.
	private static final List<String> SHARED_GROUP_OPENERS = List.of(":", "=", "!", "<=", "<!");

	private static final Pattern INLINE_FLAGS = Pattern.compile("[A-Za-z]+(-[A-Za-z]*)?|-[A-Za-z]+");

	// What stands between the braces of a quantifier: {n}, {n,} or {n,m}.
	private static final Pattern QUANTIFIER_BOUNDS = Pattern.compile("\\d+(,\\d*)?");

	private JavaOnlyRegexConstructs() {
	}

	/**
	 * Returns the first construct in the pattern that Java's regular expressions define
	 * and ECMA 262 leaves out, named the way the refusal names it, or {@code null} for a
	 * pattern inside the shared subset.
	 * @param regex the pattern as the fragment wrote it
	 * @return the first construct ECMA 262 lacks, named the way the refusal names it, or
	 * {@code null} for a pattern inside the shared subset
	 */
	// JSON Schema says a pattern SHOULD be an ECMA 262 regular expression, and that is
	// what a client compiles it as. Pattern.compile alone accepts (?i), \A, \Q...\E,
	// \p{Alpha} and [a-z&&[^m]], each of which compiles in Java and means something else
	// in ECMA 262. An inline flag, a property class or a possessive quantifier fails to
	// compile there, and an anchor or an intersection becomes a literal, so the published
	// pattern would refuse or accept values outside what the fragment means. The scan
	// reads the pattern the way a compiler tokenises it, escape by escape and class by
	// class, because a regex over the regex reads \p{Alpha}+ as a possessive quantifier.
	// The property class \p{...} is refused although ECMA 262 defines it under the u
	// flag, because the clients compile a pattern without that flag. The escapes \R, \h,
	// \H, \v, \V, \X, \G, \e, \a, \N{...} and \x{...} and a class nested inside a class
	// are refused for the same reason: ECMA 262 reads an escape it lacks as the letter
	// itself, \v as a vertical tab and a bracket inside a class as a literal bracket, so
	// a client would match the letter h where the fragment means horizontal whitespace.
	static @Nullable String find(String regex) {
		Scan scan = new Scan(regex);
		while (scan.hasMore()) {
			String construct = scan.readNext();
			if (construct != null) {
				return construct;
			}
		}
		return null;
	}

	/**
	 * One pass over a pattern, token by token, which stops at the first construct ECMA
	 * 262 lacks.
	 */
	private static final class Scan {

		private final String regex;

		private int at;

		private boolean inClass;

		Scan(String regex) {
			this.regex = regex;
		}

		boolean hasMore() {
			return this.at < this.regex.length();
		}

		// Reads one token and names it where ECMA 262 lacks it, or moves past it and
		// returns null.
		@Nullable String readNext() {
			char current = this.regex.charAt(this.at);
			if (current == '\\') {
				return readEscape();
			}
			if (this.inClass) {
				return readInsideClass(current);
			}
			return readOutsideClass(current);
		}

		private @Nullable String readEscape() {
			if (this.at + 1 == this.regex.length()) {
				// A trailing backslash, which Pattern.compile refuses next, ends the
				// scan.
				this.at = this.regex.length();
				return null;
			}
			String escape = describeEscape(this.regex, this.at + 1);
			if (escape != null) {
				return escape;
			}
			this.at += 2;
			return null;
		}

		private @Nullable String readInsideClass(char current) {
			if (current == '[') {
				return "the nested class [...[...]]";
			}
			if (current == '&' && next() == '&') {
				return "the class intersection &&";
			}
			if (current == ']') {
				this.inClass = false;
			}
			this.at++;
			return null;
		}

		private @Nullable String readOutsideClass(char current) {
			if (current == '[') {
				this.inClass = true;
				this.at++;
				return null;
			}
			if (current == '(' && next() == '?') {
				String group = describeGroup(this.regex, this.at + 2);
				if (group != null) {
					return group;
				}
				this.at += 2;
				return null;
			}
			if (current == '*' || current == '+' || current == '?') {
				if (next() == '+') {
					return "the possessive quantifier " + current + "+";
				}
				this.at++;
				return null;
			}
			if (current == '{') {
				return readBrace();
			}
			this.at++;
			return null;
		}

		// A brace opens a quantifier where digits, or digits and a comma, lead to the
		// closing brace; any other brace is a literal.
		private @Nullable String readBrace() {
			int close = this.regex.indexOf('}', this.at);
			boolean quantifier = close > this.at
					&& QUANTIFIER_BOUNDS.matcher(this.regex.substring(this.at + 1, close)).matches();
			if (quantifier && close + 1 < this.regex.length() && this.regex.charAt(close + 1) == '+') {
				return "the possessive quantifier {n}+";
			}
			this.at = quantifier ? close + 1 : this.at + 1;
			return null;
		}

		// The character after the current one, or 0 at the end of the pattern, which
		// equals none of the characters the scan asks about.
		private char next() {
			return (this.at + 1 < this.regex.length()) ? this.regex.charAt(this.at + 1) : 0;
		}

		// Names the escape whose letter sits at this offset where ECMA 262 lacks it, or
		// returns null for an escape both engines share.
		private static @Nullable String describeEscape(String regex, int letterAt) {
			char escaped = regex.charAt(letterAt);
			switch (escaped) {
				case 'A', 'z', 'Z' -> {
					return "the anchor \\" + escaped;
				}
				case 'Q' -> {
					return "the quoting \\Q...\\E";
				}
				case 'p', 'P' -> {
					return "the property class \\" + escaped + "{...}";
				}
				case 'R' -> {
					return "the linebreak matcher \\R";
				}
				case 'h', 'H' -> {
					return "the horizontal whitespace class \\" + escaped;
				}
				case 'v', 'V' -> {
					return "the vertical whitespace class \\" + escaped;
				}
				case 'X' -> {
					return "the grapheme cluster \\X";
				}
				case 'G' -> {
					return "the previous match anchor \\G";
				}
				case 'e' -> {
					return "the escape character \\e";
				}
				case 'a' -> {
					return "the bell character \\a";
				}
				case 'N' -> {
					// Java takes \N{name} alone, and Pattern.compile refuses a bare \N
					// next.
					return braceFollows(regex, letterAt) ? "the named character \\N{...}" : null;
				}
				case 'x' -> {
					// \x41 with two hex digits is shared, and the braced code point is
					// Java's.
					return braceFollows(regex, letterAt) ? "the code point \\x{...}" : null;
				}
				default -> {
					// Every other escape means the same in both engines.
				}
			}
			return null;
		}

		// Names the group opened by (? at this offset where ECMA 262 lacks it, or returns
		// null for a non-capturing group, a lookaround or a named group, which both
		// engines
		// share.
		//
		// A lookaround and a named group pass here and meet the Anthropic check next,
		// which
		// refuses them for that client alone: the construct itself is ECMA 262.
		private static @Nullable String describeGroup(String regex, int afterOpener) {
			String rest = regex.substring(afterOpener);
			if (opensASharedGroup(rest)) {
				return null;
			}
			if (rest.startsWith(">")) {
				return "the atomic group (?>";
			}
			int end = indexOfEither(rest, ')', ':');
			if (end > 0 && INLINE_FLAGS.matcher(rest.substring(0, end)).matches()) {
				return "the inline flag (?" + rest.substring(0, end) + ")";
			}
			return "the group (?" + (rest.isEmpty() ? "" : rest.substring(0, 1));
		}

		private static int indexOfEither(String text, char first, char second) {
			int firstAt = text.indexOf(first);
			int secondAt = text.indexOf(second);
			if (firstAt < 0) {
				return secondAt;
			}
			return (secondAt < 0) ? firstAt : Math.min(firstAt, secondAt);
		}

		private static boolean braceFollows(String regex, int letterAt) {
			return letterAt + 1 < regex.length() && regex.charAt(letterAt + 1) == '{';
		}

		// A non-capturing group, a lookaround or a named group, which both engines share.
		private static boolean opensASharedGroup(String rest) {
			return SHARED_GROUP_OPENERS.stream().anyMatch(rest::startsWith)
					|| NAMED_GROUP_OPENER.matcher(rest).lookingAt();
		}

	}

}
