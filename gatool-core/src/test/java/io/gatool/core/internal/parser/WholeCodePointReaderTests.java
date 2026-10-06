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

import java.io.IOException;
import java.io.Reader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class WholeCodePointReaderTests {

	// U+1F600, which a Java string holds as the surrogate pair D83D DE00.
	private static final String EMOJI = "😀";

	@Test
	void read_lengthEndingBetweenTheHalvesOfAPair_shouldStopAheadOfThePair() throws IOException {
		Reader reader = new WholeCodePointReader("ab" + EMOJI + "cd");
		char[] buffer = new char[8];

		assertThat(reader.read(buffer, 0, 3)).isEqualTo(2);
		assertThat(new String(buffer, 0, 2)).isEqualTo("ab");
		assertThat(reader.read(buffer, 0, 3)).isEqualTo(3);
		assertThat(new String(buffer, 0, 3)).isEqualTo(EMOJI + "c");
	}

	@Test
	void read_lengthEndingBehindAPair_shouldReturnThePairWhole() throws IOException {
		Reader reader = new WholeCodePointReader("ab" + EMOJI + "cd");
		char[] buffer = new char[8];

		assertThat(reader.read(buffer, 0, 4)).isEqualTo(4);
		assertThat(new String(buffer, 0, 4)).isEqualTo("ab" + EMOJI);
	}

	@Test
	void read_moreCharsThanAntlrAsksFor_shouldReturnTheNumberAntlrAsksFor() throws IOException {
		// The BufferedReader inside MultiSourceReader asks for 8,192 chars and hands
		// ANTLR 4,096 of them, so a longer piece would be cut where ANTLR's read ends.
		Reader reader = new WholeCodePointReader("a".repeat(10_000));
		char[] buffer = new char[8192];

		assertThat(reader.read(buffer, 0, 8192)).isEqualTo(4096);
		assertThat(reader.read(buffer, 0, 8192)).isEqualTo(4096);
		assertThat(reader.read(buffer, 0, 8192)).isEqualTo(10_000 - 8192);
		assertThat(reader.read(buffer, 0, 8192)).isEqualTo(-1);
	}

	@Test
	void read_pairOnTheLastCharAntlrAsksFor_shouldReturnOneCharFewerAndThenThePair() throws IOException {
		String text = "a".repeat(4095) + EMOJI + "b";
		Reader reader = new WholeCodePointReader(text);
		char[] buffer = new char[8192];

		assertThat(reader.read(buffer, 0, 8192)).isEqualTo(4095);
		assertThat(reader.read(buffer, 0, 8192)).isEqualTo(3);
		assertThat(new String(buffer, 0, 3)).isEqualTo(EMOJI + "b");
	}

	@Test
	void read_lengthOfOneAtAPair_shouldReturnTheHighSurrogate() throws IOException {
		// Reader has a read of one char or more return at least one, so the pair is
		// split for the caller that asks for a single char, and read() is that caller.
		Reader reader = new WholeCodePointReader(EMOJI + "a");

		assertThat(reader.read()).isEqualTo(0xD83D);
		assertThat(reader.read()).isEqualTo(0xDE00);
		assertThat(reader.read()).isEqualTo('a');
		assertThat(reader.read()).isEqualTo(-1);
	}

	@Test
	void read_highSurrogateEndingTheText_shouldReturnIt() throws IOException {
		// A text that ends on half a pair is malformed, and it reaches the parser whole,
		// so the lexer is the one to refuse it.
		Reader reader = new WholeCodePointReader("ab\uD83D");
		char[] buffer = new char[8];

		assertThat(reader.read(buffer, 0, 8)).isEqualTo(3);
		assertThat(reader.read(buffer, 0, 8)).isEqualTo(-1);
	}

	@Test
	void read_lengthOfZero_shouldReturnZeroEvenAtTheEnd() throws IOException {
		Reader reader = new WholeCodePointReader("");

		assertThat(reader.read(new char[4], 0, 0)).isZero();
		assertThat(reader.read(new char[4], 0, 4)).isEqualTo(-1);
	}

	@Test
	void read_intoTheMiddleOfABuffer_shouldWriteFromTheOffset() throws IOException {
		Reader reader = new WholeCodePointReader("abc");
		char[] buffer = { '.', '.', '.', '.', '.' };

		assertThat(reader.read(buffer, 1, 3)).isEqualTo(3);
		assertThat(new String(buffer)).isEqualTo(".abc.");
	}

	@Test
	void read_rangeOutsideTheBuffer_shouldThrow() {
		Reader reader = new WholeCodePointReader("abc");

		assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> reader.read(new char[2], 1, 2));
	}

	@Test
	void ready_withTextLeft_shouldAnswerFalse() throws IOException {
		// True lets the BufferedReader inside MultiSourceReader join a second read to a
		// short first one, and the joined text is cut at 4,096 chars again.
		try (Reader reader = new WholeCodePointReader("abc")) {
			assertThat(reader.ready()).isFalse();
		}
	}

}
