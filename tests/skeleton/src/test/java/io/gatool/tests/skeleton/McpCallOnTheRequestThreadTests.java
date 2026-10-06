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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.boot.mcp.limit.ToolRateLimiter;
import io.gatool.tests.hosts.OffThreadCallHostApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A tool call runs on the thread that serves its request, which is where GATool reads the
 * caller.
 *
 * <p>
 * The MCP SDK hands a synchronous tool handler to a worker thread unless the server was
 * built with {@code immediateExecution(true)}. Spring AI asks for that in a servlet
 * application: its stateless server bean sets it itself, and its stateful server bean
 * takes it from the {@code McpSyncServerCustomizer} bean Spring AI contributes. That
 * server bean takes one customizer, so an application's own bean beside Spring AI's stops
 * startup, and one marked primary takes the place of Spring AI's and has to ask for
 * immediate execution itself. {@code McpCallOffTheRequestThreadTests} holds what a call
 * reads where it does not.
 *
 * <p>
 * A filter records the thread that served the last request, and a rate limiter of the
 * test's own records the caller GATool named and the thread the call ran on.
 */
class McpCallOnTheRequestThreadTests {

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"request-thread\",\"version\":\"1\"}}}";

	private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
			+ "{\"name\":\"topRatedMovies\",\"arguments\":{\"first\":1}}}";

	private static final List<String> CALLS = new CopyOnWriteArrayList<>();

	private static final AtomicReference<String> REQUEST_THREAD = new AtomicReference<>("");

	@BeforeEach
	void forgetEarlierCalls() {
		CALLS.clear();
	}

	@Test
	void callTool_onTheStatelessTransport_shouldRunOnTheRequestThreadAndNameTheCaller() {
		try (ConfigurableApplicationContext context = start("STATELESS", Recording.class)) {
			ResponseEntity<String> answer = post(context, token("ana"), null, CALL);

			assertThat(answer.getBody()).contains("Signal from Kepler");
			assertThat(CALLS).containsExactly("principal:ana on " + REQUEST_THREAD.get());
		}
	}

	@Test
	void callTool_onTheStatefulTransport_shouldRunOnTheRequestThreadAndNameTheCaller() {
		try (ConfigurableApplicationContext context = start("STREAMABLE", Recording.class)) {
			ResponseEntity<String> answer = callInASession(context, token("ana"));

			assertThat(answer.getBody()).contains("Signal from Kepler");
			assertThat(CALLS).containsExactly("principal:ana on " + REQUEST_THREAD.get());
		}
	}

	@Test
	void callTool_underAPrimaryCustomizerThatAsksForImmediateExecution_shouldRunOnTheRequestThread() {
		try (ConfigurableApplicationContext context = start("STREAMABLE", PrimaryCustomizer.class)) {
			ResponseEntity<String> answer = callInASession(context, token("ana"));

			assertThat(answer.getBody()).contains("Signal from Kepler");
			assertThat(CALLS).containsExactly("principal:ana on " + REQUEST_THREAD.get());
		}
	}

	@Test
	void startup_customizerOfTheApplicationBesideSpringAis_shouldStopNamingBothBeans() {
		assertThatExceptionOfType(Throwable.class)
			.isThrownBy(() -> start("STREAMABLE", CustomizerBesideSpringAis.class))
			.withRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
			.havingRootCause()
			.withMessageContaining(McpSyncServerCustomizer.class.getName())
			.withMessageContaining("ownServerCustomizer")
			.withMessageContaining("servletMcpSyncServerCustomizer");
	}

	@Test
	void startup_statefulServerUnderAPrimaryCustomizerWithoutImmediateExecution_shouldStopNamingTheCustomizerAndTheFix()
			throws Exception {
		HostProcess.Run stopped = startHost(List.of());

		assertStopped(stopped);
	}

	@Test
	void startup_theSameCaseOnVirtualThreads_shouldStopNamingTheCustomizerAndTheFix() throws Exception {
		// Virtual threads move the request to a virtual thread and leave the SDK's own
		// choice alone: without immediate execution the call still goes to the
		// scheduler's worker, so the stop holds here as well.
		HostProcess.Run stopped = startHost(List.of("--spring.threads.virtual.enabled=true"));

		assertStopped(stopped);
	}

