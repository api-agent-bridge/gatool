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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-caller session cap of a stateful server.
 *
 * <p>
 * The cap on the whole server counts every session it holds, so one authenticated caller
 * could fill it and leave every other caller with 503. The per-caller cap bounds what one
 * caller takes, and a caller past their own cap reads 429 with the wait, the way the rate
 * limiter refuses.
 *
 * <p>
 * The setup mirrors {@code McpSessionBindingOverHttpTests}: stateful Streamable HTTP, a
 * resource server against {@link LocalIssuer}, and the baseline scope. The per-caller cap
 * is two and the server cap ten, so one caller reaches their own cap while the server has
 * places left. The API URL points at a closed port, because the tests only open sessions.
 * The tests share one server and hold nine of its ten places between them, so a test
 * added here has one place to open a session in.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.mcp.sessions.max-count=10",
				"gatool.mcp.sessions.max-count-per-caller=2" })
class StatefulSessionCapTests {

	private static final String AUDIENCE = "gatool-skeleton";

	private static final List<String> SCOPES = List.of("mcp:tools");

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void initialize_oneAuthenticatedCallerFillingTheCap_shouldStillServeADifferentCaller() {
		String ana = LocalIssuer.get().tokenFor("ana", AUDIENCE, SCOPES);
		String ben = LocalIssuer.get().tokenFor("ben", AUDIENCE, SCOPES);

		ResponseEntity<String> anaFirst = initialize(ana);
		ResponseEntity<String> anaSecond = initialize(ana);
		ResponseEntity<String> benFirst = initialize(ben);

		assertThat(anaFirst.getStatusCode()).as(String.valueOf(anaFirst)).isEqualTo(HttpStatus.OK);
		assertThat(anaFirst.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
		assertThat(anaSecond.getStatusCode()).as(String.valueOf(anaSecond)).isEqualTo(HttpStatus.OK);
		assertThat(anaSecond.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
		// Ana holds every place her own cap allows, and the server cap has eight
		// places left, so ben is served.
		assertThat(benFirst.getStatusCode())
			.as("the first session of a second authenticated caller, after one caller filled the cap: " + benFirst)
			.isEqualTo(HttpStatus.OK);
		assertThat(benFirst.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
	}

	@Test
	void initialize_aCallerPastTheirOwnCap_shouldAnswerTooManyRequestsWithTheWait() {
		String cara = LocalIssuer.get().tokenFor("cara", AUDIENCE, SCOPES);

		initialize(cara);
		initialize(cara);
		ResponseEntity<String> third = initialize(cara);

		assertThat(third.getStatusCode()).as(String.valueOf(third)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
		assertThat(third.getBody()).contains("gatool.mcp.sessions.max-count-per-caller")
			.contains("End one with DELETE");
	}

	@Test
	void initialize_oneCallerOpeningTenSessionsAtOnce_shouldServeTheirCapAndRefuseTheRest() throws Exception {
		// The SDK issues the id inside the call, so the cap has to reserve the place
		// when it decides, or ten openings sent together all pass a count of zero.
		String dan = LocalIssuer.get().tokenFor("dan", AUDIENCE, SCOPES);

		List<HttpStatusCode> statuses = initializeTogether(dan, 10);

		assertThat(statuses).as("ten openings sent together by one caller at a cap of two: " + statuses)
			.containsOnly(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS);
		assertThat(statuses.stream().filter(HttpStatus.OK::equals).count())
			.as("sessions served to one caller at a cap of two: " + statuses)
			.isEqualTo(2);
	}

	@Test
	void initialize_aCallerEndingOneSessionAndOpeningFiveAtOnce_shouldServeTheOnePlaceThatCameFree() throws Exception {
		// A session ended with DELETE gives its place back, and the session the caller
		// still holds keeps counting, so the five openings sent together compete for
		// one place. The caller opens in turn first and once more at the end, because
		// the count has to hold for both ways of arriving.
		String eve = LocalIssuer.get().tokenFor("eve", AUDIENCE, SCOPES);
		ResponseEntity<String> first = initialize(eve);
		ResponseEntity<String> second = initialize(eve);
		ResponseEntity<String> third = initialize(eve);
		assertThat(List.of(first.getStatusCode(), second.getStatusCode(), third.getStatusCode()))
			.containsExactly(HttpStatus.OK, HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS);

		ResponseEntity<String> ended = end(eve, first.getHeaders().getFirst("Mcp-Session-Id"));
		List<HttpStatusCode> statuses = initializeTogether(eve, 5);
		ResponseEntity<String> last = initialize(eve);

		assertThat(ended.getStatusCode()).as(String.valueOf(ended)).isEqualTo(HttpStatus.OK);
		assertThat(statuses).as("five openings sent together by a caller with one place free: " + statuses)
			.containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS,
					HttpStatus.TOO_MANY_REQUESTS, HttpStatus.TOO_MANY_REQUESTS);
		assertThat(last.getStatusCode()).as(String.valueOf(last)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	private List<HttpStatusCode> initializeTogether(String token, int count) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<ResponseEntity<String>>> openings = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				openings.add(pool.submit(() -> {
					go.await();
					return initialize(token);
				}));
			}
			go.countDown();
			List<HttpStatusCode> statuses = new ArrayList<>();
			for (Future<ResponseEntity<String>> opening : openings) {
				statuses.add(opening.get(30, TimeUnit.SECONDS).getStatusCode());
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private ResponseEntity<String> end(String token, String sessionId) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.delete()
			.uri("/mcp")
			.header("Mcp-Session-Id", sessionId)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private ResponseEntity<String> initialize(String token) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.body(INITIALIZE)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	/**
	 * The per-caller cap at its default of 100, on a secured stateful server that leaves
	 * gatool.mcp.sessions.max-count-per-caller unset. The server cap stays at its own
	 * default of 1000, so the per-caller cap is what the 101st session meets.
	 */
	@Nested
	@SpringBootTest(classes = StatefulSessionCapTests.SkeletonApplication.class,
			webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "spring.ai.mcp.server.protocol=STREAMABLE", "gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
					"spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE,
					"gatool.mcp.security.baseline-scopes=mcp:tools" })
	class TheDefaultCap {

		@LocalServerPort
		private int port;

		private RestClient client;

		@DynamicPropertySource
		static void issuer(DynamicPropertyRegistry registry) {
			registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		}

		@BeforeEach
		void buildTheClient() {
			this.client = RestClient.builder().baseUrl("http://127.0.0.1:" + this.port).build();
		}

		@Test
		void initialize_the101stSessionOfOneCallerUnderTheDefaults_shouldAnswer429AndServeAnotherCaller() {
			String fay = LocalIssuer.get().tokenFor("fay", AUDIENCE, SCOPES);
			for (int session = 0; session < 100; session++) {
				ResponseEntity<String> opened = initialize(fay);
				assertThat(opened.getStatusCode())
					.as("session " + session + " of one caller under the default per-caller cap: " + opened)
					.isEqualTo(HttpStatus.OK);
			}

			ResponseEntity<String> pastTheCap = initialize(fay);

			assertThat(pastTheCap.getStatusCode()).as(String.valueOf(pastTheCap))
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
			assertThat(pastTheCap.getBody()).contains("gatool.mcp.sessions.max-count-per-caller");

			String gil = LocalIssuer.get().tokenFor("gil", AUDIENCE, SCOPES);
			ResponseEntity<String> gilFirst = initialize(gil);

			assertThat(gilFirst.getStatusCode())
				.as("a different caller, after one caller filled the default per-caller cap: " + gilFirst)
				.isEqualTo(HttpStatus.OK);
		}

		private ResponseEntity<String> initialize(String token) {
			return this.client.post()
				.uri("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.header("MCP-Protocol-Version", "2025-11-25")
				.headers((headers) -> headers.setBearerAuth(token))
				.body(INITIALIZE)
				.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
						reply.getStatusCode()));
		}

	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
