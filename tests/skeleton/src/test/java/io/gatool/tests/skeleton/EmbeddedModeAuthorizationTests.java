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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import reactor.core.publisher.Mono;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.GraphQlExecutionRequest;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.internal.execution.EmbeddedGraphQlExecutor;
import io.gatool.boot.mcp.security.McpServerSecurityConfigurer;
import io.gatool.fixtures.movies.api.MoviesApiApplication;
import io.gatool.fixtures.movies.api.ScalarConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an embedded tool call misses, in two shapes.
 *
 * <p>
 * First, the application serves its own GraphQL API at {@code /graphql} behind a URL rule
 * that asks for {@code movies:read}, and serves MCP at {@code /mcp} behind the baseline
 * scope. A tool call in embedded mode reaches {@code WebGraphQlHandler} inside the JVM,
 * so the servlet filters and the URL rule on {@code /graphql} are left out of the path.
 * The operation file does not declare scopes, which is what the README recommends for
 * embedded mode.
 *
 * <p>
 * Second, an application without a {@code WebGraphQlHandler} bean, which is every non-web
 * application, gets a handler built from its {@code ExecutionGraphQlService}. That
 * handler carries the application's {@code WebGraphQlInterceptor} beans the way Boot's
 * own handler bean does, and startup names them in the line it logs for embedded mode.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = MoviesApiApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools" })
@Import(EmbeddedModeAuthorizationTests.Chains.class)
class EmbeddedModeAuthorizationTests {

	private static final String AUDIENCE = "gatool-skeleton";

	// The same operation the tool file holds, sent the way a client sends it.
	private static final String GRAPHQL_BODY = "{\"query\":\"query TopRatedMovies($first: Int = 10) "
			+ "{ topRatedMovies(first: $first) { id title rating } }\",\"variables\":{\"first\":1}}";

