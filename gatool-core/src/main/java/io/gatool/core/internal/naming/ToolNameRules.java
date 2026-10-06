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

package io.gatool.core.internal.naming;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Utility methods for tool names, holding the rule that every final tool name follows.
 *
 * @author Željko Kozina
 */
public final class ToolNameRules {

	// Letters, digits, underscores and hyphens, up to 64 characters, form the portable
	// set of tool names.
	private static final Pattern PORTABLE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private ToolNameRules() {
	}

	/**
	 * Tells whether a tool name uses only letters, digits, {@code _} and {@code -}, with
	 * 1 to 64 characters.
	 * @param name the tool name to check
	 * @return {@code true} when every client can use the name
	 */
	public static boolean isPortable(String name) {
		return PORTABLE_NAME.matcher(name).matches();
	}

	/**
	 * Returns what is wrong with a tool name the catalog is about to publish, or
	 * {@code null} where it can be published.
	 *
	 * <p>
	 * Two rules, in the order a reader fixes them: the portable set of
	 * {@link #isPortable}, then at least one letter or digit. A name of separators alone,
	 * such as {@code ---}, passes the first rule, and a person cannot read it. An
	 * explicit {@code @gatool(name:)} and a name a strategy built both end here, so the
	 * two fail the same way.
	 * @param name the tool name to check
	 * @return the rule the name breaks, written to follow "a tool name", or {@code null}
	 * where the name follows both
	 */
	public static @Nullable String problemWith(String name) {
		if (!isPortable(name)) {
			return "uses only letters, digits, '_' and '-', with at most 64 characters";
		}
		if (!holdsLetterOrDigit(name)) {
			return "holds at least one letter or digit";
		}
		return null;
	}

	/**
	 * Tells whether a tool name can travel in a challenge header and a log line:
	 * printable ASCII without a space, 1 to 256 characters.
	 *
	 * <p>
	 * The MCP endpoint refuses a {@code tools/call} naming anything else. Startup stops
	 * on a registered tool named outside this set, so a scoped tool is always named to
	 * the scope check.
	 * @param name the tool name to check
	 * @return {@code true} when a header can carry the name
	 */
	public static boolean fitsInHeader(String name) {
		if (name.isEmpty() || name.length() > 256) {
			return false;
		}
		for (int index = 0; index < name.length(); index++) {
			char character = name.charAt(index);
			if (character < 0x21 || character > 0x7E) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns the tool name a strategy built, once it holds something a client can call.
	 *
	 * <p>
	 * Every built-in strategy ends here, so a name made of separators alone fails the
	 * same way whichever strategy produced it. The catalog turns the exception into a
	 * startup problem that names the operation file to fix.
	 * @param graphQlName the GraphQL name the strategy read
	 * @param toolName the tool name the strategy built
	 * @return the tool name
	 * @throws IllegalArgumentException if the tool name consists of separators alone
	 */
	public static String requireNameable(String graphQlName, String toolName) {
		if (!holdsLetterOrDigit(toolName)) {
			throw new IllegalArgumentException(
					"'" + graphQlName + "' needs at least one letter or digit to become a tool name");
		}
		return toolName;
	}

	private static boolean holdsLetterOrDigit(String name) {
		return name.chars().anyMatch(Character::isLetterOrDigit);
	}

}
