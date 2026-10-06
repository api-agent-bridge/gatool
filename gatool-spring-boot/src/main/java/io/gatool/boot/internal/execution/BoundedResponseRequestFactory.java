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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.unit.DataSize;

/**
 * Stops reading a GraphQL response once it passes {@code gatool.api.max-response-size}.
 *
 * <p>
 * The schema fetch uses the same factory under {@code gatool.api.schema.max-size}, and
 * {@code io.gatool.boot.internal.schema.SchemaUrlReader} catches the refusal and writes a
 * sentence of its own naming that property, because the sentence below is written for a
 * model asking for a smaller page.
 *
 * <p>
 * The limit belongs here instead of beside {@code gatool.results.max-characters}, because
 * that one is checked on a finished {@code String}. By the time it can say the result is
 * too large, the process holds the bytes, the parsed map and the text at once. The
 * counting stream below refuses before any of that is built, so one call costs at most
 * the configured size.
 *
 * <p>
 * Only the body is wrapped. Everything else is the factory Spring Boot built, so the
 * timeouts, the SSL bundle and the client implementation all stay as they were.
 *
 * <p>
 * The same wrapper names a read deadline that passed inside the body. Where the client
 * applies its read deadline to the whole exchange, a read of the body that fails once
 * that time has passed is raised as a {@link ReadDeadlinePassedException}, because the
 * client itself reports it the way it reports a dropped connection.
 *
 * <p>
 * It also raises the end of a body that came short of the {@code Content-Length} the
 * response declares, because {@code HttpURLConnection} on Java 21 hands such a body out
 * as one that ended.
 *
 * @author Željko Kozina
 */
public final class BoundedResponseRequestFactory implements ClientHttpRequestFactory {

	// What HttpHeaders.getContentLength() returns for a response without the header.
	private static final long UNDECLARED = -1;

	private final ClientHttpRequestFactory delegate;

	private final long maxBytes;

	private final DataSize maxResponseSize;

	// Null where the client applies its read deadline to each wait for data, and
	// where GATool does not know the deadline. A failed read then passes as it is.
	private final @Nullable Duration exchangeDeadline;

	private final LongSupplier clock;

	/**
	 * Wraps one request factory whose failures pass as the client raised them.
	 * @param delegate the factory Spring Boot built
	 * @param maxResponseSize the value of {@code gatool.api.max-response-size}
	 */
	public BoundedResponseRequestFactory(ClientHttpRequestFactory delegate, DataSize maxResponseSize) {
		this(delegate, maxResponseSize, null);
	}

	/**
	 * Wraps one request factory, and names the read deadline where it passed inside the
	 * body.
	 * @param delegate the factory Spring Boot built
	 * @param maxResponseSize the value of {@code gatool.api.max-response-size}
	 * @param exchangeDeadline the read deadline of the client where it bounds the whole
	 * exchange, from the request being sent to the end of the body, as it does with the
	 * JDK client, or {@code null} where the client applies it to each wait for data
	 */
	public BoundedResponseRequestFactory(ClientHttpRequestFactory delegate, DataSize maxResponseSize,
			@Nullable Duration exchangeDeadline) {
		this(delegate, maxResponseSize, exchangeDeadline, System::nanoTime);
	}

	/**
	 * Wraps one request factory with the clock the deadline is measured on.
	 * @param delegate the factory Spring Boot built
	 * @param maxResponseSize the value of {@code gatool.api.max-response-size}
	 * @param exchangeDeadline the read deadline of the client where it bounds the whole
	 * exchange, or {@code null}
	 * @param clock the time in nanoseconds, which a test moves by hand
	 */
	BoundedResponseRequestFactory(ClientHttpRequestFactory delegate, DataSize maxResponseSize,
			@Nullable Duration exchangeDeadline, LongSupplier clock) {
		this.delegate = delegate;
		this.maxBytes = maxResponseSize.toBytes();
		this.maxResponseSize = maxResponseSize;
		this.exchangeDeadline = exchangeDeadline;
		this.clock = clock;
	}

	/**
	 * Returns the limit as a reader sees it, in kilobytes where it is at least one.
	 * @param maxResponseSize the value of {@code gatool.api.max-response-size}
	 * @return the limit, such as {@code 10240KB} or {@code 512 bytes}
	 */
	// A limit under 1KB is printed in bytes, because whole kilobytes would print it as
	// 0KB.
	public static String describeLimit(DataSize maxResponseSize) {
		long kilobytes = maxResponseSize.toKilobytes();
		return (kilobytes > 0) ? kilobytes + "KB" : maxResponseSize.toBytes() + " bytes";
	}

