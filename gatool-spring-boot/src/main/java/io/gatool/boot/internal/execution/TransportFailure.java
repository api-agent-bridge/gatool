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

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLHandshakeException;

import io.gatool.boot.internal.CauseChain;

/**
 * How far a call got before it failed in transport, read from the cause chain of the
 * failure.
 *
 * <p>
 * A connection that did not open means the call did not run, and a later call may
 * succeed. A read timeout is different: the API received the request and was still
 * working when the client stopped waiting, so the call may have run, and the same call is
 * likely to take as long again.
 *
 * <p>
 * The HTTP clients raise different exceptions for the same failure, and Spring wraps each
 * in a {@code ResourceAccessException}, or in the reader's failure where the body had
 * begun, so the chain is what gets read. These are the ones observed against a stub, with
 * the JDK client and with the client on {@code HttpURLConnection}:
 *
 * <ul>
 * <li>A refused connection is a {@code ConnectException} with both. A host without an
 * address is an {@code UnknownHostException} with {@code HttpURLConnection}, and a
 * {@code ConnectException} carrying an {@code UnresolvedAddressException} with the JDK
 * client.</li>
 * <li>A connect timeout is a {@code HttpConnectTimeoutException} with the JDK client, and
 * a {@code SocketTimeoutException} saying "Connect timed out" with
 * {@code HttpURLConnection}.</li>
 * <li>A TLS handshake the server ended is a {@code SSLHandshakeException} with both.</li>
 * <li>A read timeout before the status arrived is a {@code HttpTimeoutException} with the
 * JDK client, and a {@code SocketTimeoutException} saying "Read timed out" with
 * {@code HttpURLConnection}.</li>
 * <li>A read timeout inside the body is the same {@code SocketTimeoutException} with
 * {@code HttpURLConnection}. With the JDK client it is a
 * {@link ReadDeadlinePassedException}, which {@link BoundedResponseRequestFactory}
 * raises: Spring's JDK request closes the body when its deadline passes, and the client
 * then fails with the plain {@code IOException} a dropped connection leaves.</li>
 * </ul>
 *
 * <p>
 * Apache HttpClient 5 declares its {@code HttpHostConnectException} under
 * {@code ConnectException} and its {@code ConnectTimeoutException} under
 * {@code SocketTimeoutException}, so the same checks read both.
 *
 * <p>
 * The read deadline can pass before the request left. With the JDK client it bounds the
 * whole exchange, so it ends a connection that is still opening where it is the shorter
 * of the two deadlines, and with both clients it ends a TLS handshake that stalls. Each
 * reads as a read timeout, which is the deadline that ended it.
 *
 * <p>
 * Every other failure stays {@link #UNCLASSIFIED}. A connection that dropped inside the
 * body is one of them, with both clients.
 *
 * @author Željko Kozina
 */
enum TransportFailure {

	/**
	 * The connection did not open, so the request did not leave and the call did not run.
	 */
	BEFORE_THE_REQUEST_LEFT,

	/**
	 * The API took longer to answer than the read timeout of the client, so the call may
	 * have run.
	 */
	READ_TIMEOUT,

	/**
	 * The chain does not say how far the call got.
	 */
	UNCLASSIFIED;

	// By name, because java.net.http is a module of its own and a runtime image built
	// without it still runs the other HTTP clients. An instanceof against a class
	// that is missing raises a NoClassDefFoundError in place of the answer.
	private static final String HTTP_CONNECT_TIMEOUT = "java.net.http.HttpConnectTimeoutException";

	private static final String HTTP_TIMEOUT = "java.net.http.HttpTimeoutException";

	/**
	 * Reads how far the call got from a failure and its causes.
	 * @param failure what the executor raised
	 * @return where the call stopped, or {@link #UNCLASSIFIED} where the chain does not
	 * say
	 */
	// A connection that did not open is looked for first and through the whole chain,
	// because HttpConnectTimeoutException extends HttpTimeoutException, and the second
	// pass would read it as a read timeout.
	static TransportFailure of(Throwable failure) {
		if (CauseChain.any(failure, TransportFailure::stoppedBeforeTheRequestLeft)) {
			return BEFORE_THE_REQUEST_LEFT;
		}
		if (CauseChain.any(failure, TransportFailure::isReadTimeout)) {
			return READ_TIMEOUT;
		}
		return UNCLASSIFIED;
	}

	// The failures that end before a byte of the request leaves: the host is unknown or
	// unreachable, the port refuses, or the TLS handshake fails.
	private static boolean isAConnectionThatWasRefusedOrUnresolved(Throwable cause) {
		return CONNECTION_FAILURES.stream().anyMatch((type) -> type.isInstance(cause));
	}

	private static final List<Class<? extends Throwable>> CONNECTION_FAILURES = List.of(ConnectException.class,
			UnknownHostException.class, NoRouteToHostException.class, UnresolvedAddressException.class,
			SSLHandshakeException.class);

	private static boolean stoppedBeforeTheRequestLeft(Throwable cause) {
		if (isAConnectionThatWasRefusedOrUnresolved(cause)) {
			return true;
		}
		if (isA(cause, HTTP_CONNECT_TIMEOUT)) {
			return true;
		}
		return cause instanceof SocketTimeoutException
				&& (says(cause, "connect tim") || cause.getClass().getSimpleName().contains("ConnectTimeout"));
	}

	private static boolean isReadTimeout(Throwable cause) {
		return cause instanceof ReadDeadlinePassedException || isA(cause, HTTP_TIMEOUT)
				|| (cause instanceof SocketTimeoutException && says(cause, "read tim"));
	}

	// SocketTimeoutException is one type for both timeouts, and the message is what
	// tells them apart: "Connect timed out" and "Read timed out" from the JDK's socket.
	// The words are matched up to "tim", which reads "timed out" and "timeout" alike
	// and leaves a message that names a connection or a thread unmatched. The class
	// name is read for a connect timeout as well, because Apache's
	// ConnectTimeoutException extends SocketTimeoutException and writes a message of
	// its own. A message without either phrase stays unclassified.
	private static boolean says(Throwable cause, String words) {
		String message = cause.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains(words);
	}

	private static boolean isA(Throwable cause, String className) {
		for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
			if (className.equals(type.getName())) {
				return true;
			}
		}
		return false;
	}

}
