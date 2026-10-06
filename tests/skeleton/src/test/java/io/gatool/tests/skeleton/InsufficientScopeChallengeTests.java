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

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * the 403 challenge and the step-up loop guard of the MCP TypeScript SDK.
 *
 * <p>
 * That client remembers the {@code WWW-Authenticate} value of the last 403 and stops when
 * the next one repeats it, because a repeated challenge means the step-up asked for a
 * scope the authorization server declined to grant. {@code McpBearerChallenges.deny} puts
 * a fresh correlation id into {@code error_description} on every 403, so two refusals of
 * the same caller for the same missing scope carry different values, and the guard sees a
 * new challenge each time.
 *
 * <p>
 * The second test asks what the rate limiter counts. A call refused by the authorization
 * manager is answered inside Spring Security's filter chain, and the limiter sits in the
 * tool specification behind it, so the limit may stay out of reach of a caller that keeps
 * looping.
 *
 * <p>
 * The setup follows {@code McpToolScopesOverHttpTests}: a resource server against
 * {@link LocalIssuer}, the scoped operation files, and the movie API running so a call
 * the limiter lets through answers with data. The limit is one call per minute, the
 * smallest value the settings accept, and each test uses a subject of its own, because
 * the limiter counts per caller and tool.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.mcp.rate-limit.calls-per-minute=1" })
class InsufficientScopeChallengeTests {

	private static final String AUDIENCE = "gatool-skeleton";

	private static final List<String> BASELINE = List.of("mcp:tools");

	private static final List<String> WITH_THE_TOOL_SCOPE = List.of("mcp:tools", "movies:read");

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void challenge_twoRefusalsOfOneCallerForOneMissingScope_shouldRepeatTheSameHeader() {
		String token = LocalIssuer.get().tokenFor("agent-challenge", AUDIENCE, BASELINE);

		ResponseEntity<String> first = post(token, callBody("topRatedMovies"));
		ResponseEntity<String> second = post(token, callBody("topRatedMovies"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(challenge(second))
			.as("the second challenge for the same caller and the same missing scope, which the "
					+ "TypeScript SDK compares with the first to end a step-up loop")
			.isEqualTo(challenge(first));
	}

	@Test
	void rateLimit_callsRefusedForAMissingScope_shouldLeaveTheCallersBudgetAlone() {
		// The limiter bounds the work a caller sends to the GraphQL API, and a call
		// refused for a missing scope does not reach it, so it leaves the caller's
		// budget alone. That is the decision, and the cost of the other choice is what
		// pins it here: a caller whose token briefly lacks a scope, during a step-up,
		// would otherwise spend their minute on refusals and meet the limit on the call
		// that would have worked. Bounding requests to the endpoint itself belongs to a
		// gateway.
		String withoutTheScope = LocalIssuer.get().tokenFor("agent-limit", AUDIENCE, BASELINE);
		String withTheScope = LocalIssuer.get().tokenFor("agent-limit", AUDIENCE, WITH_THE_TOOL_SCOPE);

		for (int attempt = 0; attempt < 3; attempt++) {
			assertThat(post(withoutTheScope, callBody("topRatedMovies")).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN);
		}
		ResponseEntity<String> fourth = post(withTheScope, callBody("topRatedMovies"));

		assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fourth.getBody())
			.as("the fourth call of one caller within a minute, after three calls refused for a missing scope")
			.doesNotContain("Rate limit reached");
	}

	private static String callBody(String tool) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":{\"first\":1}}}";
	}

	private ResponseEntity<String> post(String token, String body) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	private static String challenge(ResponseEntity<String> response) {
		String header = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
		assertThat(header).isNotNull();
		return header;
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
