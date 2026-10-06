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

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.boot.mcp.limit.ToolRateLimiter;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Every ending of an MCP tool call is a {@code CallToolResult}.
 *
 * <p>
 * An {@code Error} is not a {@code RuntimeException}, so without this guard an
 * {@code OutOfMemoryError} raised while a large response is being written would travel to
 * the servlet, and the client would read Spring Boot's own 500 body, which lacks the
 * {@code jsonrpc} member, the {@code id} and the {@code error} object. The compliance
 * filter cannot reach that answer, since it is produced below it. A failing observation
 * handler and a failing rate limiter belong to the same family: an application owns both,
 * and neither should take the protocol down.
 */
class McpToolSpecificationsGuardTests {

	private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

	private static final ToolRateLimiter ALLOW_EVERY_CALL = (caller, toolName) -> RateLimitDecision.allow();

	@Test
	void call_toolRaisingAnError_shouldStillAnswerWithAToolError() {
		McpSchema.CallToolResult result = call((arguments) -> {
			throw new OutOfMemoryError("Java heap space");
		}, ALLOW_EVERY_CALL, ObservationRegistry.NOOP);

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).contains("topRatedMovies").contains("did not complete");
	}

	@Test
	void call_rateLimiterThatThrows_shouldAnswerWithAToolError() {
		ToolRateLimiter broken = (caller, toolName) -> {
			throw new IllegalStateException("the shared store is unreachable");
		};

		McpSchema.CallToolResult result = call((arguments) -> new ToolCallOutcome("{\"data\":{}}", false), broken,
				ObservationRegistry.NOOP);

		assertThat(result.isError()).isTrue();
		assertThat(text(result)).contains("topRatedMovies");
	}

	@Test
	void call_observationHandlerThatThrowsOnStop_shouldKeepTheAnswerTheModelWasGoingToRead() {
		McpSchema.CallToolResult result = call((arguments) -> new ToolCallOutcome("{\"data\":{\"ok\":true}}", false),
				ALLOW_EVERY_CALL, registryFailingOn(false, true));

		// Telemetry sits around a tool call, so a handler failing after the work is
		// done leaves the result intact.
		assertThat(result.isError()).isFalse();
		assertThat(text(result)).contains("\"ok\":true");
	}

	@Test
	void call_observationHandlerThatThrowsOnStart_shouldStillRunTheCall() {
		McpSchema.CallToolResult result = call((arguments) -> new ToolCallOutcome("{\"data\":{\"ok\":true}}", false),
				ALLOW_EVERY_CALL, registryFailingOn(true, false));

		// A handler that fails on start leaves observe(...) before the call ran.
		// Answering a tool error there would let a metrics backend that is unreachable
		// skip every tool call, where telemetry sits around a call and stays out of its
		// answer, so the call runs outside the failed observation.
		assertThat(result.isError()).isFalse();
		assertThat(text(result)).contains("\"ok\":true");
	}

	@Test
	void call_failedCallUnderAnObservationHandlerThatThrowsOnError_shouldRunTheToolOnce() {
		AtomicInteger runs = new AtomicInteger();

		call((arguments) -> {
			runs.incrementAndGet();
			throw new ToolCallFailedException(
					"The tool topRatedMovies sent its request and the API did not answer in time.",
					new IllegalStateException("Read timed out"));
		}, ALLOW_EVERY_CALL, registryFailingOnError());

		// The call outside the observation is for a handler that failed before the call
		// began. A handler failing while the error of a call is recorded stays out of it,
		// because a mutation that timed out after the API applied it would be sent a
		// second time inside one tools/call.
		assertThat(runs.get()).isEqualTo(1);
	}

	@Test
	void call_failedCallUnderAnObservationHandlerThatThrowsOnError_shouldKeepTheSentenceOfTheFailure() {
		McpSchema.CallToolResult result = call((arguments) -> {
			throw new ToolCallFailedException(
					"The tool topRatedMovies sent its request and the API did not answer in time.",
					new IllegalStateException("Read timed out"));
		}, ALLOW_EVERY_CALL, registryFailingOnError());

		// The sentence of a failed call tells the model what happened to the request,
		// which matters most for a write, so a failing handler leaves it in the answer.
		assertThat(result.isError()).isTrue();
		assertThat(text(result))
			.isEqualTo("The tool topRatedMovies sent its request and the API did not answer in time.");
	}

	@Test
	void call_rateLimiterThatThrows_shouldRecordTheFailureOnTheObservation() {
		AtomicReference<@Nullable Throwable> recorded = new AtomicReference<>();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onError(Observation.Context context) {
				recorded.set(context.getError());
			}
		});
		ToolRateLimiter broken = (caller, toolName) -> {
			throw new IllegalStateException("the shared store is unreachable");
		};

		McpSchema.CallToolResult result = call((arguments) -> new ToolCallOutcome("{\"data\":{}}", false), broken,
				registry);

		// The guard turns the failure into a tool error, and an observation ending
		// without it would leave a trace showing a failed call with the cause left out.
		assertThat(result.isError()).isTrue();
		assertThat(recorded.get()).isInstanceOf(IllegalStateException.class)
			.hasMessage("the shared store is unreachable");
	}

	@Test
	void call_toolRaisingAnInternalError_shouldLetItTravelOn() {
		// An InternalError says the JVM itself is corrupted, and a server answering from
		// one answers wrongly, so the guard lets it through where it turns an
		// OutOfMemoryError, which one large response raises, into a tool error.
		assertThatExceptionOfType(InternalError.class).isThrownBy(() -> call((arguments) -> {
			throw new InternalError("the JVM is corrupted");
		}, ALLOW_EVERY_CALL, ObservationRegistry.NOOP));
	}

	@Test
	void call_offTheRequestThreadUnderTheRequestThreadRule_shouldRefuseAheadOfTheLimiterAndTheTool() {
		AtomicReference<@Nullable String> asked = new AtomicReference<>();
		ToolRateLimiter recording = (caller, toolName) -> {
			asked.set(caller);
			return RateLimitDecision.allow();
		};
		AtomicReference<@Nullable String> called = new AtomicReference<>();

		McpSchema.CallToolResult result = call((arguments) -> {
			called.set("the tool ran");
			return new ToolCallOutcome("{\"data\":{}}", false);
		}, recording, ObservationRegistry.NOOP, true);

		// The caller GATool would read on an unmarked thread belongs to whichever request
		// last used it, so the refusal runs before the limiter counts and before the API
		// is called with a credential minted for that caller.
		assertThat(result.isError()).isTrue();
		assertThat(text(result)).isEqualTo("The tool topRatedMovies was not called. This server ran the call on a "
				+ "thread that does not carry the caller, so GATool cannot tell who is calling. The arguments are "
				+ "not the cause, and the same call gets the same answer until the operator of this server "
				+ "corrects its configuration.");
		assertThat(asked.get()).isNull();
		assertThat(called.get()).isNull();
	}

	@Test
	void admit_twoRefusalsInsideOneWindow_shouldWriteOneLineAndCountTheRest() {
		McpToolSpecifications.RefusalWindow window = new McpToolSpecifications.RefusalWindow();
		long opened = 1_000_000_000L;

		assertThat(window.admit(opened)).isEmpty();
		assertThat(window.admit(opened + 1_000)).isNull();
		assertThat(window.admit(opened + 1_001)).isNull();
		assertThat(window.admit(opened + TimeUnit.MINUTES.toNanos(1)))
			.isEqualTo(" GATool left 2 more such refusals for this tool out of the log since its last line.");
	}

	private static McpSchema.CallToolResult call(Function<Map<String, @Nullable Object>, ToolCallOutcome> handler,
			ToolRateLimiter rateLimiter, ObservationRegistry registry) {
		return call(handler, rateLimiter, registry, false);
	}

	private static McpSchema.CallToolResult call(Function<Map<String, @Nullable Object>, ToolCallOutcome> handler,
			ToolRateLimiter rateLimiter, ObservationRegistry registry, boolean requiresTheRequestThread) {
		GATool tool = GATool.builder()
			.name("topRatedMovies")
			.description("Returns the highest-rated movies.")
			.inputSchema(SCHEMA)
			.readOnly(true)
			.callHandler(handler)
			.build();
		McpCallSettings policy = new McpCallSettings(rateLimiter, () -> "10.0.0.1", registry, false, Map.of())
			.requiringTheRequestThread(requiresTheRequestThread);
		return McpToolSpecifications.specification(tool, jsonMapper(), policy)
			.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build());
	}

	private static String text(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.findFirst()
			.orElse("");
	}

	private static ObservationRegistry registryFailingOnError() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onError(Observation.Context context) {
				throw new IllegalStateException("the tracing backend refused the error event");
			}
		});
		return registry;
	}

	private static ObservationRegistry registryFailingOn(boolean start, boolean stop) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStart(Observation.Context context) {
				if (start) {
					throw new IllegalStateException("the metrics backend is unreachable");
				}
			}

			@Override
			public void onStop(Observation.Context context) {
				if (stop) {
					throw new IllegalStateException("the metrics backend is unreachable");
				}
			}
		});
		return registry;
	}

	private static JsonMapper jsonMapper() {
		return JsonMapper.builder().build();
	}

}
