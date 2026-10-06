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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIOException;

// A fake factory hands back a body from memory, so every read path of the counting
// stream runs without a socket and the cap is checked to the byte.
class BoundedResponseRequestFactoryTests {

	private static final URI API = URI.create("http://localhost/graphql");

	@Test
	void getBody_calledTwice_shouldReturnTheSameStream() throws IOException {
		ClientHttpResponse response = bounded("abcdef", DataSize.ofBytes(10));

		InputStream first = response.getBody();
		InputStream second = response.getBody();

		// RestClient reads the first byte through one call and the rest through another,
		// so a second counter starting from zero would count the same bytes twice, or
		// hand a second caller a stream that lost the bytes the first one read.
		assertThat(second).isSameAs(first);
	}

	@Test
	void read_byteAtATime_shouldHandOutTheBytesUpToTheCapAndRefuseTheNextOne() throws IOException {
		InputStream body = bounded("abcdef", DataSize.ofBytes(4)).getBody();

		assertThat(body.read()).isEqualTo('a');
		assertThat(body.read()).isEqualTo('b');
		assertThat(body.read()).isEqualTo('c');
		assertThat(body.read()).isEqualTo('d');
		assertThatExceptionOfType(ResponseTooLargeException.class).isThrownBy(body::read);
	}

	@Test
	void read_intoAnArray_shouldCountTheBytesReadAndRefusePastTheCap() throws IOException {
		InputStream body = bounded("abcdef", DataSize.ofBytes(4)).getBody();
		byte[] target = new byte[3];

		assertThat(body.read(target, 0, 3)).isEqualTo(3);
		assertThat(new String(target, StandardCharsets.UTF_8)).isEqualTo("abc");
		// The next read hands out three more bytes, which crosses the cap.
		assertThatExceptionOfType(ResponseTooLargeException.class).isThrownBy(() -> body.read(target, 0, 3));
	}

	@Test
	void read_aBodyAboveTheLimit_shouldStateTheLimitAndLeaveTheAdviceToTheRunner() throws IOException {
		// What the model should do next depends on whether the operation writes, which
		// the runner knows and this factory does not.
		InputStream body = bounded("abcdef", DataSize.ofBytes(4)).getBody();

		assertThatExceptionOfType(ResponseTooLargeException.class).isThrownBy(body::readAllBytes)
			.withMessageEndingWith("so GATool stopped reading the response.");
	}

	@Test
	void read_aLimitBelowOneKilobyte_shouldNameThatLimitInBytes() throws IOException {
		// A limit under 1KB is printed in bytes, because whole kilobytes would tell the
		// model it had sent "more than 0KB", which does not name a limit it can work
		// with.
		InputStream body = bounded("abcdef", DataSize.ofBytes(4)).getBody();

		assertThatExceptionOfType(ResponseTooLargeException.class).isThrownBy(body::readAllBytes)
			.withMessageContaining("4 bytes")
			.withMessageNotContaining("0KB");
	}

	@Test
	void read_aLimitInKilobytes_shouldNameItInKilobytes() throws IOException {
		InputStream body = bounded("abcdef", DataSize.ofKilobytes(2)).getBody();

		assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abcdef");
	}

	@Test
	void readAllBytes_bodyUnderTheCap_shouldReturnEveryByte() throws IOException {
		InputStream body = bounded("abcdef", DataSize.ofBytes(6)).getBody();

		assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abcdef");
	}

	@Test
	void readAllBytes_bodyOverTheCap_shouldRefuse() throws IOException {
		InputStream body = bounded("abcdef", DataSize.ofBytes(5)).getBody();

		assertThatExceptionOfType(ResponseTooLargeException.class).isThrownBy(body::readAllBytes);
	}

	@Test
	void transferTo_bodyOverTheCap_shouldRefuseMidStreamAfterTransferringWhatFitBeforeIt() throws IOException {
		// A large response arrives in pieces, and the refusal has to fire on the piece
		// that crosses the cap, before the rest is read into memory.
		InputStream body = bounded("x".repeat(10_000), DataSize.ofBytes(2_500)).getBody();
		ByteArrayOutputStream target = new ByteArrayOutputStream();

		assertThatExceptionOfType(ResponseTooLargeException.class).isThrownBy(() -> body.transferTo(target))
			.withMessageContaining("gatool.api.max-response-size")
			.withMessageContaining("2KB");
		assertThat(target.size()).isLessThan(10_000);
	}

	@Test
	void transferTo_bodyUnderTheCap_shouldTransferEveryByte() throws IOException {
		InputStream body = bounded("abcdef", DataSize.ofBytes(6)).getBody();
		ByteArrayOutputStream target = new ByteArrayOutputStream();

		assertThat(body.transferTo(target)).isEqualTo(6);
		assertThat(target.toString(StandardCharsets.UTF_8)).isEqualTo("abcdef");
	}

