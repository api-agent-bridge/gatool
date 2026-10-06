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

package io.gatool.tests.consumer.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A servlet application that serves MCP over stdio keeps the MCP endpoint off its HTTP
 * port.
 *
 * <p>
 * An application sets {@code spring.ai.mcp.server.stdio=true} and keeps
 * {@code spring.main.web-application-type=servlet} for pages or an actuator of its own.
 * Spring AI's HTTP transport backs off there through its
 * {@code McpServerStdioDisabledCondition}, and GATool's own HTTP transport backs off with
 * it. A server built on HTTP there would run tool calls on {@code POST /mcp} for any
 * caller with the unsafe switch off, because every check that reads the stdio property
 * takes the application for a stdio server.
 *
 * <p>
 * This module's classpath is the one that shape needs, the MCP starter without Spring
 * Security. The application declares a provider of its own that stands in for the SDK's
 * stdio provider, which would read the standard input of the test JVM.
 */
class McpStdioInAServletApplicationTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@Test
	void mcpEndpoint_overStdioInAServletApplication_shouldAnswerNotFound() throws Exception {
		assertThat(
				ClassUtils.isPresent("org.springframework.security.core.Authentication", getClass().getClassLoader()))
			.as("Spring Security absent from the MCP starter's classpath")
			.isFalse();

		try (ConfigurableApplicationContext context = startServletApplicationOverStdio()) {
			String port = context.getEnvironment().getProperty("local.server.port");

			HttpResponse<String> response = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json, text/event-stream")
					.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE))
					.build(), HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).as("POST /mcp initialize, body: %s", response.body()).isEqualTo(404);
		}
	}

	@Test
	void transportProviders_overStdioInAServletApplication_shouldBeTheStdioProviderAlone() {
		try (ConfigurableApplicationContext context = startServletApplicationOverStdio()) {
			assertThat(context.getBeanNamesForType(McpServerTransportProviderBase.class))
				.containsExactly("standInStdioProvider");
			assertThat(context.containsBean("webMvcStreamableServerRouterFunction"))
				.as("the router function of the HTTP transport")
				.isFalse();
		}
	}

	private static ConfigurableApplicationContext startServletApplicationOverStdio() {
		// The property, because GATool sets spring.main.web-application-type=none for
		// stdio while that property is unset, and a type set on the builder yields to it.
		return new SpringApplicationBuilder(ServletApplicationOverStdio.class)
			.properties("server.port=0", "spring.ai.mcp.server.stdio=true", "spring.main.web-application-type=servlet",
					"gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:greetings.graphqls")
			.run();
	}

	// Without a component scan, so that the other tests' applications stay out.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ServletApplicationOverStdio {

		@Bean
		McpServerTransportProvider standInStdioProvider() {
			return new StandInStdioProvider();
		}

	}

	/**
	 * A provider that stands in for the SDK's stdio provider, which reads the standard
	 * input of the process it runs in.
	 */
	private static final class StandInStdioProvider implements McpServerTransportProvider {

		@Override
		public void setSessionFactory(McpServerSession.Factory sessionFactory) {
			// Nothing opens a session here, so the factory goes unused.
		}

		@Override
		public Mono<Void> notifyClients(String method, Object params) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

}
