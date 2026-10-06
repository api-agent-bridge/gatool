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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

// The context holds both starters, so these tests also show that the in-process
// callbacks stay off the MCP server that runs beside them.
@SpringBootTest(classes = InProcessToolEndToEndTests.SkeletonApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class InProcessToolEndToEndTests {

	// The MCP end-to-end test asserts this same text for the same operation, so a
	// difference between the two sides fails one of the two tests.
	private static final String TOP_RATED_MOVIES_RESULT = """
			{"data":{"topRatedMovies":[\
			{"id":"movie-1","title":"Signal from Kepler","rating":8.6},\
			{"id":"movie-2","title":"The Last Lighthouse","rating":7.9},\
			{"id":"movie-3","title":"Midnight Recipe","rating":7.1},\
			{"id":"movie-4","title":"Iron Harbor","rating":6.4}]}}\
			""";

	// The text InputSchemaWriter generates for $first: Int = 10. The MCP test asserts
	// the same schema as the map the SDK publishes.
	private static final String INPUT_SCHEMA = "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
			+ "\"type\":\"object\",\"properties\":{\"first\":"
			+ "{\"anyOf\":[{\"type\":\"integer\"},{\"type\":\"null\"}],"
			+ "\"description\":\"How many movies to return.\",\"default\":10}},"
			+ "\"required\":[],\"additionalProperties\":false}";

	private static final String MOVIE_SCHEMA = "classpath:io/gatool/fixtures/movies/movies.graphqls";

	@Autowired
	private GAToolCallbacks tools;

	@Autowired
	private ApplicationContext context;

	// Startup builds the client and a tool call reaches the API, so port 1 is enough
	// for the closed-port test, and it is the port the other context tests use.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://localhost:1/graphql", "gatool.api.schema.location=" + MOVIE_SCHEMA);

	@DynamicPropertySource
	static void moviesApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void call_emptyArguments_shouldReturnTheSameTextAsTheMcpSide() {
		ToolCallback callback = onlyCallback(this.tools.toolCallbackProvider());

		String text = callback.call("{}");

		assertThat(text).isEqualTo(TOP_RATED_MOVIES_RESULT);
	}

	@Test
	void call_firstTwo_shouldReturnTheTextTheMcpSideReturns() {
		ToolCallback callback = onlyCallback(this.tools.toolCallbackProvider());

		String text = callback.call("{\"first\": 2}");

		assertThat(callback.getToolDefinition().inputSchema()).isEqualTo(INPUT_SCHEMA);
		assertThat(text).contains("Signal from Kepler", "The Last Lighthouse").doesNotContain("Midnight Recipe");
	}

	@Test
	void call_argumentNameOutsideTheSchema_shouldGetTheApisAnswerForAVariableItLeavesUndeclared() {
		ToolCallback callback = onlyCallback(this.tools.toolCallbackProvider());
		int before = MoviesApiServer.graphQlCalls();

		String text = callback.call("{\"frist\": 2}");

		// The callback runs without the validation the MCP SDK applies on the other side,
		// where McpTransportTestBase holds the refusal of this same argument. The API
		// passes over a variable the operation leaves undeclared, so $first keeps its
		// default of 10 and all four movies come back, where first: 2 returns two.
		assertThat(MoviesApiServer.graphQlCalls()).as("calls the API counted after the call").isGreaterThan(before);
		assertThat(text).isEqualTo(TOP_RATED_MOVIES_RESULT);
	}

	@Test
	void call_argumentNameOutsideTheSchema_shouldTravelToTheApiAsTheModelWroteIt() throws IOException {
		AtomicReference<String> body = new AtomicReference<>("");
		HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		api.createContext("/graphql", (exchange) -> {
			try (InputStream in = exchange.getRequestBody()) {
				body.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			byte[] answer = "{\"data\":{\"topRatedMovies\":[]}}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, answer.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(answer);
			}
		});
		api.start();
		try {
			this.contextRunner
				.withPropertyValues("gatool.api.url=http://127.0.0.1:" + api.getAddress().getPort() + "/graphql")
				.run((recordingContext) -> onlyCallback(
						recordingContext.getBean(GAToolCallbacks.class).toolCallbackProvider())
					.call("{\"frist\": 2}"));
		}
		finally {
			api.stop(0);
		}

		// The misspelt name is among the variables the API received, and the name the
		// operation declares is absent, so the API decides what the call means.
		JsonNode variables = JsonMapper.builder().build().readTree(body.get()).path("variables");
		assertThat(variables.path("frist").asInt()).isEqualTo(2);
		assertThat(variables.has("first")).isFalse();
	}

	@Test
	void call_misspeltNameOfARequiredArgument_shouldGetTheApisOwnError() {
		this.contextRunner
			.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl(),
					"gatool.in-process.operations.locations=classpath:gatool/colliding-inputs/")
			.run((requiredContext) -> {
				ToolCallback movieYear = Arrays
					.stream(requiredContext.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks())
					.filter((callback) -> callback.getToolDefinition().name().equals("movieYear"))
					.findFirst()
					.orElseThrow();
				int before = MoviesApiServer.graphQlCalls();

				String text = movieYear.call("{\"nodeId\": \"movie-1\"}");

				// MovieYear declares $nodeID and requires it. The spelling sent here is
				// one the operation leaves undeclared, so the API receives the call
				// without the variable it requires and answers with its own error, which
				// comes back as the result text.
				assertThat(MoviesApiServer.graphQlCalls()).as("calls the API counted after the call")
					.isGreaterThan(before);
				assertThat(text).startsWith("{\"errors\":[").contains("Variable 'nodeID'");
			});
	}

	@Test
	void call_argumentTextThatFailsToParse_shouldThrowToolExecutionExceptionForTheModel() {
		ToolCallback callback = onlyCallback(this.tools.toolCallbackProvider());

		// Spring AI's tool calling manager catches this one type and hands it to the
		// exception processor, which answers the model with text it can act on. A failure
		// of another type would reach the caller of the ChatClient and leave the model
		// waiting, which is what a tool written by hand avoids.
		assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> callback.call("{\"first\": "))
			.satisfies((failure) -> assertThat(failure.getMessage()).isNotBlank());
	}

	@Test
	void executeToolCalls_toolCallWithEmptyArguments_shouldReturnTheSameText() {
		ToolCallback callback = onlyCallback(this.tools.toolCallbackProvider());
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(callback).build();
		Prompt prompt = new Prompt(new UserMessage("Which movies rate highest?"), options);
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "topRatedMovies", "")))
			.build();
		ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));

		ToolExecutionResult result = ToolCallingManager.builder().build().executeToolCalls(prompt, chatResponse);

		ToolResponseMessage responses = (ToolResponseMessage) result.conversationHistory().getLast();
		assertThat(responses.getResponses()).singleElement()
			.satisfies((response) -> assertThat(response.responseData()).isEqualTo(TOP_RATED_MOVIES_RESULT));
	}

	@Test
	void getToolCallbacks_readTwice_shouldBuildBothReadsFromTheCurrentCatalogue() {
		ToolCallbackProvider provider = this.tools.toolCallbackProvider();

		ToolCallback[] first = provider.getToolCallbacks();
		ToolCallback[] second = provider.getToolCallbacks();

		assertThat(first).isNotSameAs(second);
		for (ToolCallback[] callbacks : List.of(first, second)) {
			assertThat(callbacks).singleElement().satisfies((callback) -> {
				assertThat(callback.getToolDefinition().name()).isEqualTo("topRatedMovies");
				assertThat(callback.getToolDefinition().description())
					.isEqualTo("Returns the highest-rated movies, best first.");
				assertThat(callback.getToolDefinition().inputSchema()).isEqualTo(INPUT_SCHEMA);
			});
		}
	}

	@Test
	void call_apiOnAClosedPort_shouldThrowToolExecutionExceptionCarryingTheStartersMessage() {
		this.contextRunner.run((closedPortContext) -> {
			ToolCallback callback = onlyCallback(
					closedPortContext.getBean(GAToolCallbacks.class).toolCallbackProvider());

			assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> callback.call("{}"))
				.satisfies((failure) -> assertThat(failure.getMessage()).contains("topRatedMovies")
					.doesNotContain("Exception"));
		});
	}

	@Test
	void mcpToolSpecifications_bothStartersInOneContext_shouldListTheMcpToolAlone() {
		assertThat(this.context.getBeanNamesForType(ToolCallback.class)).isEmpty();
		assertThat(this.context.getBeanNamesForType(ToolCallbackProvider.class)).isEmpty();
		ResolvableType specificationList = ResolvableType.forClassWithGenerics(List.class,
				McpStatelessServerFeatures.SyncToolSpecification.class);

		List<String> toolNames = new ArrayList<>();
		for (String beanName : this.context.getBeanNamesForType(specificationList)) {
			@SuppressWarnings("unchecked")
			List<McpStatelessServerFeatures.SyncToolSpecification> specifications = (List<McpStatelessServerFeatures.SyncToolSpecification>) this.context
				.getBean(beanName);
			specifications.forEach((specification) -> toolNames.add(specification.tool().name()));
		}

		assertThat(toolNames).containsExactly("topRatedMovies");
	}

	@Test
	void inputSchema_bothStartersInOneContext_shouldCarryTheIdOnTheMcpSideAlone() {
		ResolvableType specificationList = ResolvableType.forClassWithGenerics(List.class,
				McpStatelessServerFeatures.SyncToolSpecification.class);
		List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
		for (String beanName : this.context.getBeanNamesForType(specificationList)) {
			@SuppressWarnings("unchecked")
			List<McpStatelessServerFeatures.SyncToolSpecification> found = (List<McpStatelessServerFeatures.SyncToolSpecification>) this.context
				.getBean(beanName);
			specifications.addAll(found);
		}

		String inProcessText = onlyCallback(this.tools.toolCallbackProvider()).getToolDefinition().inputSchema();

		// The $id is the key the MCP Java SDK's validator keeps a compiled schema under,
		// and that validator runs on the MCP side alone. The in-process text goes to a
		// model provider, so it stays the text the writer produced.
		assertThat(specifications).singleElement()
			.satisfies((specification) -> assertThat(specification.tool().inputSchema()).containsKey("$id"));
		assertThat(inProcessText).isEqualTo(INPUT_SCHEMA).doesNotContain("$id");
	}

	private static ToolCallback onlyCallback(ToolCallbackProvider provider) {
		ToolCallback[] callbacks = provider.getToolCallbacks();
		assertThat(callbacks).hasSize(1);
		return callbacks[0];
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
