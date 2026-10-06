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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter counts per authenticated caller, so two callers whose requests leave
 * the same client address each get a count of their own, and one caller's second call
 * within the minute is the one refused.
 *
 * <p>
 * The setup mirrors {@code McpToolScopesOverHttpTests}: a resource server against
 * {@link LocalIssuer}, the scoped operation files, the baseline scope, and the movie API
 * running so a call the limiter lets through answers with data. The limit is one call per
 * minute per caller and tool, the smallest value the settings accept. Without a
 * forwarded-headers strategy the servlet request reports the socket's own address, which
 * is the same for every call this test makes, so a count per address would starve the
 * second caller.
 *
 * <p>
 * Each test calls a tool of its own, because both tests share one context and the limiter
 * keys its buckets on caller and tool.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.mcp.rate-limit.calls-per-minute=1" })
class McpRateLimitPerCallerTests {

	private static final String AUDIENCE = "gatool-skeleton";

	private static final List<String> SCOPES = List.of("mcp:tools", "movies:read");

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_twoAuthenticatedCallersFromOneAddress_shouldEachGetACountOfTheirOwn() {
		String callerOne = LocalIssuer.get().tokenFor("agent-one", AUDIENCE, SCOPES);
		String callerTwo = LocalIssuer.get().tokenFor("agent-two", AUDIENCE, SCOPES);

		ResponseEntity<String> first = post(callerOne, callBody("topRatedMovies"));
		ResponseEntity<String> second = post(callerTwo, callBody("topRatedMovies"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).contains("Signal from Kepler").doesNotContain("Rate limit reached");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		// agent-two has made one call this minute, so the limiter lets it through
		// however many calls agent-one made from the same address.
		assertThat(second.getBody()).as("the first call of a second authenticated caller, from the same client address")
			.contains("Signal from Kepler")
			.doesNotContain("Rate limit reached");
	}

	@Test
	void callTool_oneAuthenticatedCallerTwiceWithinAMinute_shouldBeRefusedOnTheSecondCall() {
		String caller = LocalIssuer.get().tokenFor("agent-three", AUDIENCE, SCOPES);

		ResponseEntity<String> first = post(caller, callBody("moviesPage"));
		ResponseEntity<String> second = post(caller, callBody("moviesPage"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).contains("\"isError\":false");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody())
			.as("the second call of one authenticated caller within a minute, at one call per minute")
			.contains("Rate limit reached for moviesPage: 1 calls per minute. Retry after")
			.contains("\"isError\":true");
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

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
