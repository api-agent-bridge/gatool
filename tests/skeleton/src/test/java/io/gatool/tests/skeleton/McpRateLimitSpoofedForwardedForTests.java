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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter counts per authenticated caller, so one caller cannot open a fresh
 * count by changing the address a forwarded header reports.
 *
 * <p>
 * The setup mirrors {@code McpToolScopesOverHttpTests} and adds
 * {@code server.forward-headers-strategy=framework}. With that strategy Spring Boot
 * registers {@link ForwardedHeaderFilter}, and the servlet request answers
 * {@code getRemoteAddr()} with whatever {@code X-Forwarded-For} carries, which any client
 * can write. The limit is one call per minute per caller and tool, so the second call of
 * one caller is the one the limiter refuses.
 *
 * <p>
 * Each test calls a tool of its own, because the tests share one context and the limiter
 * keys its buckets on caller and tool.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.mcp.rate-limit.calls-per-minute=1",
				"server.forward-headers-strategy=framework" })
class McpRateLimitSpoofedForwardedForTests {

	private static final String AUDIENCE = "gatool-skeleton";

	private static final List<String> SCOPES = List.of("mcp:tools", "movies:read");

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext context;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void forwardedHeaderFilter_withTheFrameworkStrategy_shouldBeRegistered() {
		// The sanity check for the test below: without this filter both calls would
		// report the socket's address, and the second would be refused for that reason
		// alone.
		String[] names = this.context.getBeanNamesForType(
				ResolvableType.forClassWithGenerics(FilterRegistrationBean.class, ForwardedHeaderFilter.class));

		assertThat(names).isNotEmpty();
	}

	@Test
	void callTool_oneAuthenticatedCallerBehindTwoForwardedForValues_shouldBeRefusedOnTheSecondCall() {
		String caller = LocalIssuer.get().tokenFor("agent-one", AUDIENCE, SCOPES);

		ResponseEntity<String> first = post(caller, "10.9.0.1", callBody("topRatedMovies"));
		ResponseEntity<String> second = post(caller, "10.9.0.2", callBody("topRatedMovies"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).contains("Signal from Kepler").doesNotContain("Rate limit reached");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		// The same token, so the same authenticated caller, has made two calls within a
		// minute, and the limiter refuses the second one whatever address the forwarded
		// header names.
		assertThat(second.getBody())
			.as("the second call of one authenticated caller within a minute, sent with another X-Forwarded-For")
			.contains("Rate limit reached for topRatedMovies: 1 calls per minute. Retry after")
			.contains("\"isError\":true");
	}

	@Test
	void callTool_oneAuthenticatedCallerBehindOneForwardedForValueTwice_shouldBeRefusedOnTheSecondCall() {
		// The same caller and the same forwarded address: refused under either key, and
		// it pins the refusal text the limiter returns under this strategy.
		String caller = LocalIssuer.get().tokenFor("agent-two", AUDIENCE, SCOPES);

		ResponseEntity<String> first = post(caller, "10.9.0.3", callBody("moviesPage"));
		ResponseEntity<String> second = post(caller, "10.9.0.3", callBody("moviesPage"));

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

	private ResponseEntity<String> post(String token, String forwardedFor, String body) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.header("X-Forwarded-For", forwardedFor)
			.headers((headers) -> headers.setBearerAuth(token))
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
