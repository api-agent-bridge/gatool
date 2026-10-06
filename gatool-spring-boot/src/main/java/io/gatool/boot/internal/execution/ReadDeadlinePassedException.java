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

import java.io.IOException;
import java.time.Duration;

/**
 * The body of a response was still being read when the read deadline of the client
 * passed.
 *
 * <p>
 * Spring's request on the JDK client closes the body when its deadline passes, and the
 * read then fails with the plain {@code IOException} a dropped connection leaves.
 * {@link BoundedResponseRequestFactory} raises this type around that failure where the
 * deadline had passed, so {@link TransportFailure} reads a read timeout from the type,
 * the way it reads one from the {@code SocketTimeoutException} the other clients raise.
 *
 * <p>
 * It is an {@code IOException}, so Spring and the executor keep treating the failure as
 * one of transport, and the client's own failure stays in the chain as the cause, where
 * the DEBUG line prints it.
 *
 * @author Željko Kozina
 */
final class ReadDeadlinePassedException extends IOException {

	// The compiler runs with -Xlint:all and -Werror, and the serial lint asks every
	// Serializable class for this field.
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 * @param deadline the read deadline that passed
	 * @param cause what the read of the body failed with
	 */
	ReadDeadlinePassedException(Duration deadline, IOException cause) {
		super("The read timeout of " + deadline.toMillis() + " ms passed while the body of the response was "
				+ "being read", cause);
	}

}
