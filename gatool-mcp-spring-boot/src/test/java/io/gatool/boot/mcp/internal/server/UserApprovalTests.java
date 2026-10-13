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

package io.gatool.boot.mcp.internal.server;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

// The capture reads the lines UserApproval logs, which also reach the build output.
@ExtendWith(OutputCaptureExtension.class)
class UserApprovalTests {

	private static final ElicitationSettings SETTINGS = new ElicitationSettings(true,
			"The user declined the call to {tool}, so the API was not called. Ask the user what to do next.",
			"The user dismissed the confirmation for {tool} without answering, so the API was not called. "
					+ "Ask the user whether to try again.",
			Duration.ofSeconds(20));

	private static final String UNSUPPORTED_ANSWER = "The tool topRatedMovies asks the user before it runs, and "
			+ "this client did not declare the form elicitation capability, so the API was not called. Connect with "
			+ "a client that supports MCP form elicitation.";

	@Test
	void ask_clientThatAccepts_shouldSendTheQuestionWithTheArgumentsAndAccept() {
		FakeLoggableSession session = FakeLoggableSession.answering(McpSchema.ElicitResult.Action.ACCEPT);

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of("first", 3),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation().build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.ACCEPTED);
		assertThat(session.requests).hasValue(1);
		McpSchema.ElicitFormRequest sent = (McpSchema.ElicitFormRequest) session.lastParams.get();
		assertThat(sent.message()).isEqualTo("Read the movies?\n\n{\"first\":3}");
		assertThat(sent.requestedSchema()).isEqualTo(Map.of("type", "object", "properties", Map.of()));
		// The keys keep the order they were put in, so the wire form is the same text on
		// every run of the JVM.
		assertThat(JsonMapper.builder().build().writeValueAsString(sent.requestedSchema()))
			.isEqualTo("{\"type\":\"object\",\"properties\":{}}");
	}

	@Test
	void ask_clientThatDeclines_shouldAnswerTheDeclinedSentenceWithTheToolName() {
		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(FakeLoggableSession.answering(McpSchema.ElicitResult.Action.DECLINE),
						McpSchema.ClientCapabilities.builder().elicitation().build()),
				SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.DECLINED);
		assertThat(verdict.outcome()).isEqualTo("elicitation-declined");
		assertThat(verdict.answer()).isEqualTo(
				"The user declined the call to topRatedMovies, so the API was not called. Ask the user what to do next.");
	}

	@Test
	void ask_clientThatCancels_shouldAnswerTheCancelledSentence() {
		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(FakeLoggableSession.answering(McpSchema.ElicitResult.Action.CANCEL),
						McpSchema.ClientCapabilities.builder().elicitation().build()),
				SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.CANCELLED);
		assertThat(verdict.outcome()).isEqualTo("elicitation-cancelled");
		assertThat(verdict.answer()).startsWith("The user dismissed the confirmation for topRatedMovies");
	}

	@Test
	void ask_cancelledUnderABlankAnswer_shouldFallBackToTheDefault() {
		ElicitationSettings blank = new ElicitationSettings(true, "No.", " ", Duration.ofSeconds(20));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(FakeLoggableSession.answering(McpSchema.ElicitResult.Action.CANCEL),
						McpSchema.ClientCapabilities.builder().elicitation().build()),
				blank);

		assertThat(verdict.answer()).isEqualTo("The user dismissed the confirmation for topRatedMovies without "
				+ "answering, so the API was not called. Ask the user whether to try again.");
	}

	@Test
	void ask_declinedUnderAnAnswerNamingTheToolTwice_shouldNameItInBothPlaces() {
		ElicitationSettings twice = new ElicitationSettings(true,
				"{tool} was declined. Ask before calling {tool} again.", "Later.", Duration.ofSeconds(20));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(FakeLoggableSession.answering(McpSchema.ElicitResult.Action.DECLINE),
						McpSchema.ClientCapabilities.builder().elicitation().build()),
				twice);

		assertThat(verdict.answer()).isEqualTo("topRatedMovies was declined. Ask before calling topRatedMovies again.");
	}

	@Test
	void ask_clientWithoutTheElicitationCapability_shouldAnswerUnsupportedWithoutSending() {
		FakeLoggableSession session = FakeLoggableSession.answering(McpSchema.ElicitResult.Action.ACCEPT);

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.UNSUPPORTED);
		assertThat(verdict.outcome()).isEqualTo("elicitation-unsupported");
		assertThat(verdict.answer()).isEqualTo(UNSUPPORTED_ANSWER);
		assertThat(session.requests).hasValue(0);
	}

	@Test
	void ask_clientWithUrlElicitationOnly_shouldAnswerUnsupported() {
		FakeLoggableSession session = FakeLoggableSession.answering(McpSchema.ElicitResult.Action.ACCEPT);

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation(false, true).build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.UNSUPPORTED);
		assertThat(verdict.outcome()).isEqualTo("elicitation-unsupported");
		assertThat(verdict.answer()).isEqualTo(UNSUPPORTED_ANSWER);
		assertThat(session.requests).hasValue(0);
	}

	@Test
	void ask_clientWithFormAndUrlElicitation_shouldAsk() {
		FakeLoggableSession session = FakeLoggableSession.answering(McpSchema.ElicitResult.Action.ACCEPT);

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation(true, true).build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.ACCEPTED);
		assertThat(session.requests).hasValue(1);
	}

	@Test
	void ask_withoutAnExchange_shouldAnswerUnsupported() {
		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(), null, SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.UNSUPPORTED);
	}

	@Test
	void ask_answerThatTimesOut_shouldNameTheTimeoutAndTheProperty(CapturedOutput output) {
		FakeLoggableSession session = new FakeLoggableSession(() -> Mono.error(new TimeoutException("5000ms")));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation().build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.TIMED_OUT);
		assertThat(verdict.outcome()).isEqualTo("elicitation-timeout");
		assertThat(verdict.answer()).isEqualTo("The user did not answer the confirmation for topRatedMovies within "
				+ "20 seconds (spring.ai.mcp.server.request-timeout), so the API was not called.");
		// A timeout is an answer the user did not give in time, so it stays out of the
		// ERROR line a failed confirmation writes.
		assertThat(output).doesNotContain("The confirmation for topRatedMovies failed");
	}

	@Test
	void ask_answerThatTimesOutAfterOneSecond_shouldNameTheTimeoutInTheSingular() {
		FakeLoggableSession session = new FakeLoggableSession(() -> Mono.error(new TimeoutException("1000ms")));
		ElicitationSettings oneSecond = new ElicitationSettings(true, "No.", "Later.", Duration.ofSeconds(1));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation().build()), oneSecond);

		assertThat(verdict.answer()).isEqualTo("The user did not answer the confirmation for topRatedMovies within "
				+ "1 second (spring.ai.mcp.server.request-timeout), so the API was not called.");
	}

	@Test
	void ask_sessionThatFails_shouldAnswerFailedWithoutTheCause(CapturedOutput output) {
		FakeLoggableSession session = new FakeLoggableSession(
				() -> Mono.error(new IllegalStateException("secret internals")));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation().build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.FAILED);
		assertThat(verdict.outcome()).isEqualTo("elicitation-failed");
		assertThat(verdict.answer()).isEqualTo("The confirmation for topRatedMovies failed before the user could "
				+ "answer, so the API was not called.");
		// The log carries the cause for the operator, and the model reads the sentence
		// above alone.
		assertThat(output).contains("ERROR")
			.contains("The confirmation for topRatedMovies failed before the user could answer.")
			.contains("java.lang.IllegalStateException: secret internals");
		assertThat(verdict.answer()).doesNotContain("secret internals").doesNotContain("IllegalStateException");
	}

	// The timeout runs the test on a thread of its own, so a walk that loops fails the
	// test after 10 seconds.
	@Test
	@Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
	void ask_failureWhoseCausesFormALoop_shouldEndTheWalkAndAnswerFailed() {
		// initCause accepts a chain that leads back to an earlier link, and the walk for
		// a timeout among the causes reads each link once.
		IllegalStateException first = new IllegalStateException("first");
		IllegalArgumentException second = new IllegalArgumentException("second");
		first.initCause(second);
		second.initCause(first);
		FakeLoggableSession session = new FakeLoggableSession(() -> Mono.error(first));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation().build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.FAILED);
	}

	@Test
	@Timeout(value = 10, threadMode = ThreadMode.SEPARATE_THREAD)
	void ask_timeoutInsideALoopOfCauses_shouldAnswerTimedOut() {
		RuntimeException wrapper = new RuntimeException("wrapper");
		TimeoutException timeout = new TimeoutException("20000ms");
		wrapper.initCause(timeout);
		timeout.initCause(wrapper);
		FakeLoggableSession session = new FakeLoggableSession(() -> Mono.error(wrapper));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(session, McpSchema.ClientCapabilities.builder().elicitation().build()), SETTINGS);

		assertThat(verdict.decision()).isEqualTo(UserApproval.Decision.TIMED_OUT);
	}

	@Test
	void ask_declinedUnderACustomAnswerWithoutThePlaceholder_shouldReadItAsWritten() {
		ElicitationSettings custom = new ElicitationSettings(true, "No.", "Later.", Duration.ofSeconds(20));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(FakeLoggableSession.answering(McpSchema.ElicitResult.Action.DECLINE),
						McpSchema.ClientCapabilities.builder().elicitation().build()),
				custom);

		assertThat(verdict.answer()).isEqualTo("No.");
	}

	@Test
	void ask_declinedUnderABlankAnswer_shouldFallBackToTheDefault() {
		ElicitationSettings blank = new ElicitationSettings(true, "  ", "", Duration.ofSeconds(20));

		UserApproval.Verdict verdict = UserApproval.ask(tool("Read the movies?"), Map.of(),
				exchange(FakeLoggableSession.answering(McpSchema.ElicitResult.Action.DECLINE),
						McpSchema.ClientCapabilities.builder().elicitation().build()),
				blank);

		assertThat(verdict.answer()).startsWith("The user declined the call to topRatedMovies");
	}

	@Test
	void message_withoutArguments_shouldBeTheQuestionAlone() {
		assertThat(UserApproval.message("Read the movies?", Map.of())).isEqualTo("Read the movies?");
	}

	@Test
	void message_longArguments_shouldCutAtOneThousandCharacters() {
		String message = UserApproval.message("Read?", Map.of("text", "x".repeat(5_000)));

		assertThat(message).startsWith("Read?\n\n{\"text\":\"xxx").endsWith(" [cut]");
		assertThat(message.length()).isEqualTo("Read?\n\n".length() + 1_000 + " [cut]".length());
	}

	@Test
	void message_argumentsWithABearerToken_shouldRedactIt() {
		String message = UserApproval.message("Read?", Map.of("note", "Bearer abc.def-ghi please"));

		assertThat(message).contains("Bearer [redacted]").doesNotContain("abc.def");
	}

	@Test
	void message_bearerTokenAfterATab_shouldRedactItAsJsonWritesTheTab() {
		// Jackson writes the tab as the two characters \t.
		String message = UserApproval.message("Read?", Map.of("note", "Bearer\tabc.def-ghi please"));

		assertThat(message).isEqualTo("Read?\n\n{\"note\":\"Bearer [redacted] please\"}");
	}

	@Test
	void message_bearerTokenAfterALineFeed_shouldRedactItAsJsonWritesTheLineFeed() {
		String message = UserApproval.message("Read?", Map.of("note", "Bearer\nabc.def-ghi please"));

		assertThat(message).isEqualTo("Read?\n\n{\"note\":\"Bearer [redacted] please\"}");
	}

	@Test
	void message_bearerTokenAfterAFormFeed_shouldRedactItAsJsonWritesTheFormFeed() {
		// Jackson writes the form feed as the two characters \f.
		String message = UserApproval.message("Read?", Map.of("note", "Bearer\fabc.def-ghi please"));

		assertThat(message).isEqualTo("Read?\n\n{\"note\":\"Bearer [redacted] please\"}");
	}

	@Test
	void message_bearerTokenAfterAnEncodedSpace_shouldRedactIt() {
		String message = UserApproval.message("Read?", Map.of("note", "Bearer%20abc.def-ghi please"));

		assertThat(message).isEqualTo("Read?\n\n{\"note\":\"Bearer [redacted] please\"}");
	}

	@Test
	void message_argumentsJacksonCannotWrite_shouldFallBackToTheQuestion(CapturedOutput output) {
		Map<String, @Nullable Object> arguments = new LinkedHashMap<>();
		arguments.put("self", arguments);

		assertThat(UserApproval.message("Read?", arguments)).isEqualTo("Read?");
		assertThat(output).contains("WARN")
			.contains("The arguments of a call could not be written for the user to read, so the question is shown "
					+ "alone.");
	}

	private static GATool tool(String question) {
		return GATool.builder()
			.name("topRatedMovies")
			.inputSchema("{\"type\":\"object\",\"properties\":{}}")
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.elicit(question)
			.build();
	}

	static McpSyncServerExchange exchange(FakeLoggableSession session, McpSchema.ClientCapabilities capabilities) {
		return new McpSyncServerExchange(new McpAsyncServerExchange("session-1", session, capabilities,
				McpSchema.Implementation.builder("test-client", "1").build(), McpTransportContext.EMPTY,
				new DefaultJsonSchemaValidator()));
	}

}
