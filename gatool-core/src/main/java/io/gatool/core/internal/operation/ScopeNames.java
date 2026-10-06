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

import org.jspecify.annotations.Nullable;

/**
 * The characters a scope name may hold, so that a scope written in a file or a property
 * survives the {@code scope} parameter of a bearer challenge.
 *
 * <p>
 * RFC 6749 defines a scope token as one or more characters from
 * {@code %x21 / %x23-5B / %x5D-7E}: printable ASCII without space, double quote and
 * backslash. A space would split the value into two scopes on the wire, and the other two
 * cannot sit in a quoted string, so all three are refused where the scope is read.
 *
 * @author Željko Kozina
 */
public final class ScopeNames {

	private ScopeNames() {
	}

	/**
	 * Returns what is wrong with one scope name, or {@code null} for a name that fits the
	 * syntax.
	 * @param scope the scope as written
	 * @return the problem, or {@code null}
	 */
	public static @Nullable String problemWith(String scope) {
		if (scope.isEmpty()) {
			return "is empty";
		}
		for (int index = 0; index < scope.length(); index++) {
			char character = scope.charAt(index);
			if (character == ' ') {
				return "holds a space, and a space separates scopes on the wire";
			}
			if (character == '"' || character == '\\') {
				return "holds a quote or a backslash, which a challenge cannot carry";
			}
			if (character < 0x21 || character > 0x7E) {
				return "holds a character outside printable ASCII";
			}
		}
		return null;
	}

}
