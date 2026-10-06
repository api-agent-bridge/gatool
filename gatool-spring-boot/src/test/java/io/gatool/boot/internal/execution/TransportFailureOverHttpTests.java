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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.core.internal.operation.OperationType;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.operation.ToolOperation;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * What a model reads when a call fails in transport, over the two HTTP clients this
 * classpath builds: the JDK client, which the starters ship, and the client on
 * {@code HttpURLConnection}. Each throws its own exceptions for the same failure, and the
 * runner reads the sentence from them. Each client is built the way the
 * auto-configuration builds it, through {@link RemoteHttpClients}, so the request factory
 * GATool wraps around the client is in the path.
 *
 * <p>
 * The API is a stub on a raw socket, because the JDK's HTTP server cannot stay silent
 * after a request, close a connection without an answer, or stall inside a body.
 */
class TransportFailureOverHttpTests {

	private enum Client {

		JDK, URL_CONNECTION

	}

	private enum Answer {

		SILENCE, END_THE_HANDSHAKE, CLOSE_WITHOUT_AN_ANSWER, STALL_AHEAD_OF_THE_BODY, STALL_INSIDE_THE_BODY,
		DROP_INSIDE_THE_BODY, DROP_INSIDE_A_BODY_OF_DECLARED_LENGTH, DROP_AFTER_A_SLOW_BODY, PAUSE_INSIDE_THE_BODY

	}

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

	private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

	// For the answers that end well inside the deadline. The first exchange of a JVM
	// loads the client's classes, which can take a large part of half a second.
	private static final Duration DISTANT_READ_TIMEOUT = Duration.ofSeconds(5);

	private static final Duration SLOW_BODY_READ_TIMEOUT = Duration.ofSeconds(1);

	// Five pieces, one every quarter of a second, so each wait for data ends well
	// inside SLOW_BODY_READ_TIMEOUT and the body as a whole outlasts it.
	private static final int SLOW_BODY_PIECES = 5;

	private static final long SLOW_BODY_INTERVAL_MILLIS = 250;

	private static final long PAUSE_MILLIS = 400;

	private static final String TOOK_LONGER = "The tool topRatedMovies called the GraphQL API, and the API took "
			+ "longer to answer than the read timeout of 500 milliseconds, which spring.http.clients.read-timeout "
			+ "sets. The same call is likely to take as long again, so ask for less: fewer fields or a smaller "
			+ "page.";

	private static final String DID_NOT_RUN = "The tool topRatedMovies could not reach the GraphQL API. The call did "
			+ "not run, and a later call may succeed.";

	private static final String CANNOT_TELL = "The tool topRatedMovies failed in transport while calling the "
			+ "GraphQL API. GATool cannot tell whether the call ran, and a later call may succeed.";

	private static final String PARTIAL_BODY = "{\"data\":{\"topRatedMovies\":[{\"id\":\"m1\",\"title\":\"Sig";

	private static final String WHOLE_BODY = PARTIAL_BODY + "nal from Kepler\"}]}}";

	private static final Pattern CONTENT_LENGTH = Pattern.compile("(?i)content-length:\\s*(\\d+)");

	private final AtomicReference<Answer> answer = new AtomicReference<>(Answer.SILENCE);

	private final CountDownLatch release = new CountDownLatch(1);

	// A port without a listener. Port 1 sits below the range the operating system assigns
	// from, so a call to it is refused every time. The port of a stub that was closed can
	// be handed out again before the call connects, and the call then waits for the read
	// timeout.
	private static final int CLOSED_PORT = 1;

	private ServerSocket stub;

	private int port;