	@Test
	void read_bodyEndingShortOfItsDeclaredLength_shouldRaiseTheEndOfTheBodyAsAFailureOfTheTransport()
			throws IOException {
		// HttpURLConnection on Java 21 hands such a body out as one that ended, and the
		// JSON reader would then meet the end of the input inside a value.
		InputStream body = declaring("abc", (headers) -> headers.setContentLength(10)).getBody();

		assertThat(body.read()).isEqualTo('a');
		assertThat(body.read()).isEqualTo('b');
		assertThat(body.read()).isEqualTo('c');
		assertThatExceptionOfType(EOFException.class).isThrownBy(body::read)
			.withMessage("The body ended after 3 of the 10 bytes its Content-Length declares");
	}

	@Test
	void readIntoAnArray_bodyEndingShortOfItsDeclaredLength_shouldRaiseTheEndOfTheBody() throws IOException {
		InputStream body = declaring("abc", (headers) -> headers.setContentLength(10)).getBody();

		assertThatExceptionOfType(EOFException.class).isThrownBy(body::readAllBytes)
			.withMessage("The body ended after 3 of the 10 bytes its Content-Length declares");
	}

	@Test
	void read_bodyOfItsDeclaredLength_shouldEndAsTheClientEndedIt() throws IOException {
		InputStream body = declaring("abc", (headers) -> headers.setContentLength(3)).getBody();

		assertThat(body.readAllBytes()).hasSize(3);
		assertThat(body.read()).isEqualTo(-1);
	}

	@Test
	void read_bodyWithoutADeclaredLength_shouldEndAsTheClientEndedIt() throws IOException {
		InputStream body = bounded("abc", DataSize.ofBytes(10)).getBody();

		assertThat(body.readAllBytes()).hasSize(3);
		assertThat(body.read()).isEqualTo(-1);
	}

	@Test
	void read_encodedBodyShorterThanItsDeclaredLength_shouldEndAsTheClientEndedIt() throws IOException {
		// The declared length counts the encoded bytes. A client that decoded the body
		// and kept both headers hands out another number of bytes, so the length is
		// left unread beside a Content-Encoding.
		InputStream body = declaring("abc", (headers) -> {
			headers.setContentLength(10);
			headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
		}).getBody();

		assertThat(body.readAllBytes()).hasSize(3);
		assertThat(body.read()).isEqualTo(-1);
	}

