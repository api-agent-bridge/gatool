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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What the model reads when the GraphQL API refuses the call.
 *
 * <p>
 * A 400 or a 404 is the API's verdict on the request, so the model reads the status and
 * the API's own explanation. A connection that did not open is answered with "could not
 * reach the GraphQL API. The call did not run, and a later call may succeed.", because
 * there the call really did not run.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String MUTATION_OPERATIONS = "gatool.mcp.operations.locations=classpath:gatool/mutation/";

	private static final AtomicInteger STATUS = new AtomicInteger(400);

	private static final AtomicReference<String> BODY = new AtomicReference<>("");

	private static final AtomicReference<String> CONTENT_TYPE = new AtomicReference<>("application/json");

	// A Location the answer carries, for the redirect case, and how often the redirect
	// target was reached.
	private static final AtomicReference<String> LOCATION = new AtomicReference<>("");

	private static final AtomicInteger REDIRECT_TARGET_CALLS = new AtomicInteger(0);

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues(SCHEMA);

	@BeforeAll
	static void startRefusingApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// The redirect target is registered first, because the JDK's server matches the
		// longest context path and this one shares a prefix with the API's.
		server.createContext("/graphql/", (exchange) -> {
			REDIRECT_TARGET_CALLS.incrementAndGet();
			byte[] body = "{\"data\":{\"topRatedMovies\":[]}}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/graphql", (exchange) -> {
			byte[] body = BODY.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE.get());
			if (!LOCATION.get().isEmpty()) {
				exchange.getResponseHeaders().add("Location", LOCATION.get());
			}
			// A length of zero means chunked encoding to the JDK's server; minus one
			// leaves the body out, which is what an empty answer from a proxy looks like.
			exchange.sendResponseHeaders(STATUS.get(), (body.length != 0) ? body.length : -1);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopRefusingApi() {
		server.stop(0);
	}

	@Test
	void call_apiAnswers400_shouldCarryTheStatusAndTheApisOwnWords() {
		STATUS.set(400);
		BODY.set("{\"message\":\"first must be between 1 and 100\"}");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("400")
			.contains("first must be between 1 and 100")
			// A 4xx is the API's own verdict on this request, so repeating it
			// unchanged gets the same answer.
			.contains("change the arguments or the operation")
			.doesNotContain("a later call may succeed");
	}

	@Test
	void call_apiAnswers308_shouldReportTheRedirectAndLeaveItUnfollowed(CapturedOutput output) {
		// Under the application's redirect default the JDK would follow the 308 itself,
		// and on a 301, 302 or 303 it would turn the POST into a GET without a body. The
		// URL is the operator's to correct, so the call reports the status and the log
		// names the Location.
		STATUS.set(308);
		BODY.set("");
		LOCATION.set("/graphql/");
		REDIRECT_TARGET_CALLS.set(0);

		ToolCallOutcome outcome;
		try {
			outcome = callTool();
		}
		finally {
			LOCATION.set("");
		}

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("308")
			.contains("a redirect")
			.contains("gatool.api.url")
			.doesNotContain("change the arguments or the operation");
		assertThat(REDIRECT_TARGET_CALLS.get()).isZero();
		assertThat(output.getAll()).contains("WARN")
			.contains("answered 308, a redirect to http://127.0.0.1:")
			.contains("/graphql/");
	}

	@Test
	void call_apiAnswers503_shouldSayThatWaitingHelps() {
		STATUS.set(503);
		BODY.set("upstream unavailable");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("503")
			.contains("upstream unavailable")
			.contains("A later call may succeed");
	}

	@ParameterizedTest
	@ValueSource(ints = { 408, 425, 429 })
	void call_apiAnswersAStatusThatInvitesTheSameCallAgain_shouldSayThatWaitingHelps(int status) {
		// Request Timeout, Too Early and Too Many Requests each ask for the same request
		// again, so the model reads what it reads for a 503.
		STATUS.set(status);
		BODY.set("{\"message\":\"try again\"}");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("answered " + status)
			.contains("try again")
			.contains("A later call may succeed")
			.doesNotContain("change the arguments or the operation");
	}

	@Test
	void call_apiAnswers404WithAnEmptyBody_shouldStillNameTheStatus() {
		STATUS.set(404);
		BODY.set("");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("404").contains("topRatedMovies");
	}

	@Test
	void call_apiAnswersALongErrorPage_shouldCutTheBodyItPutsInFrontOfTheModel() {
		STATUS.set(500);
		BODY.set("<html>" + "y".repeat(50_000) + "</html>");

		ToolCallOutcome outcome = callTool();

		// Whatever the API sends, the model's context holds at most a couple of
		// thousand characters of it.
		assertThat(outcome.text()).hasSizeLessThan(2500);
	}

	@Test
	void call_proxyAnswers200WithASignInPage_shouldTellTheModelTheCallRan() {
		// UnknownContentTypeException extends RestClientException directly, as a sibling
		// of RestClientResponseException, so a 2xx whose body the converters cannot read
		// has to be routed to the refusal path separately, and the model reads that the
		// call ran.
		STATUS.set(200);
		CONTENT_TYPE.set("text/html");
		BODY.set("<html><body>Sign in to continue</body></html>");

		ToolCallOutcome outcome = callTool();
		CONTENT_TYPE.set("application/json");

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("200")
			.contains("text/html")
			.contains("Sign in to continue")
			.contains("different arguments will not help")
			.doesNotContain("The call did not run")
			.doesNotContain("A later call may succeed");
	}

	@Test
	void call_apiAnswers200AsPlainText_shouldSayWhatCameBackInstead() {
		STATUS.set(200);
		CONTENT_TYPE.set("text/plain");
		BODY.set("service temporarily routed to maintenance");

		ToolCallOutcome outcome = callTool();
		CONTENT_TYPE.set("application/json");

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("text/plain")
			.contains("service temporarily routed to maintenance")
			.contains("does not carry a GraphQL response");
	}

	@Test
	void call_apiAnswers200WithABodyThatIsNotJson_shouldTellTheModelTheCallRanAndTheAnswerIsNotGraphQl() {
		// The reader's failure leaves RestClient as a plain RestClientException, the base
		// type a transport failure shares, and it is still answered as a refusal: the
		// call ran, and the same call gets the same body.
		STATUS.set(200);
		BODY.set("service temporarily routed to maintenance");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("200")
			.contains("a body that is not a GraphQL response")
			.contains("The same call will get the same answer")
			.doesNotContain("could not reach")
			.doesNotContain("The call did not run");
	}

	@Test
	void call_apiAnswers200WithAnEmptyBody_shouldNameTheStatusAndTheEmptyBody() {
		// An empty body is answered with the status and a sentence, where an empty
		// response map would reach the model as the text "{}".
		STATUS.set(200);
		BODY.set("");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("topRatedMovies")
			.contains("answered 200 with an empty body")
			.contains("different arguments will not help")
			.doesNotContain("{}");
	}

	@Test
	void call_apiAnswers401_shouldSayTheCredentialWasRefusedAndWarnTheOperator(CapturedOutput output) {
		// A refused credential is the operator's to fix, so the model reads that the
		// arguments are not the cause and the log carries one WARN line naming the
		// status.
		STATUS.set(401);
		BODY.set("{\"message\":\"invalid token\"}");

		ToolCallOutcome outcome = callTool();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("401")
			.contains("invalid token")
			.contains("The credential GATool sends was refused, so the arguments are not the cause")
			.doesNotContain("change the arguments or the operation");
		assertThat(output.getAll()).contains("WARN")
			.contains("answered 401, which refuses the credential GATool sends");
	}

	@Test
	void call_apiOnAClosedPort_shouldKeepTheTransportSentence() {
		// A connection that did not open is the case the transport sentence is for: the
		// call did not run, and a later one may well succeed.
		this.contextRunner.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql").run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

			assertThatExceptionOfType(ToolCallFailedException.class).isThrownBy(() -> tool.call(Map.of()))
				.withMessage("The tool topRatedMovies could not reach "
						+ "the GraphQL API. The call did not run, and a later call may succeed.");
		});
	}

	@Test
	void call_apiAnswersGraphQlErrorsWithAnErrorStatus_shouldStillReachTheModelAsAGraphQlResponse() {
		STATUS.set(400);
		CONTENT_TYPE.set("application/graphql-response+json");
		BODY.set("{\"errors\":[{\"message\":\"Validation error: field topRatedMovies is unknown\"}]}");

		ToolCallOutcome outcome = callTool();
		CONTENT_TYPE.set("application/json");

		// Spring for GraphQL parses this one, so the model reads the GraphQL errors
		// themselves, which is the richer answer of the two.
		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("field topRatedMovies is unknown");
	}

	@Test
	void call_apiRefusesAMutation_shouldSayTheWriteMayHaveBeenAppliedAlready() {
		// The highest-consequence sentence GATool writes. A status the API answers after
		// reading the request does not say whether the write went through, so a model
		// told only that a later call may succeed would apply it twice.
		STATUS.set(503);
		BODY.set("{\"message\":\"the review service is restarting\"}");

		ToolCallOutcome outcome = callMutation();

		assertThat(outcome.isError()).isTrue();
		assertThat(outcome.text()).contains("503")
			.contains("A later call may succeed, and this tool writes, so the first call may have been "
					+ "applied already. Read the current state before you send it again.");
	}

	@Test
	void call_mutationAgainstAClosedPort_shouldSayTheToolCouldNotReachTheApiAndAskForTheCurrentState() {
		// A refused connection did not carry the request. The model is still asked for
		// the current state, as after every failure of a write.
		// A call that did not reach the API raises, and each adapter turns that into the
		// tool error its protocol carries, so the sentence is asserted where it is built.
		this.contextRunner.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", MUTATION_OPERATIONS)
			.run((context) -> assertThatExceptionOfType(ToolCallFailedException.class)
				.isThrownBy(() -> mutationTool(context).call(Map.of("input", Map.of("movieId", "movie-1", "score", 9))))
				.withMessage("The tool addReview could not reach the GraphQL API. This tool writes, so read "
						+ "the current state before you send it again."));
	}

	private ToolCallOutcome callMutation() {
		AtomicReference<ToolCallOutcome> outcome = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty(), MUTATION_OPERATIONS)
			.run((context) -> outcome
				.set(mutationTool(context).call(Map.of("input", Map.of("movieId", "movie-1", "score", 9)))));
		return outcome.get();
	}

	private static GATool mutationTool(AssertableApplicationContext context) {
		return context.getBean(GAToolCatalog.class)
			.mcpTools()
			.stream()
			.filter((tool) -> "addReview".equals(tool.name()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the mutation fixture publishes addReview"));
	}

	private ToolCallOutcome callTool() {
		AtomicReference<ToolCallOutcome> outcome = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			outcome.set(tool.call(Map.of()));
		});
		return outcome.get();
	}

	private static String apiUrlProperty() {
		return "gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

}