	@BeforeEach
	void startApi() throws IOException {
		this.stub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		this.port = this.stub.getLocalPort();
		Thread acceptor = new Thread(this::acceptCalls, "transport-failure-stub");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	@AfterEach
	void stopApi() throws IOException {
		this.release.countDown();
		this.stub.close();
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiSilentPastTheReadTimeout_query_shouldSayTheApiTookLongerAndAskForLess(Client client) {
		// The API received the request and was still working when the client stopped
		// waiting, so the call may have run and the same call is likely to take as long.
		this.answer.set(Answer.SILENCE);

		ToolCallFailedException failure = callExpectingFailure(client, "http", query());

		assertThat(failure.getMessage()).isEqualTo(TOOK_LONGER);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiSilentPastTheReadTimeout_mutation_shouldSayTheApiTookLongerAndThatTheWriteMayHaveRun(Client client) {
		// The API had the request when the client stopped waiting.
		this.answer.set(Answer.SILENCE);

		ToolCallFailedException failure = callExpectingFailure(client, "http", mutation());

		assertThat(failure.getMessage()).isEqualTo("The tool addReview called the GraphQL API, and the API took "
				+ "longer to answer than the read timeout of 500 milliseconds, which "
				+ "spring.http.clients.read-timeout sets. This tool writes, and the write may have run, so read "
				+ "the current state before you send it again.");
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiOnAClosedPort_mutation_shouldSayTheToolCouldNotReachTheApi(Client client) {
		this.port = CLOSED_PORT;

		ToolCallFailedException failure = callExpectingFailure(client, "http", mutation());

		assertThat(failure.getMessage()).isEqualTo("The tool addReview could not reach the GraphQL API. This tool "
				+ "writes, so read the current state before you send it again.");
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiClosingTheConnectionWithoutAnAnswer_mutation_shouldSayItCannotTellWhetherTheWriteRan(Client client) {
		// The request was sent and the connection closed before a status came back, so
		// the API may have carried the write out.
		this.answer.set(Answer.CLOSE_WITHOUT_AN_ANSWER);

		ToolCallFailedException failure = callExpectingFailure(client, "http", mutation());

		assertThat(failure.getMessage()).isEqualTo("The tool addReview failed in transport while calling the "
				+ "GraphQL API. This tool writes, and GATool cannot tell whether the write ran, so read the "
				+ "current state before you send it again.");
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiOnAClosedPort_query_shouldSayTheCallDidNotRun(Client client) {
		// A refused connection did not carry the request.
		this.port = CLOSED_PORT;

		ToolCallFailedException failure = callExpectingFailure(client, "http", query());

		assertThat(failure.getMessage()).isEqualTo(DID_NOT_RUN);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiEndingTheTlsHandshake_query_shouldSayTheCallDidNotRun(Client client) {
		// The handshake comes before the request, so a call that failed inside it did
		// not send one.
		this.answer.set(Answer.END_THE_HANDSHAKE);

		ToolCallFailedException failure = callExpectingFailure(client, "https", query());

		assertThat(failure.getMessage()).isEqualTo(DID_NOT_RUN);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiClosingTheConnectionWithoutAnAnswer_query_shouldSayItCannotTellWhetherTheCallRan(Client client) {
		// The request was sent and the connection closed before a status came back, so
		// the API may have run the call.
		this.answer.set(Answer.CLOSE_WITHOUT_AN_ANSWER);

		ToolCallFailedException failure = callExpectingFailure(client, "http", query());

		assertThat(failure.getMessage()).isEqualTo(CANNOT_TELL);
	}

	@Test
	void run_apiStallingInsideTheBody_overUrlConnection_shouldSayTheApiTookLonger() {
		// This client applies the read timeout to each wait for data, and the reader's
		// failure carries the SocketTimeoutException the socket raised.
		this.answer.set(Answer.STALL_INSIDE_THE_BODY);

		ToolCallFailedException failure = callExpectingFailure(Client.URL_CONNECTION, "http", query());

		assertThat(failure.getMessage()).isEqualTo(TOOK_LONGER);
	}

	@Test
	void run_apiStallingInsideTheBody_overTheJdkClient_shouldSayTheApiTookLonger() {
		// Spring's JDK request closes the body when its deadline passes, and the reader
		// then fails with a plain IOException, the same one a dropped connection leaves.
		// Read as a dropped connection, the failure would invite a second wait of the
		// same length.
		this.answer.set(Answer.STALL_INSIDE_THE_BODY);

		ToolCallFailedException failure = callExpectingFailure(Client.JDK, "http", query());

		assertThat(failure.getMessage()).isEqualTo(TOOK_LONGER);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiStallingAheadOfTheFirstByteOfTheBody_query_shouldSayTheApiTookLonger(Client client) {
		// The status and the headers arrived and the body did not begin. GATool reads
		// the first byte itself, to tell an empty body apart, so this read fails ahead
		// of the JSON reader and Spring wraps it as it wraps a failure to connect.
		this.answer.set(Answer.STALL_AHEAD_OF_THE_BODY);

		ToolCallFailedException failure = callExpectingFailure(client, "http", query());

		assertThat(failure.getMessage()).isEqualTo(TOOK_LONGER);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiDroppingTheConnectionInsideTheBody_query_shouldSayItCannotTellWhetherTheCallRan(Client client) {
		// The API closed the connection well inside the deadline, so the deadline is not
		// what ended the call, and a later call may succeed.
		this.answer.set(Answer.DROP_INSIDE_THE_BODY);

		ToolCallFailedException failure = callExpectingFailure(client, "http", query(), DISTANT_READ_TIMEOUT);

		assertThat(failure.getMessage()).isEqualTo(CANNOT_TELL);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiDroppingTheConnectionInsideABodyOfDeclaredLength_query_shouldSayItCannotTellWhetherTheCallRan(
			Client client) {
		// The answer declares its length and the connection closes short of it. On Java
		// 21 HttpURLConnection hands such a body out as one that ended, so the JSON
		// reader would meet the end of the input inside a value, and the model would read
		// that the API answered with a body that is not a GraphQL response and that the
		// same call gets the same answer.
		this.answer.set(Answer.DROP_INSIDE_A_BODY_OF_DECLARED_LENGTH);

		ToolCallFailedException failure = callExpectingFailure(client, "http", query(), DISTANT_READ_TIMEOUT);

		assertThat(failure.getMessage()).isEqualTo(CANNOT_TELL);
	}

	@Test
	void run_apiDroppingTheConnectionAfterABodySlowerThanTheReadTimeout_overUrlConnection_shouldSayItCannotTell() {
		// This client applies the read timeout to each wait for data, so a body that
		// arrives in pieces can outlast the timeout as a whole. A connection that drops
		// after that was ended by the API, and the time the call took does not make it a
		// read timeout.
		this.answer.set(Answer.DROP_AFTER_A_SLOW_BODY);
		long startedAt = System.nanoTime();

		ToolCallFailedException failure = callExpectingFailure(Client.URL_CONNECTION, "http", query(),
				SLOW_BODY_READ_TIMEOUT);

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).as("the body outlasted the read timeout")
			.isGreaterThan(SLOW_BODY_READ_TIMEOUT);
		assertThat(failure.getMessage()).isEqualTo(CANNOT_TELL);
	}

	@ParameterizedTest
	@EnumSource(Client.class)
	void run_apiPausingInsideTheBodyAndFinishingInsideTheDeadline_query_shouldReturnTheResult(Client client) {
		this.answer.set(Answer.PAUSE_INSIDE_THE_BODY);

		ToolCallOutcome outcome = runner(client, "http", DISTANT_READ_TIMEOUT).run(query(), Map.of());

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.kind()).isEqualTo(ToolCallOutcome.Kind.SUCCESS);
		assertThat(outcome.text()).contains("Signal from Kepler");
	}

	private ToolCallFailedException callExpectingFailure(Client client, String scheme, ToolOperation operation) {
		return callExpectingFailure(client, scheme, operation, READ_TIMEOUT);
	}

	private ToolCallFailedException callExpectingFailure(Client client, String scheme, ToolOperation operation,
			Duration readTimeout) {
		ToolCallRunner runner = runner(client, scheme, readTimeout);
		ToolCallFailedException failure = catchThrowableOfType(ToolCallFailedException.class,
				() -> runner.run(operation, Map.of()));
		assertThat(failure).as("the call fails the way a transport failure fails").isNotNull();
		return failure;
	}

	// The client and the runner as the auto-configuration builds them: the request
	// factory comes from RemoteHttpClients, and the runner names the deadline those
	// clients apply.
	private ToolCallRunner runner(Client client, String scheme, Duration readTimeout) {
		ClientHttpRequestFactoryBuilder<?> builder = (client == Client.JDK) ? ClientHttpRequestFactoryBuilder.jdk()
				: ClientHttpRequestFactoryBuilder.simple();
		HttpClientSettings settings = HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, readTimeout);
		RemoteHttpClients clients = new RemoteHttpClients(RestClient.builder(), settings, builder);
		RestClient.Builder restClient = Objects.requireNonNull(clients.apiClientBuilder(DataSize.ofMegabytes(1)));
		String url = scheme + "://127.0.0.1:" + this.port + "/graphql";
		return ToolCallRunner
			.builder(new RemoteGraphQlExecutor(restClient, url, null),
					new GraphQlResultWriter(JsonMapper.builder().build()))
			.maxCharacters(60000)
			.readTimeout(clients.appliedReadTimeout())
			.build();
	}

	private void acceptCalls() {
		while (!this.stub.isClosed()) {
			try {
				Socket call = this.stub.accept();
				Thread answering = new Thread(() -> answer(call), "transport-failure-answer");
				answering.setDaemon(true);
				answering.start();
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	// Ends a TLS handshake, or reads the request and then stays silent, closes the
	// connection, or answers 200 with the body it declared held back, as a whole or in
	// part, for good or for a pause. The handshake ends after the client's first
	// message was read, because a socket closed over unread bytes resets the
	// connection, and the client then reads a reset where this test wants the end of
	// the stream. A body that is dropped is sent in chunks: HttpURLConnection on Java 21
	// reads a body that ends short of its declared length as a body that ended, and
	// both clients on every Java version notice a chunked body without its last chunk.
	private void answer(Socket call) {
		try (call) {
			call.setSoTimeout(10_000);
			Answer mode = this.answer.get();
			InputStream in = call.getInputStream();
			if (mode == Answer.END_THE_HANDSHAKE) {
				in.read(new byte[16_384]);
				return;
			}
			Matcher length = CONTENT_LENGTH.matcher(readHead(in));
			if (length.find()) {
				in.readNBytes(Integer.parseInt(length.group(1)));
			}
			if (mode == Answer.CLOSE_WITHOUT_AN_ANSWER) {
				return;
			}
			OutputStream out = call.getOutputStream();
			if (mode == Answer.STALL_AHEAD_OF_THE_BODY || mode == Answer.STALL_INSIDE_THE_BODY) {
				send(out,
						"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
								+ (PARTIAL_BODY.length() + 200) + "\r\n\r\n"
								+ ((mode == Answer.STALL_INSIDE_THE_BODY) ? PARTIAL_BODY : ""));
			}
			if (mode == Answer.DROP_INSIDE_THE_BODY || mode == Answer.DROP_AFTER_A_SLOW_BODY) {
				send(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked" + "\r\n\r\n"
						+ chunk(PARTIAL_BODY));
				for (int piece = 0; mode == Answer.DROP_AFTER_A_SLOW_BODY && piece < SLOW_BODY_PIECES; piece++) {
					TimeUnit.MILLISECONDS.sleep(SLOW_BODY_INTERVAL_MILLIS);
					send(out, chunk("n"));
				}
				return;
			}
			if (mode == Answer.DROP_INSIDE_A_BODY_OF_DECLARED_LENGTH) {
				send(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + WHOLE_BODY.length()
						+ "\r\n\r\n" + PARTIAL_BODY);
				return;
			}
			if (mode == Answer.PAUSE_INSIDE_THE_BODY) {
				send(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + WHOLE_BODY.length()
						+ "\r\n\r\n" + PARTIAL_BODY);
				TimeUnit.MILLISECONDS.sleep(PAUSE_MILLIS);
				send(out, WHOLE_BODY.substring(PARTIAL_BODY.length()));
				return;
			}
			this.release.await(30, TimeUnit.SECONDS);
		}
		catch (IOException ex) {
			// The client closed first, which ends this answer as well.
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void send(OutputStream out, String text) throws IOException {
		out.write(text.getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	private static String chunk(String data) {
		return Integer.toHexString(data.length()) + "\r\n" + data + "\r\n";
	}

	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		int last = 0;
		int value;
		while ((value = in.read()) != -1) {
			head.write(value);
			last = (last << 8) | value;
			if (last == 0x0D0A0D0A) {
				break;
			}
		}
		return head.toString(StandardCharsets.ISO_8859_1);
	}

	private static ToolOperation query() {
		return operation("topRatedMovies", "TopRatedMovies", OperationType.QUERY,
				"query TopRatedMovies { topRatedMovies { id } }");
	}

	private static ToolOperation mutation() {
		return operation("addReview", "AddReview", OperationType.MUTATION,
				"mutation AddReview { addReview(input: { movieId: \"movie-1\", score: 9 }) { id } }");
	}

	private static ToolOperation operation(String toolName, String operationName, OperationType type, String document) {
		return ToolOperation.builder()
			.toolName(toolName)
			.operationName(operationName)
			.operationType(type)
			.printedDocument(document)
			.inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
			.location("file:/gatool/mcp/" + operationName + ".graphql")
			.toolExposureTypes(Set.of(ToolExposureType.MCP))
			.build();
	}

}
