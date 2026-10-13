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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpLoggableSession;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool marked elicit in a context configured for stdio with
 * {@code gatool.mcp.stdio.elicitation} on, with the tool handler called directly: each
 * test takes the stateful handler from the context and calls it with an exchange of its
 * own, so the call stays inside this JVM. The handler asks through the exchange it is
 * given, and a handler called without one answers the unsupported text.
 *
 * <p>
 * {@link McpStdioElicitOverPipesTests} runs the same tool end to end over the pipes of a
 * second JVM.
 */
class McpStdioElicitTests {

	private static final ResolvableType STATEFUL_TOOL_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpServerFeatures.SyncToolSpecification.class);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
				"gatool.mcp.stdio.elicitation=true", "gatool.mcp.stdio.granted-scopes=",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/elicit/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/")
		.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl());

	@Test
	void callTool_userAccepts_shouldAskWithTheQuestionAndTheArgumentsAndReachTheApi() {
		AnsweringSession session = new AnsweringSession(
				() -> Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of(), null)));
		this.contextRunner.run((context) -> {
			McpSchema.CallToolResult result = call(context, exchange(session), "topRatedMovies", Map.of("first", 1));
			assertThat(result.isError()).as(firstText(result)).isFalse();
			assertThat(firstText(result)).contains("Signal from Kepler");
		});

		assertThat(session.sent()).singleElement().satisfies((sent) -> {
			assertThat(sent.method()).isEqualTo("elicitation/create");
			assertThat(sent.params()).isInstanceOfSatisfying(McpSchema.ElicitFormRequest.class,
					(request) -> assertThat(request.message())
						.isEqualTo("Read the top rated movies?\n\n{\"first\":1}"));
		});
	}

	@Test
	void callTool_userDeclines_shouldAnswerTheDeclinedSentence() {
		AnsweringSession session = new AnsweringSession(
				() -> Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, Map.of(), null)));
		this.contextRunner.run((context) -> {
			McpSchema.CallToolResult result = call(context, exchange(session), "topRatedMovies", Map.of("first", 1));
			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).isEqualTo("The user declined the call to topRatedMovies, so the API was not "
					+ "called. Ask the user what to do next.");
		});
	}

	@Test
	void callTool_withoutAnExchange_shouldAnswerUnsupported() {
		this.contextRunner.run((context) -> {
			McpSchema.CallToolResult result = call(context, null, "topRatedMovies", Map.of("first", 1));
			assertThat(result.isError()).isTrue();
			assertThat(firstText(result)).isEqualTo("The tool topRatedMovies asks the user before it runs, and this "
					+ "client did not declare the form elicitation capability, so the API was not called. Connect "
					+ "with a client that supports MCP form elicitation.");
		});
	}

	@SuppressWarnings("unchecked")
	private static McpSchema.CallToolResult call(AssertableApplicationContext context,
			@Nullable McpSyncServerExchange exchange, String tool, Map<String, Object> arguments) {
		List<McpServerFeatures.SyncToolSpecification> specifications = (List<McpServerFeatures.SyncToolSpecification>) context
			.getBeanProvider(STATEFUL_TOOL_SPECIFICATIONS)
			.getObject();
		McpServerFeatures.SyncToolSpecification specification = specifications.stream()
			.filter((candidate) -> candidate.tool().name().equals(tool))
			.findFirst()
			.orElseThrow();
		return specification.callHandler()
			.apply(exchange, McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
	}

	private static McpSyncServerExchange exchange(AnsweringSession session) {
		return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", session,
				McpSchema.ClientCapabilities.builder().elicitation().build(),
				McpSchema.Implementation.builder("test-client", "1").build(), McpTransportContext.EMPTY,
				new DefaultJsonSchemaValidator()));
	}

	private static String firstText(McpSchema.CallToolResult result) {
		return ((McpSchema.TextContent) result.content().getFirst()).text();
	}

	/**
	 * One request the handler sent to the client through the session.
	 *
	 * @param method the JSON-RPC method
	 * @param params the parameters of the request
	 */
	private record SentRequest(String method, Object params) {

	}

	/**
	 * A session that records each request the handler sends and answers it from the
	 * supplier.
	 */
	private static final class AnsweringSession implements McpLoggableSession {

		private final Supplier<Mono<?>> answer;

		private final List<SentRequest> sent = new CopyOnWriteArrayList<>();

		AnsweringSession(Supplier<Mono<?>> answer) {
			this.answer = answer;
		}

		List<SentRequest> sent() {
			return this.sent;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
			this.sent.add(new SentRequest(method, requestParams));
			return (Mono<T>) this.answer.get();
		}

		@Override
		public Mono<Void> sendNotification(String method, Object params) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public void close() {
		}

		@Override
		public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
		}

		@Override
		public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
			return true;
		}

	}

}
