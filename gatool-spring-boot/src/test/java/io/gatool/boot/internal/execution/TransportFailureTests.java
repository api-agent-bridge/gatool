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

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cause chains the HTTP clients leave, built by hand, so the types a stub cannot
 * provoke cheaply are read as well: a connect timeout, a host without a route, and the
 * exceptions of a client that is absent from this classpath.
 * {@link TransportFailureOverHttpTests} reads the chains two real clients raise.
 */
class TransportFailureTests {

	@ParameterizedTest
	@MethodSource("failuresBeforeTheRequestLeft")
	void of_connectionThatDidNotOpen_shouldSayTheRequestDidNotLeave(IOException cause) {
		assertThat(TransportFailure.of(wrapped(cause))).as(String.valueOf(cause))
			.isEqualTo(TransportFailure.BEFORE_THE_REQUEST_LEFT);
	}

	static Stream<IOException> failuresBeforeTheRequestLeft() {
		ConnectException withoutAnAddress = new ConnectException();
		withoutAnAddress.initCause(new UnresolvedAddressException());
		return Stream.of(new ConnectException("Connection refused"), new UnknownHostException("api.example.com"),
				new NoRouteToHostException("No route to host"), withoutAnAddress,
				new SSLHandshakeException("Remote host terminated the handshake"),
				// A subclass of HttpTimeoutException, so it has to be read before its
				// parent.
				new HttpConnectTimeoutException("HTTP connect timed out"),
				new SocketTimeoutException("Connect timed out"), new SocketTimeoutException("connect timed out"),
				new SocketTimeoutException("Connect Timeout"), new ConnectTimeoutException("10000 MILLISECONDS"));
	}

	@ParameterizedTest
	@MethodSource("readTimeouts")
	void of_readTimeout_shouldSayTheApiTookLonger(IOException cause) {
		assertThat(TransportFailure.of(wrapped(cause))).as(String.valueOf(cause))
			.isEqualTo(TransportFailure.READ_TIMEOUT);
	}

	static Stream<IOException> readTimeouts() {
		return Stream.of(new HttpTimeoutException("Request cancelled"), new HttpTimeoutException("get"),
				new SocketTimeoutException("Read timed out"), new SocketTimeoutException("Read timeout"));
	}

	@ParameterizedTest
	@MethodSource("failuresThatDoNotSayHowFarTheCallGot")
	void of_failureThatDoesNotSayHowFarTheCallGot_shouldStayUnclassified(Throwable failure) {
		assertThat(TransportFailure.of(failure)).as(String.valueOf(failure)).isEqualTo(TransportFailure.UNCLASSIFIED);
	}

	static Stream<Throwable> failuresThatDoNotSayHowFarTheCallGot() {
		return Stream.of(wrapped(new IOException("HTTP/1.1 header parser received no bytes")),
				wrapped(new SocketException("Unexpected end of file from server")),
				// A message has to say which timeout it is, and one that names a thread
				// or a connection has to stay unmatched.
				wrapped(new SocketTimeoutException()), wrapped(new SocketTimeoutException("10000 MILLISECONDS")),
				wrapped(new SocketTimeoutException("Timed out in thread http-1 on connection 7")),
				// Jetty ends a request with this one, whichever phase the request was in.
				new IllegalStateException(new TimeoutException("Total timeout 1000 ms elapsed")),
				new IllegalStateException("connection refused"));
	}

	@Test
	void of_readTimeoutInsideABodyTheReaderCouldNotFinish_shouldSayTheApiTookLonger() {
		// The chain the client on HttpURLConnection leaves when a body stalls.
		RestClientException failure = new RestClientException("Error while extracting response",
				new HttpMessageNotReadableException("JSON parse error: Read timed out",
						new IOException(new SocketTimeoutException("Read timed out")),
						new MockHttpInputMessage(new byte[0])));

		assertThat(TransportFailure.of(failure)).isEqualTo(TransportFailure.READ_TIMEOUT);
	}

	@Test
	void of_readDeadlinePassedInsideABodyUnderTheJdkClient_shouldSayTheApiTookLonger() {
		// The chain the JDK client leaves when a body stalls, with the type
		// BoundedResponseRequestFactory raises around the client's own failure.
		RestClientException failure = new RestClientException("Error while extracting response",
				new HttpMessageNotReadableException("JSON parse error: closed",
						new IOException(new ReadDeadlinePassedException(Duration.ofSeconds(20),
								closedBy(new IOException("subscription cancelled")))),
						new MockHttpInputMessage(new byte[0])));

		assertThat(TransportFailure.of(failure)).isEqualTo(TransportFailure.READ_TIMEOUT);
	}

	@Test
	void of_connectionDroppedInsideABodyUnderTheJdkClient_shouldStayUnclassified() {
		// The same chain without that type, which is what a connection the API closed
		// inside the deadline leaves.
		RestClientException failure = new RestClientException("Error while extracting response",
				new HttpMessageNotReadableException("JSON parse error: closed",
						new IOException(closedBy(new EOFException("EOF reached while reading"))),
						new MockHttpInputMessage(new byte[0])));

		assertThat(TransportFailure.of(failure)).isEqualTo(TransportFailure.UNCLASSIFIED);
	}

	@Test
	void of_connectTimeoutTheJdkClientWrapsAroundAConnectException_shouldSayTheRequestDidNotLeave() {
		HttpConnectTimeoutException timeout = new HttpConnectTimeoutException("HTTP connect timed out");
		timeout.initCause(new ConnectException("HTTP connect timed out"));

		assertThat(TransportFailure.of(wrapped(timeout))).isEqualTo(TransportFailure.BEFORE_THE_REQUEST_LEFT);
	}

	@Test
	void of_causeChainThatPointsBackAtItself_shouldEndUnclassified() {
		IOException first = new IOException("first");
		IOException second = new IOException("second", first);
		first.initCause(second);

		assertThat(TransportFailure.of(first)).isEqualTo(TransportFailure.UNCLASSIFIED);
	}

	// The JDK client's failure of a read: "closed", then how far the body got, then
	// what ended it.
	private static IOException closedBy(IOException ending) {
		return new IOException("closed", new IOException("fixed content-length: 250, bytes received: 50", ending));
	}

	private static ResourceAccessException wrapped(IOException cause) {
		return new ResourceAccessException("I/O error on POST request for \"http://localhost:1/graphql\"", cause);
	}

	// The shape of Apache HttpClient 5's connect timeout: a SocketTimeoutException by
	// type, with a message that names the wait alone.
	private static final class ConnectTimeoutException extends SocketTimeoutException {

		private static final long serialVersionUID = 1L;

		ConnectTimeoutException(String message) {
			super(message);
		}

	}

}
