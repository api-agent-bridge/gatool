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
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP endpoint answers under {@code spring.main.lazy-initialization=true} on both
 * HTTP transports.
 *
 * <p>
 * {@link McpLazyInitializationTests} holds the startup stops under lazy initialization on
 * a context runner without a server. This one starts the server and sends
 * {@code initialize}. Spring AI's server bean installs the handler on the transport while
 * it is built, and a lazy context builds a bean once another bean asks for it. Requests
 * reach the transport, and the transport is what the server installs its handler on, so
 * the server bean is one that every other bean leaves alone, and a lazy context would
 * leave it unbuilt. Such a context reports itself active with the port bound, which is
 * what a health check reads, and answers every request with 500: "MCP handler not
 * configured" on the stateless transport and "SessionFactory not configured" on the
 * stateful one. {@code GAToolMcpAutoConfiguration} publishes a
 * {@code LazyInitializationExcludeFilter} for the two server types, so the server is
 * built at startup as it is in an eager application.
 */
class McpLazyInitializationOverHttpTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"lazy-probe\",\"version\":\"1\"}}}";

	@Test
	void initialize_statelessUnderLazyInitialization_shouldAnswerWithTheServerInfo() {
		try (ConfigurableApplicationContext context = start("STATELESS")) {
			ResponseEntity<String> response = initialize(port(context));

			assertThat(context.isActive()).isTrue();
			assertThat(response.getStatusCode().value()).isEqualTo(200);
			assertThat(response.getBody()).contains("serverInfo").doesNotContain("MCP handler not configured");
		}
	}

	@Test
	void initialize_statefulUnderLazyInitialization_shouldAnswerWithTheServerInfo() {
		try (ConfigurableApplicationContext context = start("STREAMABLE")) {
			ResponseEntity<String> response = initialize(port(context));

			assertThat(context.isActive()).isTrue();
			assertThat(response.getStatusCode().value()).isEqualTo(200);
			assertThat(response.getBody()).contains("serverInfo").doesNotContain("SessionFactory not configured");
		}
	}

	private static ConfigurableApplicationContext start(String protocol) {
		return new SpringApplicationBuilder().sources(LazyApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0", "spring.main.banner-mode=off", "spring.main.lazy-initialization=true",
					"spring.ai.mcp.server.protocol=" + protocol,
					"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
					"gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls")
			.run();
	}

	private static int port(ConfigurableApplicationContext context) {
		return ((WebServerApplicationContext) context).getWebServer().getPort();
	}

	private static ResponseEntity<String> initialize(int port) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.body(INITIALIZE)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	// Without a component scan, so that the test classes of this package stay out of
	// the context.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class LazyApplication {

	}

}