	@Override
	public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
		ClientHttpRequest request = this.delegate.createRequest(uri, httpMethod);
		return new ClientHttpRequest() {

			@Override
			public ClientHttpResponse execute() throws IOException {
				// Read before the client gets the request, because Spring's JDK request
				// starts its deadline inside the call below. The interceptors have run by
				// now, so the time a credential strategy spent on a token stays out.
				long sentAt = BoundedResponseRequestFactory.this.clock.getAsLong();
				return bounded(request.execute(), sentAt);
			}

			@Override
			public OutputStream getBody() throws IOException {
				return request.getBody();
			}

			@Override
			public HttpMethod getMethod() {
				return request.getMethod();
			}

			@Override
			public URI getURI() {
				return request.getURI();
			}

			@Override
			public HttpHeaders getHeaders() {
				return request.getHeaders();
			}

			@Override
			public Map<String, Object> getAttributes() {
				return request.getAttributes();
			}
		};
	}

	// One counting stream per response, created on the first call and handed out
	// again after that. RestClient's introspecting wrapper reads the first byte through
	// one call to getBody(), and the converter reads on through the wrapper. The
	// transport reads an error body through a call of its own, so a caller that asked
	// twice would otherwise get a second counter starting from zero on the same bytes.
	private ClientHttpResponse bounded(ClientHttpResponse response, long sentAt) {
		return new ClientHttpResponse() {

			private @Nullable CountingStream body;

			@Override
			public InputStream getBody() throws IOException {
				CountingStream stream = this.body;
				if (stream == null) {
					stream = new CountingStream(response.getBody(), sentAt, declaredLength(response.getHeaders()));
					this.body = stream;
				}
				return stream;
			}

			@Override
			public HttpStatusCode getStatusCode() throws IOException {
				return response.getStatusCode();
			}

			@Override
			public String getStatusText() throws IOException {
				return response.getStatusText();
			}

			@Override
			public void close() {
				response.close();
			}

			@Override
			public HttpHeaders getHeaders() {
				return response.getHeaders();
			}
		};
	}

	// The length counts the bytes as they travel. A client that decodes a compressed
	// body hands out another number of bytes, and Apache HttpClient, Jetty and Netty
	// take the length out of the headers when they decode. The length is left unread
	// beside a Content-Encoding all the same, so a client that kept both headers
	// cannot turn a whole body into a failure here.
	private static long declaredLength(HttpHeaders headers) {
		if (headers.getFirst(HttpHeaders.CONTENT_ENCODING) != null) {
			return UNDECLARED;
		}
		return headers.getContentLength();
	}

	// A stream that counts what it hands out and refuses past the limit. The refusal
	// is a RuntimeException instead of an IOException, because Spring turns an
	// IOException from the body into a transport failure, and this is a result the
	// model can act on by asking for a smaller page.
	private final class CountingStream extends FilterInputStream {

		private final long sentAt;

		private final long declaredLength;

		private long read;

		private CountingStream(InputStream in, long sentAt, long declaredLength) {
			super(in);
			this.sentAt = sentAt;
			this.declaredLength = declaredLength;
		}

		@Override
		public int read() throws IOException {
			int value;
			try {
				value = super.read();
			}
			catch (IOException ex) {
				throw namingAPassedDeadline(ex);
			}
			if (value != -1) {
				count(1);
			}
			else {
				requireTheDeclaredLength();
			}
			return value;
		}

		@Override
		public int read(byte[] target, int offset, int length) throws IOException {
			int count;
			try {
				count = super.read(target, offset, length);
			}
			catch (IOException ex) {
				throw namingAPassedDeadline(ex);
			}
			if (count > 0) {
				count(count);
			}
			else if (count == -1) {
				requireTheDeclaredLength();
			}
			return count;
		}

		// A connection that closes inside a body of declared length is a failure of the
		// transport. The JDK client raises it on every Java version, and
		// HttpURLConnection raises "Premature EOF" on Java 25 and 26. On Java 21
		// HttpURLConnection ends the stream as though the body were whole, so the JSON
		// reader would meet the end of the input inside a value, and the model would read
		// that the API answered with a body that is not a GraphQL response and that the
		// same call gets the same answer. Raised here, the end reads the same behind each
		// client and on each Java version. An undeclared length is -1 and the count
		// starts at zero, so a body without the header ends as the client ended it.
		private void requireTheDeclaredLength() throws EOFException {
			if (this.read < this.declaredLength) {
				throw new EOFException("The body ended after " + this.read + " of the " + this.declaredLength
						+ " bytes its Content-Length declares");
			}
		}

		// Spring's JDK request closes the body when its deadline passes, and the read
		// then fails with "closed", the IOException a dropped connection leaves as well,
		// so the type of the failure does not tell the two apart. The time does. The
		// deadline bounds the exchange from the moment the request is sent, and the
		// client ends every exchange that is still open when it passes, so a read that
		// fails before then was ended by something else. The clock here starts a moment
		// ahead of the client's own, so a read the deadline ended is always measured at
		// or past it. The JDK puts "subscription cancelled" at the end of the chain when
		// the body was closed from this side, on Java 21, 25 and 26, but that text
		// belongs to the inside of the JDK, which a release is free to change. Measuring
		// in the runner would start the clock before the credential strategy and the
		// other interceptors run, so a slow token fetch would count as time the API took,
		// and the runner cannot know which client is behind an executor.
		private IOException namingAPassedDeadline(IOException failure) {
			Duration deadline = BoundedResponseRequestFactory.this.exchangeDeadline;
			if (deadline == null) {
				return failure;
			}
			long elapsedNanos = BoundedResponseRequestFactory.this.clock.getAsLong() - this.sentAt;
			if (Duration.ofNanos(elapsedNanos).compareTo(deadline) < 0) {
				return failure;
			}
			return new ReadDeadlinePassedException(deadline, failure);
		}

		private void count(int bytes) {
			this.read += bytes;
			if (this.read > BoundedResponseRequestFactory.this.maxBytes) {
				throw new ResponseTooLargeException("The GraphQL API sent more than "
						+ describeLimit(BoundedResponseRequestFactory.this.maxResponseSize)
						+ ", which is the limit gatool.api.max-response-size sets, so GATool stopped reading the "
						+ "response.");
			}
		}

	}

}
