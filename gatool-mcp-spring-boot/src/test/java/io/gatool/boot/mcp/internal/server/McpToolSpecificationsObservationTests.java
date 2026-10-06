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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.boot.mcp.limit.ToolRateLimiter;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

// A recording handler on a plain registry reads what the observation carried, so the
// assertions run on the Micrometer that the starters already bring.
class McpToolSpecificationsObservationTests {

	private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

	private static final ToolRateLimiter ALLOW_EVERY_CALL = (caller, toolName) -> RateLimitDecision.allow();

	private final List<Observation.Context> recorded = new CopyOnWriteArrayList<>();

	private final ObservationRegistry registry = registryRecordingInto(this.recorded);

	@Test
	void call_responseWithData_shouldRecordTheToolTheTypeAndASuccessOutcome() {
		McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{\"data\":{}}", false)), jsonMapper(),
					policy(false))
			.callHandler()
			.apply(null, request());

		assertThat(this.recorded).singleElement().satisfies((context) -> {
			assertThat(context.getName()).isEqualTo("gatool.call");
			assertThat(tags(context)).contains(KeyValue.of("gatool.call.name", "topRatedMovies"),
					KeyValue.of("gatool.operation.type", "query"), KeyValue.of("gatool.call.outcome", "success"));
		});
	}

	@Test
	void call_responseWithGraphQlErrors_shouldRecordTheGraphQlErrorsOutcome() {
		McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{\"errors\":[]}", true)), jsonMapper(),
					policy(false))
			.callHandler()
			.apply(null, request());

		assertThat(tags(this.recorded.getFirst())).contains(KeyValue.of("gatool.call.outcome", "graphql-errors"));
	}

	@Test
	void call_outcomeOfTheApiRefusedKind_shouldRecordItsLabel() {
		// The tag reads the kind, so a 401 and a result the size cap held back stay apart
		// from GraphQL errors on a dashboard.
		McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome(
					"The tool topRatedMovies called the GraphQL API and it answered 401.", true, null,
					ToolCallOutcome.Kind.API_REFUSED)), jsonMapper(), policy(false))
			.callHandler()
			.apply(null, request());

		assertThat(tags(this.recorded.getFirst())).contains(KeyValue.of("gatool.call.outcome", "api-refused"));
	}

	@Test
	void call_transportFailure_shouldRecordTheToolErrorOutcomeAndTheCause() {
		GATool failing = tool((arguments) -> {
			throw new ToolCallFailedException("The tool topRatedMovies could not reach the GraphQL API.",
					new IllegalStateException("Connection refused"));
		});

		McpToolSpecifications.specification(failing, jsonMapper(), policy(false)).callHandler().apply(null, request());

		Observation.Context context = this.recorded.getFirst();
		assertThat(tags(context)).contains(KeyValue.of("gatool.call.outcome", "tool-error"));
		assertThat(context.getError()).isInstanceOf(ToolCallFailedException.class);
	}

	@Test
	void call_refusedByTheRateLimiter_shouldRecordTheRateLimitedOutcome() {
		ToolRateLimiter refuseEveryCall = (caller, toolName) -> RateLimitDecision.refuse(Duration.ofSeconds(12),
				"60 calls per minute");
		McpCallSettings policy = new McpCallSettings(refuseEveryCall, () -> "10.0.0.1", this.registry, false, Map.of());

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{}", false)), jsonMapper(), policy)
			.callHandler()
			.apply(null, request());

		assertThat(result.isError()).isTrue();
		assertThat(tags(this.recorded.getFirst())).contains(KeyValue.of("gatool.call.outcome", "rate-limited"));
	}

	@Test
	void call_includeContentOff_shouldKeepTheArgumentsAndTheResultOut() {
		McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{\"data\":{}}", false)), jsonMapper(),
					policy(false))
			.callHandler()
			.apply(null, request());

		assertThat(this.recorded.getFirst().getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
			.doesNotContain("gatool.call.arguments", "gatool.call.result");
	}

	@Test
	void call_includeContentOn_shouldCarryTheArgumentsAndTheResult() {
		McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{\"data\":{}}", false)), jsonMapper(), policy(true))
			.callHandler()
			.apply(null, request());

		assertThat(this.recorded.getFirst().getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
			.contains("gatool.call.arguments", "gatool.call.result");
	}

	@Test
	void call_offTheRequestThreadUnderTheRequestThreadRule_shouldRecordTheOffRequestThreadOutcome() {
		// The compliance filter marks a request thread, and it did not mark this test
		// thread, which is what a worker thread of the MCP SDK looks like.
		McpCallSettings policy = policy(false).requiringTheRequestThread(true);

		McpSchema.CallToolResult result = McpToolSpecifications
			.specification(tool((arguments) -> new ToolCallOutcome("{\"data\":{}}", false)), jsonMapper(), policy)
			.callHandler()
			.apply(null, request());

		assertThat(result.isError()).isTrue();
		assertThat(tags(this.recorded.getFirst())).contains(KeyValue.of("gatool.call.outcome", "off-request-thread"));
	}

	private McpCallSettings policy(boolean includeContent) {
		return new McpCallSettings(ALLOW_EVERY_CALL, () -> "10.0.0.1", this.registry, includeContent, Map.of());
	}

	private static List<KeyValue> tags(Observation.Context context) {
		return context.getLowCardinalityKeyValues().stream().toList();
	}

	private static ObservationRegistry registryRecordingInto(List<Observation.Context> recorded) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				recorded.add(context);
			}
		});
		return registry;
	}

	// The two-argument constructor is deprecated, and the builder is the form the
	// end-to-end tests use as well.
	private static McpSchema.CallToolRequest request() {
		return McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build();
	}

	private static JsonMapper jsonMapper() {
		return JsonMapper.builder().build();
	}

	private static GATool tool(Function<Map<String, Object>, ToolCallOutcome> handler) {
		return GATool.builder()
			.name("topRatedMovies")
			.description("Returns the highest-rated movies, best first.")
			.inputSchema(SCHEMA)
			.readOnly(true)
			.callHandler(handler)
			.build();
	}

}
