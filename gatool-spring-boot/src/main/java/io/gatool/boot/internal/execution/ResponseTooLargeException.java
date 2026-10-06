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

package io.gatool.boot.internal.execution;

import java.util.Objects;

/**
 * The GraphQL API sent more than {@code gatool.api.max-response-size}.
 *
 * <p>
 * The limit is applied to the bytes as they arrive, because
 * {@code gatool.results.max-characters} is a presentation limit that can only be checked
 * once the whole response is a {@code String}. Reaching that check takes three copies of
 * the response: the bytes, the parsed map and the text. A 200 MB response ends in
 * {@code OutOfMemoryError} while the text is being built, on a tool whose result limit is
 * 60,000 characters.
 *
 * <p>
 * The message is written for the model, because {@code ToolCallRunner} answers this one
 * with a tool error instead of the sentence it uses for a transport failure. The call did
 * reach the API, and asking for less is something the model can act on.
 *
 * @author Željko Kozina
 */
public final class ResponseTooLargeException extends RuntimeException {

	// The compiler runs with -Xlint:all and -Werror, and the serial lint asks every
	// Serializable class for this field.
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 * @param message what the model reads, which names the limit
	 */
	public ResponseTooLargeException(String message) {
		super(message);
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
