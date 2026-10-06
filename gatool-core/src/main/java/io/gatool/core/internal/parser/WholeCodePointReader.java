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

import java.io.Reader;
import java.util.Objects;

/**
 * Reads a text in pieces that each end on a whole code point, so the two halves of a
 * surrogate pair always arrive in one read.
 *
 * <p>
 * The reader works around a fault in ANTLR 4.13.2, which graphql-java 25.0 to 26.1 carry,
 * and it goes away once GATool builds on a graphql-java that holds the fix of
 * antlr/antlr4#4943 or of graphql-java/graphql-java#4478.
 *
 * @author Željko Kozina
 */
// ANTLR fills its buffer 4,096 chars at a time, in CharStreams.fromReader. Where a read
// ends on the high surrogate of a pair, CodePointBuffer writes that surrogate as a code
// point of its own and joins it with the low surrogate of the next read as well, so the
// lexer meets a lone surrogate. GraphQL's SourceCharacter leaves surrogates out, and the
// text is refused with "token recognition error". That makes a syntax error of a valid
// file whose emoji sits on char 4,095, or any multiple of 4,096 chars past it.
// Every parse method of graphql-java reads through fromReader, so choosing another one
// does not help, and the text a reader hands over is the one thing GATool decides.
// Two readers sit between this one and ANTLR. graphql-java's SafeTokenReader passes a
// read through as it is. MultiSourceReader wraps each source in a LineNumberReader,
// which is a BufferedReader: it asks this reader for up to 8,192 chars, hands ANTLR
// what it received, and asks again within one read of ANTLR's only while ready()
// answers true. So a read here returns 4,096 chars at most, which ANTLR takes in one
// read of its own, and ready() answers false, which keeps two reads of this reader
// from being joined and cut again at 4,096.
final class WholeCodePointReader extends Reader {

	// The number of chars ANTLR asks for in one read. A larger piece would be cut by
	// the BufferedReader at this length, wherever a surrogate pair lies.
	static final int MAX_CHARS_PER_READ = 4096;

	private final String text;

	private int position;

	WholeCodePointReader(String text) {
		this.text = text;
	}

	/**
	 * Reads up to {@code length} chars, and one char fewer where the last of them would
	 * be a high surrogate with more text behind it.
	 */
	// A read of length 1 that starts at a pair returns the high surrogate alone, because
	// the contract of Reader has a read of one char or more return at least one. ANTLR
	// asks for 4,096 chars and the BufferedReader for 8,192, so that read comes from a
	// caller other than the parser.
	@Override
	public int read(char[] buffer, int offset, int length) {
		Objects.checkFromIndexSize(offset, length, buffer.length);
		if (length == 0) {
			return 0;
		}
		int remaining = this.text.length() - this.position;
		if (remaining == 0) {
			return -1;
		}
		int count = Math.min(Math.min(length, MAX_CHARS_PER_READ), remaining);
		if (count > 1 && count < remaining && Character.isHighSurrogate(this.text.charAt(this.position + count - 1))) {
			count--;
		}
		this.text.getChars(this.position, this.position + count, buffer, offset);
		this.position += count;
		return count;
	}

	/**
	 * Returns false, which Reader allows at any time and which tells a BufferedReader to
	 * hand on what one read gave it.
	 */
	@Override
	public boolean ready() {
		return false;
	}

	/**
	 * Leaves the reader as it is, because a text in memory does not hold anything to
	 * release.
	 */
	@Override
	public void close() {
		// The text lives in memory and the garbage collector alone releases it.
	}

}
