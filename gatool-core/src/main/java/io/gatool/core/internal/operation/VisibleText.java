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

/**
 * Tells text a reader can see from text that renders empty.
 *
 * <p>
 * {@code String.isBlank()} knows Java's whitespace, which leaves out the no-break spaces,
 * the zero width space and the fillers. A title made of those is a string with content to
 * Java and an empty label to the person reading the tool list, so the texts a client
 * shows are checked here, one code point at a time.
 *
 * @author Željko Kozina
 */
final class VisibleText {

	// The code points that render empty whatever their class says, as the first and the
	// last code point of each range. Unicode classes the Hangul filler U+3164 as a
	// letter, the variation selectors as marks and the blank Braille pattern U+2800 as a
	// symbol, so Character.getType cannot tell them from the letters, marks and symbols a
	// reader sees. A title of U+3164 passes a rule that reads the class alone.
	//
	// The list is the Unicode property Default_Ignorable_Code_Point, copied from
	// DerivedCoreProperties.txt of Unicode 16.0 with adjoining ranges joined, and two
	// characters from outside the property. The property holds what a renderer leaves
	// without a glyph unless it supports the character in some other way: the Hangul
	// fillers, the variation selectors, the combining grapheme joiner, and the code
	// points Unicode reserves for more characters of that kind. Its format characters
	// are listed although FORMAT covers them, so the list compares with the Unicode
	// file range by range. The two from outside are symbols whose glyph is blank by
	// definition.
	//
	// The ranges are written out because Java does not offer the property. Character
	// lacks a method for it, and java.util.regex refuses
	// \p{IsDefault_Ignorable_Code_Point} as an unknown property name on Java 21, 25 and
	// 26, while it reads \p{IsWhite_Space} on all three. ICU4J reads the property, and
	// this check alone would be the reason for that dependency. A list also answers the
	// same way on every Java version, where Character.getType follows the Unicode
	// version of the JDK that runs it.
	//
	// A character that renders empty and is missing from the list passes as visible.
	private static final int[][] RENDERED_EMPTY = { { 0x00AD, 0x00AD }, // soft hyphen
			{ 0x034F, 0x034F }, // combining grapheme joiner
			{ 0x061C, 0x061C }, // Arabic letter mark
			{ 0x115F, 0x1160 }, // Hangul choseong filler and jungseong filler
			{ 0x17B4, 0x17B5 }, // Khmer inherent vowels
			{ 0x180B, 0x180F }, // Mongolian free variation selectors and vowel separator
			{ 0x200B, 0x200F }, // zero width space, the joiners and the directional marks
			{ 0x202A, 0x202E }, // directional embeddings and overrides
			{ 0x2060, 0x206F }, // word joiner, invisible operators and directional
								// isolates
			{ 0x3164, 0x3164 }, // Hangul filler
			{ 0xFE00, 0xFE0F }, // variation selectors 1 to 16
			{ 0xFEFF, 0xFEFF }, // byte order mark
			{ 0xFFA0, 0xFFA0 }, // halfwidth Hangul filler
			{ 0xFFF0, 0xFFF8 }, // reserved
			{ 0x1BCA0, 0x1BCA3 }, // shorthand format controls
			{ 0x1D173, 0x1D17A }, // musical format controls
			{ 0xE0000, 0xE0FFF }, // tags, variation selectors 17 to 256, and reserved
			// The two from outside the property.
			{ 0x2800, 0x2800 }, // Braille pattern blank, the cell with every dot lowered
			{ 0x1D159, 0x1D159 }, // musical symbol null notehead
	};

	private VisibleText() {
	}

	/**
	 * Returns whether the text renders empty, which holds for the empty string and for a
	 * string made of characters a reader cannot see.
	 * @param text the text as written
	 * @return false where the text holds at least one visible character
	 */
	static boolean rendersEmpty(String text) {
		return text.codePoints().noneMatch(VisibleText::isVisible);
	}

	// Returns whether a reader can see the character.
	//
	// Five of the classes Character.getType knows count as invisible. SPACE_SEPARATOR,
	// LINE_SEPARATOR and PARAGRAPH_SEPARATOR hold every space Unicode has, among them
	// the no-break spaces U+00A0, U+2007 and U+202F, which Java's whitespace leaves
	// out. CONTROL holds the tab, the line terminators and the other controls, which
	// covers the rest of Java's whitespace. FORMAT holds the zero width space U+200B,
	// the joiners, the directional marks, the soft hyphen and the byte order mark.
	// Every other class counts as visible, apart from the code points RENDERED_EMPTY
	// lists. That keeps a combining mark, a private use character and an unassigned
	// code point, because a font decides what each of them shows.
	private static boolean isVisible(int codePoint) {
		return switch (Character.getType(codePoint)) {
			case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.CONTROL,
					Character.FORMAT ->
				false;
			default -> !isRenderedEmpty(codePoint);
		};
	}

	private static boolean isRenderedEmpty(int codePoint) {
		for (int[] range : RENDERED_EMPTY) {
			if (codePoint >= range[0] && codePoint <= range[1]) {
				return true;
			}
		}
		return false;
	}

}
