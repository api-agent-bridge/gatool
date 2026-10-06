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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import reactor.core.publisher.Mono;

import io.gatool.fixtures.movies.api.MoviesApiApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Embedded mode reached the way an agent reaches it: the application that serves the
 * movie API also serves MCP, and a tool call runs inside it through the application's own
 * interceptors.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = MoviesApiApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true" })
@Import(EmbeddedModeOverHttpTests.Interceptors.class)
class EmbeddedModeOverHttpTests {

	@LocalServerPort
	private int port;

	@Test
	void callTool_overMcpInEmbeddedMode_shouldRunInsideTheApplicationAndPassItsInterceptor(CapturedOutput output) {
		try (McpSyncClient client = McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build()) {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(call("topRatedMovies", Map.of("first", 1)));

			assertThat(result.isError()).isFalse();
			assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("Signal from Kepler");
			// The document ran through the application's own chain, which startup named.
			assertThat(CountingInterceptor.CALLS.get()).isGreaterThan(0);
			assertThat(output.getAll()).contains("runs each document inside this application")
				.contains("CountingInterceptor");
		}
	}

	// The SDK deprecates the two-argument constructor in favour of its builder.
	private static McpSchema.CallToolRequest call(String name, Map<String, Object> arguments) {
		return McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Interceptors {

		@Bean
		WebGraphQlInterceptor countingInterceptor() {
			return new CountingInterceptor();
		}

	}

	static final class CountingInterceptor implements WebGraphQlInterceptor {

		static final AtomicInteger CALLS = new AtomicInteger();

		@Override
		public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
			CALLS.incrementAndGet();
			return chain.next(request);
		}

	}

}
