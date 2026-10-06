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

package io.gatool.boot.internal.credentials;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A credential strategy could not supply the credential it sends, so the call to the
 * GraphQL API did not go out.
 *
 * <p>
 * The two built-in strategies that read the caller's token raise it when the thread runs
 * without a bearer token, and the OAuth2 strategy raises it when the authorization server
 * refuses the request for a token. The transport sentence of {@code ToolCallRunner}
 * invites a retry, which would fail the same way, because the credential is the
 * operator's to fix.
 *
 * <p>
 * The message names the strategy and says what was missing or refused, and the runner
 * puts it in front of the model with the sentence a refused credential gets.
 *
 * @author Željko Kozina
 */
public final class CredentialUnavailableException extends RuntimeException {

	// The compiler runs with -Xlint:all and -Werror, and the serial lint asks every
	// Serializable class for this field.
	private static final long serialVersionUID = 1L;

	private final String strategy;

	/**
	 * Creates the exception.
	 * @param strategy the strategy that could not supply the credential, as the property
	 * value that selects it, such as {@code token-exchange}, or the class name of an
	 * application's own bean
	 * @param message what was missing or refused, which names the strategy and is written
	 * for the model
	 * @param cause the failure the strategy met, or {@code null} where the strategy found
	 * the credential missing itself
	 */
	public CredentialUnavailableException(String strategy, String message, @Nullable Throwable cause) {
		super(message, cause);
		this.strategy = strategy;
	}

	/**
	 * Returns the strategy that could not supply the credential.
	 * @return the property value that selects it, or the class name of an application's
	 * own bean
	 */
	public String strategy() {
		return this.strategy;
	}

	/**
	 * Returns the message the constructor received.
	 * @return what was missing or refused, which is always present
	 */
	@Override
	public String getMessage() {
		return Objects.requireNonNull(super.getMessage(), "the constructor requires a message");
	}

}
