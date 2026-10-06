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
 * What the rate limiter counts against under the unsafe switch: the client address, which
 * is all a request without authentication carries as a name for its caller.
 *
 * <p>
 * With {@code server.forward-headers-strategy=framework} that address is whatever
 * {@code X-Forwarded-For} carries, so a client that writes the header itself opens a
 * fresh count with each value. That strategy trusts every client's forwarded headers,
 * which is why the README sends a deployment behind a proxy to {@code native} with
 * {@code server.tomcat.remoteip.trusted-proxies}, where the container rewrites the
 * address for the listed proxies alone. The test pins the key, so a change to it shows up
 * here.
 *
 * <p>
 * The setup mirrors {@code McpRateLimitOverHttpTests}: the movie API running and MCP
 * calls allowed without authentication, with the scoped operation files so that two tools
 * exist. Each test calls a tool of its own, because the tests share one context and the
 * limiter keys its buckets on caller and tool.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/", "gatool.mcp.rate-limit.calls-per-minute=1",
				"server.forward-headers-strategy=framework" })
class McpRateLimitUnauthenticatedCallerTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_withoutAuthentication_shouldCountPerForwardedAddress() {
		ResponseEntity<String> first = post("10.9.0.1", callBody("topRatedMovies"));
		ResponseEntity<String> second = post("10.9.0.2", callBody("topRatedMovies"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).contains("Signal from Kepler").doesNotContain("Rate limit reached");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody())
			.as("the second call from a client that sent another X-Forwarded-For, without authentication")
			.contains("Signal from Kepler")
			.doesNotContain("Rate limit reached");
	}

	@Test
	void callTool_withoutAuthenticationFromOneForwardedAddressTwice_shouldBeRefusedOnTheSecondCall() {
		ResponseEntity<String> first = post("10.9.0.3", callBody("moviesPage"));
		ResponseEntity<String> second = post("10.9.0.3", callBody("moviesPage"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).contains("\"isError\":false");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody()).contains("Rate limit reached for moviesPage: 1 calls per minute. Retry after")
			.contains("\"isError\":true");
	}

	private static String callBody(String tool) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
				+ "\",\"arguments\":{\"first\":1}}}";
	}

	private ResponseEntity<String> post(String forwardedFor, String body) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.header("X-Forwarded-For", forwardedFor)
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
