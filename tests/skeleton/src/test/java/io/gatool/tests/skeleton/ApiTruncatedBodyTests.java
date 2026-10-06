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

package io.gatool.tests.skeleton;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * An API that answers 200 under a JSON content type, sends part of the body and then
 * drops the connection has failed on the way back, and the next call may well succeed.
 * The runner answers it as a transport failure whose cause does not say how far the call
 * got: the model reads that GATool cannot tell whether the call ran and that a later call
 * may succeed, a mutation carries the warning that the write may have been applied, and
 * the operator reads a WARN line. An API that stalls inside the body past the read
 * timeout is answered as a read timeout: the model reads the deadline and is asked for
 * less, because the same call is likely to take as long again. A body that arrives whole
 * and is malformed JSON is the API's own answer, which the same call gets again, so that
 * one stays a refusal.
 *
 * <p>
 * The stub is a raw socket, because the JDK's HTTP server refuses to close an exchange
 * short of its declared length. It promises 200 more bytes than it sends, and for the
 * control it sends malformed JSON with the length it declares.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiTruncatedBodyTests {

	private enum Mode {

		DROP, STALL, MALFORMED

	}

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String MUTATION_OPERATIONS = "gatool.mcp.operations.locations=classpath:gatool/mutation/";

	private static final String PARTIAL_BODY = "{\"data\":{\"topRatedMovies\":[{\"id\":\"m1\",\"title\":\"Sig";

	// Whole, and broken by the comma before the closing bracket.
	private static final String MALFORMED_BODY = "{\"data\":{\"topRatedMovies\":[{\"id\":\"m1\",\"title\":\"Signal\"},]}}";

	private static final Pattern CONTENT_LENGTH = Pattern.compile("(?i)content-length:\\s*(\\d+)");

	private static final AtomicReference<Mode> MODE = new AtomicReference<>(Mode.DROP);

	private static final AtomicReference<CountDownLatch> RELEASE = new AtomicReference<>(new CountDownLatch(1));

	private static ServerSocket stub;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues(SCHEMA);

	@BeforeAll
	static void startTruncatingApi() throws IOException {
		stub = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread acceptor = new Thread(ApiTruncatedBodyTests::acceptCalls, "truncated-body-stub");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	@AfterAll
	static void stopTruncatingApi() throws IOException {
		RELEASE.get().countDown();
		stub.close();
	}

	@BeforeEach
	void freshLatch() {
		MODE.set(Mode.DROP);
		RELEASE.set(new CountDownLatch(1));
	}

	@Test
	void call_apiDropsTheConnectionMidBody_query_shouldFailAsATransportFailureAndInviteARetry(CapturedOutput output) {
		MODE.set(Mode.DROP);

		ToolCallFailedException failure = callQueryExpectingFailure();

		// Read before the assertion messages below reach the same output.
		List<String> warnings = runnerWarnings(output);
		assertThat(failure.getMessage())
			.contains("The tool topRatedMovies failed in transport while calling the GraphQL API")
			.contains("GATool cannot tell whether the call ran, and a later call may succeed")
			.doesNotContain("could not reach")
			.doesNotContain("did not run")
			.doesNotContain("different arguments will not help");
		assertThat(warnings).as("WARN lines from ToolCallRunner for a dropped body").hasSize(1);
		// The request had left and part of the answer had arrived, so the line says
		// what failed and leaves out the claim that the API was out of reach.
		assertThat(warnings.getFirst())
			.contains("The tool topRatedMovies failed in transport while calling the "
					+ "GraphQL API, and the cause does not say whether the call ran: ")
			.doesNotContain("could not reach");
	}

	@Test
	void call_apiDropsTheConnectionMidBody_mutation_shouldSayTheWriteMayHaveBeenApplied(CapturedOutput output) {
		MODE.set(Mode.DROP);

		ToolCallFailedException failure = callMutationExpectingFailure();

		List<String> warnings = runnerWarnings(output);
		assertThat(failure.getMessage())
			.contains("The tool addReview failed in transport while calling the GraphQL API")
			.contains("GATool cannot tell whether the write ran")
			.contains("read the current state before you send it again")
			.doesNotContain("could not reach");
		assertThat(warnings).hasSize(1);
		assertThat(warnings.getFirst())
			.contains("The tool addReview failed in transport while calling the GraphQL "
					+ "API, and the cause does not say whether the call ran: ")
			.doesNotContain("could not reach");
	}

	@Test
	void call_apiStallsMidBodyPastTheReadTimeout_shouldNameTheReadTimeoutAndAskForLess(CapturedOutput output) {
		MODE.set(Mode.STALL);
		long startedAt = System.nanoTime();

		ToolCallFailedException failure = callQueryExpectingFailure("spring.http.clients.read-timeout=1s");

		long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000;
		RELEASE.get().countDown();
		List<String> warnings = runnerWarnings(output);
		assertThat(waitedMillis).as("the read timeout ended the wait").isLessThan(10_000);
		// Spring's JDK request closes the body when its deadline passes, so the reader
		// fails with the IOException a dropped connection leaves. The model still reads
		// the read timeout sentence, because the one a dropped connection gets invites
		// the same call again.
		assertThat(failure.getMessage()).isEqualTo("The tool topRatedMovies called the GraphQL API, and the API "
				+ "took longer to answer than the read timeout of 1 second, which spring.http.clients.read-timeout "
				+ "sets. The same call is likely to take as long again, so ask for less: fewer fields, or a smaller "
				+ "page with first.");
		assertThat(warnings).hasSize(1);
		assertThat(warnings.getFirst()).contains("The tool topRatedMovies called the GraphQL API, and the API took "
				+ "longer to answer than the read timeout of 1 second, which spring.http.clients.read-timeout sets: ")
			.doesNotContain("could not reach");
	}

	@Test
	void call_apiSendsMalformedJsonWhole_shouldStayARefusalSayingTheSameCallGetsTheSameAnswer(CapturedOutput output) {
		MODE.set(Mode.MALFORMED);

		ToolCallOutcome outcome = callQuery();

		List<String> warnings = runnerWarnings(output);
		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.kind()).isEqualTo(ToolCallOutcome.Kind.API_REFUSED);
		assertThat(outcome.text()).contains("answered 200 with a body that is not a GraphQL response")
			.contains("different arguments will not help")
			.doesNotContain("could not reach");
		// The operator reads the shape of the answer once a minute, and the transport
		// sentence stays out, because the call did run.
		assertThat(warnings).hasSize(1);
		assertThat(warnings.getFirst()).contains("a body that is not a GraphQL response")
			.doesNotContain("could not reach");
	}

	private static List<String> runnerWarnings(CapturedOutput output) {
		return output.getAll()
			.lines()
			.filter((line) -> line.contains("WARN") && line.contains("ToolCallRunner"))
			.toList();
	}

	private ToolCallOutcome callQuery() {
		AtomicReference<ToolCallOutcome> outcome = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			outcome.set(tool.call(Map.of()));
		});
		return outcome.get();
	}

	private ToolCallFailedException callQueryExpectingFailure(String... properties) {
		AtomicReference<ToolCallFailedException> failure = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty()).withPropertyValues(properties).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			failure.set(catchThrowableOfType(ToolCallFailedException.class, () -> tool.call(Map.of())));
		});
		assertThat(failure.get()).as("the call fails the way a transport failure fails").isNotNull();
		return failure.get();
	}

	private ToolCallFailedException callMutationExpectingFailure() {
		AtomicReference<ToolCallFailedException> failure = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty(), MUTATION_OPERATIONS).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class)
				.mcpTools()
				.stream()
				.filter((candidate) -> "addReview".equals(candidate.name()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("the mutation fixture publishes addReview"));
			failure.set(catchThrowableOfType(ToolCallFailedException.class,
					() -> tool.call(Map.of("input", Map.of("movieId", "movie-1", "score", 9)))));
		});
		assertThat(failure.get()).as("the call fails the way a transport failure fails").isNotNull();
		return failure.get();
	}

	private static void acceptCalls() {
		while (!stub.isClosed()) {
			try {
				Socket call = stub.accept();
				Thread answer = new Thread(() -> answer(call), "truncated-body-answer");
				answer.setDaemon(true);
				answer.start();
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	// Reads the request and answers 200 application/json. A drop and a stall promise
	// 200 bytes more than they send, and then close the socket or hold it open until
	// the test releases it. The malformed control sends its whole body under the
	// length it declares.
	private static void answer(Socket call) {
		try (call) {
			call.setSoTimeout(10_000);
			InputStream in = call.getInputStream();
			Matcher length = CONTENT_LENGTH.matcher(readHead(in));
			if (length.find()) {
				in.readNBytes(Integer.parseInt(length.group(1)));
			}
			Mode mode = MODE.get();
			String body = (mode == Mode.MALFORMED) ? MALFORMED_BODY : PARTIAL_BODY;
			int declared = body.length() + ((mode == Mode.MALFORMED) ? 0 : 200);
			OutputStream out = call.getOutputStream();
			out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + declared + "\r\n\r\n"
					+ body)
				.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			if (mode == Mode.STALL) {
				RELEASE.get().await(30, TimeUnit.SECONDS);
			}
		}
		catch (IOException ex) {
			// The client closed first, which ends this answer as well.
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
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

	private static String apiUrlProperty() {
		return "gatool.api.url=http://127.0.0.1:" + stub.getLocalPort() + "/graphql";
	}

}
