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

package io.gatool.boot.internal;

import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Reads a failure and its causes, down to a fixed depth.
 *
 * <p>
 * The HTTP client, the JSON reader and Spring each wrap the failure below them, so the
 * cause that says what happened sits some way down the chain. The depth is bounded
 * because {@code getCause()} can be overridden, and a chain that points back at itself
 * would otherwise be walked without end.
 *
 * @author Željko Kozina
 */
public final class CauseChain {

	/**
	 * How many failures one walk reads: the failure itself and nine causes below it.
	 */
	static final int MAX_DEPTH = 10;

	private CauseChain() {
	}

	/**
	 * Returns the first failure in the chain that the test accepts.
	 * @param failure the failure the walk starts at
	 * @param accepts the test each failure in the chain is put to
	 * @return the failure itself or the nearest cause the test accepts, or {@code null}
	 * where the walk ends without one
	 */
	public static @Nullable Throwable first(Throwable failure, Predicate<? super Throwable> accepts) {
		Throwable cause = failure;
		for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++) {
			if (accepts.test(cause)) {
				return cause;
			}
			cause = cause.getCause();
		}
		return null;
	}

	/**
	 * Returns the first failure in the chain that is of the given type.
	 * @param failure the failure the walk starts at
	 * @param type the type to look for
	 * @param <T> the type to look for
	 * @return the failure itself or the nearest cause of that type, or {@code null} where
	 * the walk ends without one
	 */
	public static <T extends Throwable> @Nullable T first(Throwable failure, Class<T> type) {
		Throwable found = first(failure, type::isInstance);
		return (found != null) ? type.cast(found) : null;
	}

	/**
	 * Whether the test accepts the failure or one of its causes.
	 * @param failure the failure the walk starts at
	 * @param accepts the test each failure in the chain is put to
	 * @return true where the walk finds one the test accepts
	 */
	public static boolean any(Throwable failure, Predicate<? super Throwable> accepts) {
		return first(failure, accepts) != null;
	}

}