	private static void assertStopped(HostProcess.Run stopped) {
		String written = stopped.stdout() + stopped.stderr();
		assertThat(stopped.exitCode()).as(written).isNotZero();
		assertThat(written).contains("The MCP server bean 'mcpSyncServer' was built without immediateExecution(true)")
			.contains("'servletMcpSyncServerCustomizer'")
			.contains("'ownServerCustomizer'")
			.contains("Add immediateExecution(true) to that customizer");
	}

	// The host runs in a JVM of its own, because the stop is what the process exits
	// with, and the description and the action are what Spring Boot's failure analysis
	// wrote before it did.
	private static HostProcess.Run startHost(List<String> extraArguments) throws Exception {
		List<String> arguments = new ArrayList<>(List.of("--server.port=0", "--spring.main.banner-mode=off",
				"--spring.ai.mcp.server.protocol=STREAMABLE",
				"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"--gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"--gatool.api.url=" + MoviesApiServer.graphQlUrl(),
				"--gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true", "--host.tokens=anonymous"));
		arguments.addAll(extraArguments);
		return HostProcess.run(OffThreadCallHostApplication.class, System.getProperty("java.class.path"), arguments,
				Duration.ofSeconds(120));
	}

	@Test
	void startup_customizerOfTheApplicationOnTheStatelessTransport_shouldStartAndLeaveItUnread() {
		// The stateless server bean is built without a customizer, and Spring AI's own
		// customizer bean belongs to the stateful configuration, so the application's
		// bean is the only one of its type and stays unread.
		try (ConfigurableApplicationContext context = start("STATELESS", CustomizerBesideSpringAis.class)) {
			ResponseEntity<String> answer = post(context, token("ana"), null, CALL);

			assertThat(answer.getBody()).contains("Signal from Kepler");
			assertThat(CALLS).containsExactly("principal:ana on " + REQUEST_THREAD.get());
			assertThat(CustomizerBesideSpringAis.CUSTOMIZED).isEmpty();
		}
	}

	private static String token(String subject) {
		return LocalIssuer.get().tokenFor(subject, "gatool-skeleton", List.of("mcp:tools", "movies:read"));
	}

	private static ResponseEntity<String> callInASession(ConfigurableApplicationContext context, String token) {
		String session = post(context, token, null, INITIALIZE).getHeaders().getFirst("Mcp-Session-Id");
		post(context, token, session, INITIALIZED);
		return post(context, token, session, CALL);
	}

	private static ConfigurableApplicationContext start(String protocol, Class<?> application) {
		return new SpringApplicationBuilder().sources(application)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0", "spring.main.banner-mode=off", "spring.ai.mcp.server.protocol=" + protocol,
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
					"gatool.mcp.operations.locations=classpath:gatool/scoped/",
					"spring.security.oauth2.resourceserver.jwt.issuer-uri=" + LocalIssuer.get().url(),
					"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
					"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.url=" + MoviesApiServer.graphQlUrl())
			.run();
	}

	private static ResponseEntity<String> post(ConfigurableApplicationContext context, String token, String sessionId,
			String body) {
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> {
				headers.setBearerAuth(token);
				if (sessionId != null) {
					headers.set("Mcp-Session-Id", sessionId);
				}
			})
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	// Without a component scan, so that the test classes of this package stay out of
	// the context.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Recording {

		@Bean
		ToolRateLimiter recordingRateLimiter() {
			return (caller, toolName) -> {
				CALLS.add(caller + " on " + Thread.currentThread().getName());
				return RateLimitDecision.allow();
			};
		}

		@Bean
		OncePerRequestFilter requestThreadRecorder() {
			return new OncePerRequestFilter() {

				@Override
				protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
						FilterChain chain) throws ServletException, IOException {
					REQUEST_THREAD.set(Thread.currentThread().getName());
					chain.doFilter(request, response);
				}
			};
		}

	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(Recording.class)
	static class PrimaryCustomizer {

		@Bean
		@Primary
		McpSyncServerCustomizer ownServerCustomizer() {
			return (server) -> server.immediateExecution(true);
		}

	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import(Recording.class)
	static class CustomizerBesideSpringAis {

		static final List<String> CUSTOMIZED = new CopyOnWriteArrayList<>();

		@Bean
		McpSyncServerCustomizer ownServerCustomizer() {
			return (server) -> CUSTOMIZED.add("customized");
		}

	}

}
