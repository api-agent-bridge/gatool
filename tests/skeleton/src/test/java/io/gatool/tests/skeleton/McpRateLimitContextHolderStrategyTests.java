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
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter reads the caller through the {@code SecurityContextHolderStrategy}
 * bean an application declares, which is where Spring Security's filters write the
 * authentication; the static holder stays empty under such a bean.
 *
 * <p>
 * Read from the static holder, every caller would be anonymous and counted by the client
 * address, so two callers whose requests leave one address would share a count. The setup
 * mirrors {@code McpRateLimitPerCallerTests}, with a strategy bean of the application's
 * own added.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.mcp.rate-limit.calls-per-minute=1" })
class McpRateLimitContextHolderStrategyTests {

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
	void callTool_twoCallersFromOneAddressUnderAStrategyBean_shouldEachGetACountOfTheirOwn() {
		String callerOne = LocalIssuer.get().tokenFor("agent-one", AUDIENCE, SCOPES);
		String callerTwo = LocalIssuer.get().tokenFor("agent-two", AUDIENCE, SCOPES);

		ResponseEntity<String> first = post(callerOne, callBody("topRatedMovies"));
		ResponseEntity<String> second = post(callerTwo, callBody("topRatedMovies"));

		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(first.getBody()).contains("Signal from Kepler").doesNotContain("Rate limit reached");
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody())
			.as("the first call of a second caller from the same address, under a strategy bean")
			.contains("Signal from Kepler")
			.doesNotContain("Rate limit reached");
	}

	@Test
	void callTool_oneCallerTwiceUnderAStrategyBean_shouldBeRefusedOnTheSecondCall() {
		String caller = LocalIssuer.get().tokenFor("agent-three", AUDIENCE, SCOPES);

		ResponseEntity<String> first = post(caller, callBody("moviesPage"));
		ResponseEntity<String> second = post(caller, callBody("moviesPage"));

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

		// A strategy of the application's own, with a thread local of its own, so what
		// Spring Security's filters write here stays out of the static holder.
		@Bean
		SecurityContextHolderStrategy ownContextHolderStrategy() {
			return new OwnContextHolderStrategy();
		}

	}

	static final class OwnContextHolderStrategy implements SecurityContextHolderStrategy {

		private final ThreadLocal<SecurityContext> contexts = new ThreadLocal<>();

		@Override
		public void clearContext() {
			this.contexts.remove();
		}

		@Override
		public SecurityContext getContext() {
			SecurityContext context = this.contexts.get();
			if (context == null) {
				context = createEmptyContext();
				this.contexts.set(context);
			}
			return context;
		}

		@Override
		public void setContext(SecurityContext context) {
			this.contexts.set(context);
		}

		@Override
		public SecurityContext createEmptyContext() {
			return new SecurityContextImpl();
		}

	}

}
