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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An in-process mutation and the approval of a person.
 *
 * <p>
 * Spring AI's {@code ToolCallingManager} calls the callback a model's response names, and
 * GATool's callback sends the mutation, so a mutation runs as soon as the model asks for
 * it. An application that wants a person to agree first puts that into its own code.
 * These tests pin both halves: the call that runs at once, and a callback of the
 * application's own around GATool's, which finds the tools that write in the catalog and
 * sends a call on once the application agreed.
 */
@SpringBootTest(classes = InProcessMutationApprovalTests.SkeletonApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.in-process.operations.locations=classpath:gatool/mutation/",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class InProcessMutationApprovalTests {

	private static final String ARGUMENTS = "{\"input\":{\"movieId\":\"movie-3\",\"score\":6}}";

	private static final Pattern REVIEW_ID = Pattern.compile("\"id\":\"review-(\\d+)\"");

	@Autowired
	private GAToolCallbacks tools;

	@Autowired
	private GAToolCatalog catalog;

	@DynamicPropertySource
	static void moviesApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void executeToolCalls_mutationTheModelNamed_shouldRunItAtOnce() {
		ToolCallback addReview = this.tools.toolCallbackProvider().getToolCallbacks()[0];

		String answer = answerOf(addReview);

		assertThat(REVIEW_ID.matcher(answer).find()).as(answer).isTrue();
	}

	@Test
	void executeToolCalls_mutationBehindACallbackOfTheApplication_shouldReachTheApiOnceTheApplicationAgreed() {
		Set<String> writes = this.catalog.inProcessTools()
			.stream()
			.filter((tool) -> !tool.readOnly())
			.map(GATool::name)
			.collect(Collectors.toSet());
		assertThat(writes).containsExactly("addReview");
		AtomicBoolean agreed = new AtomicBoolean(true);
		ToolCallback addReview = Arrays.stream(this.tools.toolCallbackProvider().getToolCallbacks())
			.map((callback) -> writes.contains(callback.getToolDefinition().name()) ? new AskingFirst(callback, agreed)
					: callback)
			.findFirst()
			.orElseThrow();

		int before = reviewNumberOf(answerOf(addReview));
		agreed.set(false);
		String refused = answerOf(addReview);
		agreed.set(true);
		int after = reviewNumberOf(answerOf(addReview));

		assertThat(refused).isEqualTo(AskingFirst.REFUSAL);
		// The API numbers its reviews in the order it stores them, so the refused call
		// would have taken the number between the two.
		assertThat(after).isEqualTo(before + 1);
	}

	// What Spring AI does with a model's response that names the tool: the manager finds
	// the callback among the options of the prompt and calls it.
	private static String answerOf(ToolCallback callback) {
		ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(callback).build();
		Prompt prompt = new Prompt(new UserMessage("Add a review of six to Midnight Recipe."), options);
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "addReview", ARGUMENTS)))
			.build();
		ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
		ToolResponseMessage responses = (ToolResponseMessage) ToolCallingManager.builder()
			.build()
			.executeToolCalls(prompt, chatResponse)
			.conversationHistory()
			.getLast();
		return responses.getResponses().getFirst().responseData();
	}

	private static int reviewNumberOf(String answer) {
		Matcher matcher = REVIEW_ID.matcher(answer);
		assertThat(matcher.find()).as(answer).isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	/**
	 * A callback of the application's own: it answers for the tool it wraps and sends a
	 * call on once the application agreed, which here is a flag and in an application is
	 * whatever asks its user.
	 */
	private record AskingFirst(ToolCallback delegate, AtomicBoolean agreed) implements ToolCallback {

		static final String REFUSAL = "The user declined this call, so it was left unsent.";

		@Override
		public ToolDefinition getToolDefinition() {
			return this.delegate.getToolDefinition();
		}

		@Override
		public String call(String toolInput) {
			return this.agreed.get() ? this.delegate.call(toolInput) : REFUSAL;
		}

		@Override
		public String call(String toolInput, @Nullable ToolContext toolContext) {
			return call(toolInput);
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
