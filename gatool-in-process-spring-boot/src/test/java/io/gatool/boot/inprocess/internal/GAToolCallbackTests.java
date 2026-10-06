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

package io.gatool.boot.inprocess.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class GAToolCallbackTests {

	private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

	private final AtomicReference<Map<String, Object>> received = new AtomicReference<>();

	private final ToolCallback callback = GAToolCallbackFactory.callbacksFor(List.of(GATool.builder()
		.name("topRatedMovies")
		.description("Returns the highest-rated movies, best first.")
		.inputSchema(INPUT_SCHEMA)
		.readOnly(true)
		.callHandler((arguments) -> {
			this.received.set(arguments);
			return new ToolCallOutcome("{\"data\":{\"topRatedMovies\":[]}}", false);
		})
		.build()))[0];

	@Test
	void call_withAToolContext_shouldDelegateToTheOneArgumentCall() {
		// Spring AI's default for the two-argument call logs at INFO on every call that
		// carries a context. The override delegates, so the context test in the skeleton
		// module asserts the quiet log and this one asserts the delegation.
		String text = this.callback.call("{\"first\": 2}", new ToolContext(Map.of("conversationId", "c-1")));

		assertThat(text).isEqualTo("{\"data\":{\"topRatedMovies\":[]}}");
		assertThat(this.received.get()).containsEntry("first", 2);
	}

	@Test
	void call_theTextNull_shouldRunTheToolWithAnEmptyMap() {
		// Jackson reads the text "null" as null, and a null map would fail inside the
		// handler. It means the same as an empty string.
		String text = this.callback.call("null");

		assertThat(text).isEqualTo("{\"data\":{\"topRatedMovies\":[]}}");
		assertThat(this.received.get()).isEmpty();
	}

	@Test
	void call_blankText_shouldRunTheToolWithAnEmptyMap() {
		this.callback.call("  ");

		assertThat(this.received.get()).isEmpty();
	}

	@Test
	void call_aDecimalPastBinary64_shouldHandTheToolEveryDigit() {
		// The argument text is read with a float as a BigDecimal, because a model that
		// sends a Decimal scalar past binary64 would otherwise reach the API rounded.
		this.callback.call("{\"amount\": 1234567890.123456789}");

		assertThat(this.received.get()).containsEntry("amount", new BigDecimal("1234567890.123456789"));
	}

	@Test
	void call_textThatFailsToParse_shouldThrowToolExecutionException() {
		assertThatExceptionOfType(ToolExecutionException.class).isThrownBy(() -> this.callback.call("{\"first\": "));
	}

}
