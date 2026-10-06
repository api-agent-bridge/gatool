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

package io.gatool.core.model;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class GAToolTests {

	@Test
	void scopes_shouldBeACopyOfTheListTheToolWasGivenWith() {
		// The adapters read the scopes on every call, so a caller that keeps changing its
		// own list after building the tool must leave those reads alone.
		List<String> scopes = new ArrayList<>(List.of("movies:read"));
		GATool tool = tool(scopes);
		scopes.add("movies:write");

		assertThat(tool.scopes()).containsExactly("movies:read");
	}

	@Test
	void scopes_leftNull_shouldStayNull() {
		// Null says the tool leaves its scopes undeclared, where an empty list opens the
		// tool to every caller with the baseline scopes, so the two stay apart.
		assertThat(tool(null).scopes()).isNull();
	}

	@Test
	void build_withoutACallHandler_shouldSayWhichValueIsMissing() {
		GATool.Builder builder = GATool.builder().name("topRatedMovies").inputSchema("{}");

		assertThatIllegalStateException().isThrownBy(builder::build)
			.withMessage("A GATool needs a callHandler, and the builder was given none.");
	}

	@Test
	void build_withoutTheReadOnlyFlag_shouldCountTheToolAsOneThatWrites() {
		// A tool that does not say whether it writes is taken to write, which is the
		// careful reading: a client asks before it runs one.
		GATool tool = GATool.builder()
			.name("addReview")
			.inputSchema("{}")
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.build();

		assertThat(tool.readOnly()).isFalse();
	}

	@Test
	void toBuilder_withOtherScopes_shouldKeepEveryOtherValue() {
		GATool tool = tool(List.of("movies:read"));

		GATool copy = tool.toBuilder().scopes(List.of("movies:write")).build();

		assertThat(copy.scopes()).containsExactly("movies:write");
		assertThat(copy.name()).isEqualTo(tool.name());
		assertThat(copy.description()).isEqualTo(tool.description());
		assertThat(copy.inputSchema()).isEqualTo(tool.inputSchema());
		assertThat(copy.readOnly()).isEqualTo(tool.readOnly());
		assertThat(copy.callHandler()).isSameAs(tool.callHandler());
	}

	private static GATool tool(List<String> scopes) {
		return GATool.builder()
			.name("topRatedMovies")
			.description("Returns the highest-rated movies.")
			.inputSchema("{}")
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.scopes(scopes)
			.build();
	}

}
