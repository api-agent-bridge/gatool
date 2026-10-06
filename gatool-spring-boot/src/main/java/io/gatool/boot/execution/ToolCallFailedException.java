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

package io.gatool.boot.execution;

import java.util.Objects;

/**
 * A tool call that failed in transport, on the way to the GraphQL API or on the way back.
 *
 * <p>
 * The message is GATool's own text. It names the tool and says how far the call got where
 * the cause tells: the connection did not open, the API took longer to answer than the
 * read timeout, or the call failed at a point the cause leaves open. It leaves stack
 * traces and exception class names out of what a model reads. The log carries the cause.
 *
 * <p>
 * The exception is public because an application meets it as the cause of Spring AI's
 * {@code ToolExecutionException}. A package-private type with each adapter writing its
 * own message would put the same sentence in two places.
 *
 * @author Željko Kozina
 */
public final class ToolCallFailedException extends RuntimeException {

	// The compiler runs with -Xlint:all and -Werror, and the serial lint asks every
	// Serializable class for this field.
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 * @param message the message GATool wrote, which names the tool
	 * @param cause the failure that the transport reported
	 */
	public ToolCallFailedException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Returns the message the constructor received.
	 * @return the message GATool wrote, which is always present
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage(), "the constructor requires a message");
	}

}
