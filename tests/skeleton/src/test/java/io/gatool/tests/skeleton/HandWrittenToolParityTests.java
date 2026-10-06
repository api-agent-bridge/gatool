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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Compares a GATool tool with a tool the application writes by hand, so that the
 * differences a {@code ChatClient} and a model can observe stay visible.
 *
 * <p>
 * The hand-written tool declares the name, the description and the argument description
 * of the operation file beside it. Every difference these tests record therefore comes
 * from how each side is built, and a change in either Spring AI or GATool that widens the
 * gap fails a test here.
 *
 * <p>
 * A model reads three fields of a {@code ToolDefinition}, which are the name, the
 * description and the input schema. Both provider adapters of Spring AI 2.0.1 parse the
 * input schema text into a map before the request goes out, so the whitespace of that
 * text stays on this side while its keys travel.
 *
 * <p>
 * The API runs on a closed port here, because a tool definition is built at startup from
 * the schema file, and the calls these tests make fail while the arguments are parsed,
 * which happens before any request.
 */
class HandWrittenToolParityTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String API_URL = "gatool.api.url=http://localhost:1/graphql";

	private static final String TOOL_NAME = "topRatedMovies";

	private static final String DESCRIPTION = "Returns the highest-rated movies, best first.";

	private static final String ARGUMENT_DESCRIPTION = "How many movies to return.";

	// Argument text that stops during parsing, which is where the two sides part.
	private static final String BROKEN_ARGUMENTS = "{\"first\": ";

	private static final String RESULT = "{\"data\":{\"topRatedMovies\":[]}}";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
		.withPropertyValues(API_URL, SCHEMA);

	@Test
	void toolDefinition_gaToolAndAnnotatedMethod_shouldShareTheDefinitionClassTheNameAndTheDescription() {
		this.contextRunner.run((context) -> {
			ToolDefinition gaToolDefinition = gaToolCallback(context).getToolDefinition();
			ToolDefinition handWritten = handWrittenCallback().getToolDefinition();

			// Both sides build the same record, so a client reading the definition type
			// reads one class for both tools.
			assertThat(gaToolDefinition).isInstanceOf(DefaultToolDefinition.class);
			assertThat(handWritten).isInstanceOf(DefaultToolDefinition.class);
			assertThat(gaToolDefinition.name()).isEqualTo(TOOL_NAME).isEqualTo(handWritten.name());
			assertThat(gaToolDefinition.description()).isEqualTo(DESCRIPTION).isEqualTo(handWritten.description());
		});
	}

	@Test
	void inputSchema_gaToolAndAnnotatedMethod_shouldDescribeTheSameArgumentInDifferentText() {
		this.contextRunner.run((context) -> {
			String gaToolText = gaToolCallback(context).getToolDefinition().inputSchema();
			String handWrittenText = handWrittenCallback().getToolDefinition().inputSchema();

			// Both sides write the $schema keyword. Spring AI's generator pretty-prints
			// its text and GATool writes one line, and each provider adapter parses the
			// text into a map before the request goes out, so the whitespace stays on
			// this side.
			assertThat(gaToolText).contains("\"$schema\"").doesNotContain("\n");
			assertThat(handWrittenText).contains("\"$schema\"").contains("\n");
			assertThat(JSON.readTree(gaToolText).get("$schema").asString())
				.isEqualTo(JSON.readTree(handWrittenText).get("$schema").asString());

			JsonNode gaToolSchema = JSON.readTree(gaToolText);
			JsonNode handWrittenSchema = JSON.readTree(handWrittenText);
			assertThat(gaToolSchema.get("type").asString()).isEqualTo(handWrittenSchema.get("type").asString());
			assertThat(gaToolSchema.get("additionalProperties").asBoolean())
				.isEqualTo(handWrittenSchema.get("additionalProperties").asBoolean());
			assertThat(gaToolSchema.get("required").size()).isEqualTo(handWrittenSchema.get("required").size());

			JsonNode gaToolArgument = gaToolSchema.get("properties").get("first");
			JsonNode handWrittenArgument = handWrittenSchema.get("properties").get("first");
			assertThat(gaToolArgument.get("description").asString()).isEqualTo(ARGUMENT_DESCRIPTION)
				.isEqualTo(handWrittenArgument.get("description").asString());
			// A nullable argument reaches the model in two shapes. GATool writes anyOf
			// over the type and null, and Spring AI's generator writes the type on its
			// own.
			assertThat(gaToolArgument.has("anyOf")).isTrue();
			assertThat(gaToolArgument.has("type")).isFalse();
			assertThat(handWrittenArgument.get("type").asString()).isEqualTo("integer");
		});
	}

	@Test
	void toolMetadata_gaToolAndAnnotatedMethodTakingTheDefault_shouldBothReportReturnDirectFalse() {
		this.contextRunner.run((context) -> {
			assertThat(gaToolCallback(context).getToolMetadata().returnDirect()).isFalse();
			assertThat(handWrittenCallback().getToolMetadata().returnDirect()).isFalse();
		});
	}

	@Test
	void toolMetadata_annotatedMethodAskingForReturnDirect_shouldDifferFromEveryGATool() {
		this.contextRunner.run((context) -> {
			ToolCallback returnsDirectly = ToolCallbacks.from(new ReturnDirectTool())[0];

			// The application decides this for its own tools, and every GATool tool keeps
			// the default, so a result from GATool always travels back to the model.
			assertThat(returnsDirectly.getToolMetadata().returnDirect()).isTrue();
			assertThat(gaToolCallback(context).getToolMetadata().returnDirect()).isFalse();
		});
	}

	@Test
	void toolCallback_gaToolAndAnnotatedMethod_shouldCarryTheSameDefinitionFromDifferentClasses() {
		this.contextRunner.run((context) -> {
			ToolCallback gaTool = gaToolCallback(context);
			ToolCallback handWritten = handWrittenCallback();

			// Each side is built by its own class, which code holding the callback reads
			// apart and which stays on this side of the request. What the model reads is
			// the same on both: one definition type, and the same metadata.
			assertThat(gaTool).isInstanceOf(ToolCallback.class);
			assertThat(handWritten).isInstanceOf(MethodToolCallback.class);
			assertThat(gaTool.getClass()).isNotEqualTo(handWritten.getClass());
			assertThat(gaTool.getToolDefinition()).isInstanceOf(DefaultToolDefinition.class);
			assertThat(handWritten.getToolDefinition()).isInstanceOf(DefaultToolDefinition.class);
			assertThat(gaTool.getToolMetadata().returnDirect()).isEqualTo(handWritten.getToolMetadata().returnDirect());
		});
	}

	@Test
	void call_argumentTextThatFailsToParse_shouldThrowTheSameExceptionOnBothSides() {
		this.contextRunner.run((context) -> {
			ToolCallback handWritten = handWrittenCallback();
			ToolCallback gaTool = gaToolCallback(context);

			// Both sides answer a parsing failure with the one exception type that Spring
			// AI's tool calling manager catches, so the exception processor turns each
			// into a tool error the model reads and corrects.
			assertThatExceptionOfType(ToolExecutionException.class)
				.isThrownBy(() -> handWritten.call(BROKEN_ARGUMENTS));
			assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> gaTool.call(BROKEN_ARGUMENTS));
		});
	}

	@Test
	void executeToolCalls_argumentTextThatFailsToParse_shouldAnswerTheModelOnBothSides() {
		this.contextRunner.run((context) -> {
			ToolCallingManager manager = ToolCallingManager.builder().build();

			ToolExecutionResult handWritten = manager.executeToolCalls(promptFor(handWrittenCallback()),
					chatResponse());
			ToolExecutionResult gaTool = manager.executeToolCalls(promptFor(gaToolCallback(context)), chatResponse());

			// A model that sends an argument it got wrong reads a tool error from either
			// side, and can correct it and call again. This is the guarantee that lets a
			// tool GATool builds replace one written by hand.
			for (ToolExecutionResult result : List.of(handWritten, gaTool)) {
				ToolResponseMessage responses = (ToolResponseMessage) result.conversationHistory().getLast();
				assertThat(responses.getResponses()).singleElement()
					.satisfies((response) -> assertThat(response.responseData()).isNotBlank());
			}
		});
	}

	@Test
	void executeToolCalls_throwExceptionOnErrorSet_shouldThrowOnBothSides() {
		this.contextRunner.run((context) -> {
			// spring.ai.tools.throw-exception-on-error feeds alwaysThrow on Spring AI's
			// exception processor, and the processor reads ToolExecutionException alone.
			// A
			// application that turns it on asks for failures to reach the caller, and a
			// GATool tool
			// honours that because it raises the type the processor reads.
			ToolCallingManager throwing = DefaultToolCallingManager.builder()
				.toolExecutionExceptionProcessor(
						DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
				.build();
			Prompt handWritten = promptFor(handWrittenCallback());
			Prompt gaTool = promptFor(gaToolCallback(context));
			// A response each, so that whether the manager reads its argument or writes
			// to it cannot decide what the second side sees. This test exists to compare
			// the two sides, and sharing one would give them something to share.
			ChatResponse brokenHandWrittenCall = chatResponse();
			ChatResponse brokenGAToolCall = chatResponse();

			assertThatExceptionOfType(ToolExecutionException.class)
				.isThrownBy(() -> throwing.executeToolCalls(handWritten, brokenHandWrittenCall));
			assertThatExceptionOfType(ToolExecutionException.class)
				.isThrownBy(() -> throwing.executeToolCalls(gaTool, brokenGAToolCall));
		});
	}

	@Test
	void call_annotatedMethodReturningJsonText_shouldForwardTheTextUnchanged() {
		// Spring AI's result converter forwards a String that already holds valid JSON,
		// and it reads the method's return type for the annotated tool alone. A String
		// return therefore travels unchanged on both sides, as the in-process
		// end-to-end test asserts for the GATool side.
		assertThat(handWrittenCallback().call("{\"first\": 2}")).isEqualTo(RESULT);
	}

	private static ToolCallback gaToolCallback(AssertableApplicationContext context) {
		ToolCallback[] callbacks = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks();
		assertThat(callbacks).hasSize(1);
		return callbacks[0];
	}

	private static ToolCallback handWrittenCallback() {
		ToolCallback[] callbacks = ToolCallbacks.from(new HandWrittenTool());
		assertThat(callbacks).hasSize(1);
		return callbacks[0];
	}

	private static Prompt promptFor(ToolCallback callback) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(callback).build();
		return new Prompt(new UserMessage("Which movies rate highest?"), options);
	}

	private static ChatResponse chatResponse() {
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", TOOL_NAME, BROKEN_ARGUMENTS)))
			.build();
		return new ChatResponse(List.of(new Generation(assistantMessage)));
	}

	// The name, the description and the argument description of the operation file
	// beside this test, so the comparison shows how each side builds a tool.
	static final class HandWrittenTool {

		@Tool(name = TOOL_NAME, description = DESCRIPTION)
		String topRatedMovies(@ToolParam(required = false, description = ARGUMENT_DESCRIPTION) Integer first) {
			return RESULT;
		}

	}

	static final class ReturnDirectTool {

		@Tool(name = "weatherToday", description = "Returns today's weather.", returnDirect = true)
		String weatherToday() {
			return RESULT;
		}

	}

}
