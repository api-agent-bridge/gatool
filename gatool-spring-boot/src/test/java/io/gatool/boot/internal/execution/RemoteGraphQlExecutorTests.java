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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.GraphQlExecutionRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

// The JDK's own HTTP server answers the call, so the assertion reads the header that
// left the process. A mocked RestClient would assert what the test itself arranged.
class RemoteGraphQlExecutorTests {

	private static final String DOCUMENT = "query TopRatedMovies { topRatedMovies { id } }";

	private static final String RESPONSE = "{\"data\":{\"topRatedMovies\":[]}}";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final List<String> apiKeyHeaders = new CopyOnWriteArrayList<>();

	private final AtomicReference<String> body = new AtomicReference<>(RESPONSE);

	private final AtomicReference<Integer> status = new AtomicReference<>(200);

	private final AtomicReference<String> contentType = new AtomicReference<>("application/json");

	private HttpServer server;

	@BeforeEach
	void startApi() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/graphql", this::answer);
		this.server.start();
	}

	@AfterEach
	void stopApi() {
		this.server.stop(0);
	}

	@Test
	void execute_staticHeaderCredential_shouldSendTheHeaderToTheApi() {
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(),
				new StaticHeaderCredentialStrategy("X-API-Key", "movies-key"));

		GraphQlResponse response = executor.execute(request());

		assertThat(response.isValid()).isTrue();
		assertThat(this.apiKeyHeaders).containsExactly("movies-key");
	}

	@Test
	void execute_withoutACredential_shouldCallTheApiWithoutTheHeader() {
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		GraphQlResponse response = executor.execute(request());

		assertThat(response.isValid()).isTrue();
		assertThat(this.apiKeyHeaders).isEmpty();
	}

	@Test
	void execute_apiAnswering200WithAnEmptyBody_shouldRaiseARefusalNamingTheStatus() {
		// The transport maps an empty body to an empty response map, so the status is
		// known to the interceptor alone, which is where the refusal is raised.
		this.body.set("");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		assertThatExceptionOfType(ApiRefusedException.class).isThrownBy(() -> executor.execute(request()))
			.satisfies((refusal) -> {
				assertThat(refusal.shape()).isEqualTo(ApiRefusedException.Shape.EMPTY_BODY);
				assertThat(refusal.status()).isEqualTo(200);
				assertThat(refusal.isRetryable()).isFalse();
			});
	}

	@Test
	void execute_apiAnswering200WithABodyThatIsNotJson_shouldRaiseARefusalCarryingTheReadersReason() {
		// RestClient wraps the reader's failure in a plain RestClientException, the base
		// type a transport failure shares. The call ran, so it is a refusal.
		this.body.set("service temporarily routed to maintenance");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		assertThatExceptionOfType(ApiRefusedException.class).isThrownBy(() -> executor.execute(request()))
			.satisfies((refusal) -> {
				assertThat(refusal.shape()).isEqualTo(ApiRefusedException.Shape.UNREADABLE_BODY);
				assertThat(refusal.status()).isEqualTo(200);
				assertThat(refusal.unreadableBodyReason()).contains("service");
			});
	}

	@Test
	void execute_apiAnswering294WithAPartialResult_shouldReturnTheResponseAsItIs() {
		// The GraphQL over HTTP draft asks for 294 Partial Success when both data and
		// errors are present. Spring for GraphQL parses a 200, and a 4xx under the
		// GraphQL response type, so this one leaves the transport as a server error, and
		// the executor parses the body itself.
		this.status.set(294);
		this.contentType.set("application/graphql-response+json");
		this.body.set("{\"data\":{\"topRatedMovies\":[{\"id\":\"m1\"}]},"
				+ "\"errors\":[{\"message\":\"rating unavailable\",\"path\":[\"topRatedMovies\",0,\"rating\"]}]}");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		GraphQlResponse response = executor.execute(request());

		assertThat(response.isValid()).isTrue();
		assertThat(response.getErrors()).singleElement().satisfies((error) -> {
			assertThat(error.getMessage()).isEqualTo("rating unavailable");
			assertThat(error.getPath()).isEqualTo("topRatedMovies[0].rating");
		});
		assertThat(response.toMap()).containsKeys("data", "errors");
	}

	@Test
	void execute_apiAnswering201UnderApplicationJson_shouldReturnTheResponse() {
		this.status.set(201);
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		GraphQlResponse response = executor.execute(request());

		assertThat(response.isValid()).isTrue();
		assertThat(response.getErrors()).isEmpty();
	}

	@Test
	void execute_apiAnswering500UnderTheGraphQlResponseType_shouldStayARefusal() {
		this.status.set(500);
		this.contentType.set("application/graphql-response+json");
		this.body.set("{\"errors\":[{\"message\":\"internal\"}]}");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		assertThatExceptionOfType(ApiRefusedException.class).isThrownBy(() -> executor.execute(request()))
			.satisfies((refusal) -> {
				assertThat(refusal.shape()).isEqualTo(ApiRefusedException.Shape.REFUSING_STATUS);
				assertThat(refusal.status()).isEqualTo(500);
				assertThat(refusal.isRetryable()).isTrue();
			});
	}

	@ParameterizedTest
	@ValueSource(ints = { 408, 425, 429 })
	void execute_apiAnsweringAStatusThatInvitesTheSameRequestAgain_shouldRaiseARetryableRefusal(int invitingStatus) {
		// A 408 says the API gave up waiting for the request, a 425 that it would not
		// risk a request that may be a replay, and a 429 that the caller is over a rate.
		// Each asks for the same request again, so each reads as one that waiting helps.
		this.status.set(invitingStatus);
		this.body.set("{\"message\":\"try again\"}");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		assertThatExceptionOfType(ApiRefusedException.class).isThrownBy(() -> executor.execute(request()))
			.satisfies((refusal) -> {
				assertThat(refusal.shape()).isEqualTo(ApiRefusedException.Shape.REFUSING_STATUS);
				assertThat(refusal.status()).isEqualTo(invitingStatus);
				assertThat(refusal.isRetryable()).as("whether a " + invitingStatus + " is retryable").isTrue();
			});
	}

	@Test
	void execute_apiAnswering202WithHtml_shouldRaiseTheUnreadableContentTypeRefusal() {
		// A 2xx under a content type outside JSON is a page in front of the API, the
		// same answer a 200 text/html is, so it takes the same shape.
		this.status.set(202);
		this.contentType.set("text/html");
		this.body.set("<html>queued</html>");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		assertThatExceptionOfType(ApiRefusedException.class).isThrownBy(() -> executor.execute(request()))
			.satisfies((refusal) -> {
				assertThat(refusal.shape()).isEqualTo(ApiRefusedException.Shape.UNREADABLE_CONTENT_TYPE);
				assertThat(refusal.status()).isEqualTo(202);
				assertThat(refusal.unreadableContentType()).startsWith("text/html");
			});
	}

	@Test
	void execute_apiAnswering200WithAJsonObjectWithoutDataOrErrors_shouldRaiseTheUnreadableBodyRefusal() {
		// GraphQL limits the top level of a response to data, errors and extensions. A
		// JSON object without the first two is a proxy or a REST endpoint answering under
		// the API's own content type.
		this.body.set("{\"message\":\"ok\"}");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		assertThatExceptionOfType(ApiRefusedException.class).isThrownBy(() -> executor.execute(request()))
			.satisfies((refusal) -> {
				assertThat(refusal.shape()).isEqualTo(ApiRefusedException.Shape.UNREADABLE_BODY);
				assertThat(refusal.status()).isEqualTo(200);
				assertThat(refusal.unreadableBodyReason()).contains("lacks both data and errors");
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"data\":null,\"errors\":[{\"code\":\"UNAUTHENTICATED\"}]}",
			"{\"data\":null,\"errors\":[\"Rate limit exceeded\"]}", "{\"data\":null,\"errors\":[429]}",
			"{\"data\":null,\"errors\":[null]}", "{\"data\":null,\"errors\":{\"message\":\"Rate limit exceeded\"}}",
			"{\"data\":null,\"errors\":\"Rate limit exceeded\"}" })
	void execute_apiAnswering200WithErrorsOfAnotherShape_shouldReturnTheResponseAsTheApiWroteIt(String answer) {
		// The GraphQL specification gives errors one shape, a list of objects that each
		// carry a message, and gateways in front of an API write others. Spring for
		// GraphQL reads a 200 and casts the member to a list of maps, so each of these
		// would leave the transport as a ClassCastException or an
		// IllegalArgumentException, and the model would read that the API could not be
		// reached.
		this.body.set(answer);
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		GraphQlResponse response = executor.execute(request());

		assertThat(JSON.writeValueAsString(response.toMap())).isEqualTo(answer);
		assertThat(response.isValid()).isFalse();
		assertThat(response.getErrors()).isNotEmpty();
	}

	@Test
	void execute_apiAnswering422UnderTheGraphQlResponseTypeWithAStringEntry_shouldReturnTheResponse() {
		// Spring for GraphQL reads a 4xx under the GraphQL response type as it reads a
		// 200, so the same entry fails the same cast there.
		this.status.set(422);
		this.contentType.set("application/graphql-response+json");
		this.body.set("{\"errors\":[\"The document names a field the schema lacks.\"]}");
		RemoteGraphQlExecutor executor = new RemoteGraphQlExecutor(RestClient.builder(), url(), null);

		GraphQlResponse response = executor.execute(request());

		assertThat(response.toMap()).containsEntry("errors", List.of("The document names a field the schema lacks."));
		assertThat(response.getErrors()).singleElement()
			.satisfies((error) -> assertThat(error.getMessage())
				.isEqualTo("The document names a field the schema lacks."));
	}

	private void answer(HttpExchange exchange) throws IOException {
		String apiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
		if (apiKey != null) {
			this.apiKeyHeaders.add(apiKey);
		}
		byte[] bytes = this.body.get().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", this.contentType.get());
		// A length of zero means chunked encoding to the JDK's server, which is what a
		// proxy that answers an empty 200 sends as well; minus one leaves the body out.
		exchange.sendResponseHeaders(this.status.get(), (bytes.length != 0) ? bytes.length : -1);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/graphql";
	}

	private static GraphQlExecutionRequest request() {
		return new GraphQlExecutionRequest(DOCUMENT, "TopRatedMovies", Map.of());
	}

}
