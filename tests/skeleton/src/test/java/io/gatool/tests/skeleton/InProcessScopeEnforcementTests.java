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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code @gatool(scopes: ...)} does on the in-process side.
 *
 * <p>
 * The operation files under {@code gatool/scoped/} are the ones the MCP scope tests use.
 * {@code movieByLookup} lists {@code movies:read movies:detail}, and over MCP a caller
 * without either scope gets a 403 or a tool error. These tests hand the same files to the
 * in-process flavour and call the same tool from a thread whose security context does not
 * hold any scope.
 */
@ExtendWith(OutputCaptureExtension.class)
class InProcessScopeEnforcementTests {

	private static final String MOVIE_BY_LOOKUP = "{\"by\":{\"id\":\"movie-1\"}}";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl(),
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.in-process.operations.locations=classpath:gatool/scoped/",
				"gatool.mcp.operations.locations=optional:classpath*:gatool/none/");

	@AfterEach
	void clearTheSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void call_aToolRequiringScopesWithoutAnyAuthentication_shouldStillReachTheApi() {
		// The scope list is checked at the MCP endpoint, against the caller that arrived
		// there. The in-process caller is the application's own ChatClient, so the list
		// states what the application has to enforce, and this path does not read it.
		// Checking here would refuse ordinary calls in an async or streaming path,
		// because a ChatClient call often runs on a thread without a security context.
		this.contextRunner.run((context) -> {
			ToolCallback callback = callbackFor(context, "movieByLookup");

			String text = callback.call(MOVIE_BY_LOOKUP);

			assertThat(text).as("the result of a scoped tool called by an unauthenticated caller")
				.contains("Signal from Kepler");
		});
	}

	@Test
	void call_aToolRequiringScopesWithTheBaselineScopeAlone_shouldStillReachTheApi() {
		SecurityContextHolder.getContext()
			.setAuthentication(new UsernamePasswordAuthenticationToken("reader", "n/a",
					AuthorityUtils.createAuthorityList("SCOPE_mcp:tools")));

		this.contextRunner.run((context) -> {
			ToolCallback callback = callbackFor(context, "movieByLookup");

			String text = callback.call(MOVIE_BY_LOOKUP);

			assertThat(text).as("the result of a scoped tool called with the baseline scope alone")
				.contains("Signal from Kepler");
		});
	}

	@Test
	void call_aToolOpenToEveryCaller_shouldReachTheApi() {
		// searchMovies lists an empty scope set, which opens it at the endpoint. On this
		// path it reaches the API like the two above, and it is here because a scope list
		// that changed the answer would show up as a difference between them.
		this.contextRunner.run((context) -> {
			ToolCallback callback = callbackFor(context, "searchMovies");

			String text = callback.call("{\"text\":\"Signal\"}");

			assertThat(text).contains("Signal from Kepler");
		});
	}

	@Test
	void startup_inProcessToolsThatListScopes_shouldSayTheApplicationEnforcesThem(CapturedOutput output) {
		this.contextRunner.run((context) -> assertThat(context).hasNotFailed());

		// The listing tells an operator what each tool needs. "needs the scopes" beside a
		// tool describes the check the MCP side runs, so an in-process tool, whose list
		// reaches the application alone, says who has to enforce it.
		assertThat(output.getAll()).contains("in-process tools:");
		assertThat(output.getAll())
			.contains("declares the scopes movies:read movies:detail, which this application has to enforce itself");
		assertThat(output.getAll()).doesNotContain("needs the scopes movies:read movies:detail");
	}

	private static ToolCallback callbackFor(AssertableApplicationContext context, String name) {
		ToolCallback[] callbacks = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks();
		for (ToolCallback callback : callbacks) {
			if (callback.getToolDefinition().name().equals(name)) {
				return callback;
			}
		}
		throw new AssertionError("The in-process tools do not hold " + name);
	}

	/**
	 * The MCP side of the same operation files: what a {@code tools/list} returns to a
	 * caller holding the baseline scope alone.
	 */
	@Nested
	@SpringBootTest(classes = InProcessScopeEnforcementTests.SkeletonApplication.class,
			webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "spring.ai.mcp.server.protocol=STATELESS",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
					"gatool.mcp.operations.locations=classpath:gatool/scoped/",
					"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
					"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
					"gatool.mcp.security.baseline-scopes=mcp:tools" })
	class ListingScopedToolsOverHttp {

		@LocalServerPort
		private int port;

		@DynamicPropertySource
		static void servers(DynamicPropertyRegistry registry) {
			registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
			registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
		}

		@Test
		void listTools_withTheBaselineScopeAlone_shouldStillNameEveryTool() {
			String token = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));

			ResponseEntity<String> response = RestClient.builder()
				.baseUrl("http://127.0.0.1:" + this.port)
				.build()
				.post()
				.uri("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.header("MCP-Protocol-Version", "2025-11-25")
				.headers((headers) -> headers.setBearerAuth(token))
				.body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
				.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
						reply.getStatusCode()));

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			// The list is built once at startup and served to every caller, so it names
			// movieByLookup although this token cannot call it: a call answers 403. What
			// travels is the tool's name, description and input schema, never its data,
			// and filtering per caller would rebuild the list on every request.
			assertThat(response.getBody()).contains("searchMovies").contains("movieByLookup");
		}

	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