	private static final String TOOL_CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"topRatedMovies\",\"arguments\":{\"first\":1}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
	}

	@Test
	void postGraphQl_withTheBaselineScopeAlone_shouldAnswer403FromTheUrlRule() {
		ResponseEntity<String> response = post("/graphql", token("mcp:tools"), GRAPHQL_BODY);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(String.valueOf(response.getBody())).doesNotContain("Signal from Kepler");
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("insufficient_scope");
	}

	@Test
	void postGraphQl_withTheApiScope_shouldAnswerTheData() {
		ResponseEntity<String> response = post("/graphql", token("mcp:tools", "movies:read"), GRAPHQL_BODY);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Signal from Kepler");
	}

	@Test
	void callTool_withTheBaselineScopeAlone_shouldReachTheEngineWithoutTheGraphQlUrlRule() {
		// Embedded mode calls the handler directly, so the URL rule on /graphql is not in
		// the path and a tool call is decided by the tool's own scopes. The two answers
		// therefore differ for one token and one document, which is the trap: a team
		// whose GraphQL authorization lives in authorizeHttpRequests has a second door.
		// Startup warns, the README says so, and authorization for this mode belongs in
		// the resolvers with method security.
		assertThat(post("/graphql", token("mcp:tools"), GRAPHQL_BODY).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		ResponseEntity<String> response = post("/mcp", token("mcp:tools"), TOOL_CALL);

		assertThat(String.valueOf(response.getBody())).contains("Signal from Kepler");
	}

	@Test
	void startup_withAWebGraphQlHandlerBean_shouldWarnThatACallPassesNoUrlRule(CapturedOutput output) {
		// A WebGraphQlHandler bean is what a servlet application publishes, and Spring
		// Security is on this classpath, so the line is a warning: a URL rule on the
		// GraphQL path is something this application plausibly has, and a tool call
		// does not meet it.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GraphQlAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withUserConfiguration(ScalarConfiguration.class, HandlerConfiguration.class)
			.withPropertyValues("spring.graphql.schema.locations=classpath:io/gatool/fixtures/movies/")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.containsPattern("WARN.*A tool call in embedded mode reaches the GraphQL engine directly, so every "
					+ "servlet filter and every authorizeHttpRequests rule on the GraphQL path stays out of it")
			.contains("method security on a resolver still decides");
	}

	@Test
	void startup_inANonWebApplication_shouldSayWhatDecidesAtInfo(CapturedOutput output) {
		// Without a WebGraphQlHandler bean the application serves the engine without an
		// HTTP layer, so a sentence about servlet filters and URL rules on the GraphQL
		// path would describe something the application lacks. The line describes the
		// mode, at INFO, because every check that applies to such a call sits inside
		// the engine.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GraphQlAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withUserConfiguration(ScalarConfiguration.class)
			.withPropertyValues("spring.graphql.schema.locations=classpath:io/gatool/fixtures/movies/")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.containsPattern("INFO.*A tool call in embedded mode reaches the GraphQL engine directly, and this "
					+ "application serves the engine without an HTTP layer in front of it")
			.contains("method security on a resolver, decide what a tool call may run");
	}

	@Test
	void callTool_inEmbeddedMode_shouldLeaveTheServletFiltersOnTheGraphQlPathIdle() {
		// The mechanism behind the test above: the call reaches WebGraphQlHandler inside
		// the JVM, so it does not pass the servlet path the GraphQL API is served on.
		int filterCallsBefore = GraphQlPathFilter.CALLS.get();

		ResponseEntity<String> response = post("/mcp", token("mcp:tools"), TOOL_CALL);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(GraphQlPathFilter.CALLS.get()).isEqualTo(filterCallsBefore);
	}

	@Test
	void embeddedExecutor_inANonWebApplication_shouldRunTheInterceptorsAndNameThem(CapturedOutput output) {
		// A non-web application lacks the WebGraphQlHandler bean, which is the branch
		// that builds a handler from the ExecutionGraphQlService. That handler carries
		// the web interceptors the way Boot's own handler bean does, so the gate a team's
		// authorization interceptor puts in front of every request applies to tool calls,
		// and startup names the interceptors as applied.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GraphQlAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withUserConfiguration(ScalarConfiguration.class)
			.withBean("countingInterceptor", WebGraphQlInterceptor.class, CountingInterceptor::new)
			.withPropertyValues("spring.graphql.schema.locations=classpath:io/gatool/fixtures/movies/")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(WebGraphQlHandler.class);
				assertThat(context.getBean(GraphQlExecutor.class)).isInstanceOf(EmbeddedGraphQlExecutor.class);
				CountingInterceptor.CALLS.set(0);

				GraphQlResponse response = context.getBean(GraphQlExecutor.class)
					.execute(new GraphQlExecutionRequest("query TopRatedMovies { topRatedMovies { id } }",
							"TopRatedMovies", Map.of()));

				assertThat(response).isNotNull();
				assertThat(CountingInterceptor.CALLS.get()).isEqualTo(1);
				assertThat(output.getAll()).contains("through these WebGraphQlInterceptor beans")
					.contains("CountingInterceptor")
					.doesNotContain("stay out of that path");
			});
	}

	private static String token(String... scopes) {
		return LocalIssuer.get().token(AUDIENCE, List.of(scopes));
	}

	private ResponseEntity<String> post(String path, String token, String body) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class HandlerConfiguration {

		@Bean
		WebGraphQlHandler webGraphQlHandler(ExecutionGraphQlService service) {
			return WebGraphQlHandler.builder(service).build();
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Chains {

		@Bean
		@Order(1)
		SecurityFilterChain mcp(HttpSecurity http) throws Exception {
			return http.securityMatcher("/mcp", "/.well-known/oauth-protected-resource/mcp")
				.with(McpServerSecurityConfigurer.mcpServer(), Customizer.withDefaults())
				.build();
		}

		@Bean
		@Order(2)
		SecurityFilterChain api(HttpSecurity http) throws Exception {
			// The API asks for a scope of its own, the way a team guards a GraphQL
			// endpoint that agents and browsers share. CSRF is off so that a 403 here
			// comes from this rule alone.
			return http
				.authorizeHttpRequests((requests) -> requests.requestMatchers("/graphql")
					.hasAuthority("SCOPE_movies:read")
					.anyRequest()
					.authenticated())
				.csrf(AbstractHttpConfigurer::disable)
				.oauth2ResourceServer((server) -> server.jwt(Customizer.withDefaults()))
				.build();
		}

		@Bean
		FilterRegistrationBean<GraphQlPathFilter> graphQlPathFilter() {
			FilterRegistrationBean<GraphQlPathFilter> registration = new FilterRegistrationBean<>(
					new GraphQlPathFilter());
			registration.addUrlPatterns("/graphql");
			return registration;
		}

	}

	/**
	 * Counts what reaches the servlet path the GraphQL API is served on.
	 */
	static final class GraphQlPathFilter extends OncePerRequestFilter {

		static final AtomicInteger CALLS = new AtomicInteger();

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			CALLS.incrementAndGet();
			chain.doFilter(request, response);
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