	@Test
	void bounded_statusHeadersAndClose_shouldReachTheResponseUnderneath() throws IOException {
		FakeResponse underneath = new FakeResponse("abc");
		ClientHttpResponse response = new BoundedResponseRequestFactory((uri, method) -> new FakeRequest(underneath),
				DataSize.ofBytes(10))
			.createRequest(API, HttpMethod.POST)
			.execute();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getStatusText()).isEqualTo("OK");
		assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("application/json");
		response.close();
		assertThat(underneath.closed).isTrue();
	}

	@Test
	void read_failingOnceTheExchangeDeadlineHasPassed_shouldRaiseAPassedReadDeadlineAroundTheFailure()
			throws IOException {
		// Spring's JDK request closes the body when its deadline passes, and the read
		// fails with the IOException a dropped connection leaves.
		AtomicLong clock = new AtomicLong();
		IOException closed = new IOException("closed");
		InputStream body = failingAfter("abc", closed, Duration.ofSeconds(20), clock).getBody();
		assertThat(body.read(new byte[3], 0, 3)).isEqualTo(3);
		clock.addAndGet(Duration.ofSeconds(20).toNanos());

		assertThatExceptionOfType(ReadDeadlinePassedException.class).isThrownBy(body::read)
			.withMessage("The read timeout of 20000 ms passed while the body of the response was being read")
			.withCause(closed);
		assertThatExceptionOfType(ReadDeadlinePassedException.class).isThrownBy(() -> body.read(new byte[8], 0, 8))
			.withCause(closed);
	}

	@Test
	void read_failingInsideTheExchangeDeadline_shouldRaiseTheFailureAsTheClientRaisedIt() throws IOException {
		// A connection the API dropped, a nanosecond short of the deadline.
		AtomicLong clock = new AtomicLong();
		IOException closed = new IOException("closed");
		InputStream body = failingAfter("abc", closed, Duration.ofSeconds(20), clock).getBody();
		assertThat(body.read(new byte[3], 0, 3)).isEqualTo(3);
		clock.addAndGet(Duration.ofSeconds(20).toNanos() - 1);

		assertThatIOException().isThrownBy(body::read).isSameAs(closed);
		assertThatIOException().isThrownBy(() -> body.read(new byte[8], 0, 8)).isSameAs(closed);
	}

	@Test
	void read_failingBehindAClientThatAppliesItsDeadlineToEachRead_shouldRaiseTheFailureAsTheClientRaisedIt()
			throws IOException {
		// Such a client raises a timeout of its own, and a body that arrives in pieces
		// can outlast the deadline, so the time the exchange took is left unread.
		AtomicLong clock = new AtomicLong();
		IOException prematureEnd = new IOException("Premature EOF");
		InputStream body = failingAfter("abc", prematureEnd, null, clock).getBody();
		assertThat(body.read(new byte[3], 0, 3)).isEqualTo(3);
		clock.addAndGet(Duration.ofMinutes(5).toNanos());

		assertThatIOException().isThrownBy(body::read).isSameAs(prematureEnd);
	}

	@Test
	void execute_clockReadBeforeTheClientGetsTheRequest_shouldCountTheWaitForTheStatus() throws IOException {
		// The JDK client's deadline runs while the status is awaited, so that wait
		// belongs to the time measured here.
		AtomicLong clock = new AtomicLong();
		IOException closed = new IOException("closed");
		FakeResponse underneath = new FakeResponse(new FailingStream("abc", closed));
		ClientHttpRequestFactory delegate = (uri, method) -> new FakeRequest(underneath) {

			@Override
			public ClientHttpResponse execute() {
				clock.addAndGet(Duration.ofSeconds(20).toNanos());
				return super.execute();
			}
		};
		InputStream body = new BoundedResponseRequestFactory(delegate, DataSize.ofBytes(10), Duration.ofSeconds(20),
				clock::get)
			.createRequest(API, HttpMethod.POST)
			.execute()
			.getBody();
		assertThat(body.read(new byte[3], 0, 3)).isEqualTo(3);

		assertThatExceptionOfType(ReadDeadlinePassedException.class).isThrownBy(body::read);
	}

	private static ClientHttpResponse bounded(String body, DataSize cap) throws IOException {
		ClientHttpRequestFactory delegate = (uri, method) -> new FakeRequest(new FakeResponse(body));
		return new BoundedResponseRequestFactory(delegate, cap).createRequest(API, HttpMethod.POST).execute();
	}

	private static ClientHttpResponse declaring(String body, Consumer<HttpHeaders> headers) throws IOException {
		FakeResponse underneath = new FakeResponse(body);
		headers.accept(underneath.getHeaders());
		ClientHttpRequestFactory delegate = (uri, method) -> new FakeRequest(underneath);
		return new BoundedResponseRequestFactory(delegate, DataSize.ofBytes(10)).createRequest(API, HttpMethod.POST)
			.execute();
	}

	private static ClientHttpResponse failingAfter(String body, IOException failure, Duration exchangeDeadline,
			AtomicLong clock) throws IOException {
		ClientHttpRequestFactory delegate = (uri,
				method) -> new FakeRequest(new FakeResponse(new FailingStream(body, failure)));
		return new BoundedResponseRequestFactory(delegate, DataSize.ofBytes(10), exchangeDeadline, clock::get)
			.createRequest(API, HttpMethod.POST)
			.execute();
	}

	// Hands out its bytes and then fails every read, the way a body does once the
	// connection under it is gone.
	private static final class FailingStream extends InputStream {

		private final InputStream bytes;

		private final IOException failure;

		FailingStream(String body, IOException failure) {
			this.bytes = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
			this.failure = failure;
		}

		@Override
		public int read() throws IOException {
			int value = this.bytes.read();
			if (value == -1) {
				throw this.failure;
			}
			return value;
		}

		@Override
		public int read(byte[] target, int offset, int length) throws IOException {
			int count = this.bytes.read(target, offset, length);
			if (count == -1) {
				throw this.failure;
			}
			return count;
		}

	}

	private static class FakeRequest implements ClientHttpRequest {

		private final ClientHttpResponse response;

		private final HttpHeaders headers = new HttpHeaders();

		private final Map<String, Object> attributes = new HashMap<>();

		FakeRequest(ClientHttpResponse response) {
			this.response = response;
		}

		@Override
		public ClientHttpResponse execute() {
			return this.response;
		}

		@Override
		public OutputStream getBody() {
			return OutputStream.nullOutputStream();
		}

		@Override
		public HttpMethod getMethod() {
			return HttpMethod.POST;
		}

		@Override
		public URI getURI() {
			return API;
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.headers;
		}

		@Override
		public Map<String, Object> getAttributes() {
			return this.attributes;
		}

	}

	private static final class FakeResponse implements ClientHttpResponse {

		private final InputStream body;

		private final HttpHeaders headers = new HttpHeaders();

		private boolean closed;

		FakeResponse(String body) {
			this(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
		}

		FakeResponse(InputStream body) {
			this.body = body;
			this.headers.add("Content-Type", "application/json");
		}

		@Override
		public InputStream getBody() {
			return this.body;
		}

		@Override
		public HttpStatusCode getStatusCode() {
			return HttpStatus.OK;
		}

		@Override
		public String getStatusText() {
			return "OK";
		}

		@Override
		public void close() {
			this.closed = true;
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.headers;
		}

	}

}
