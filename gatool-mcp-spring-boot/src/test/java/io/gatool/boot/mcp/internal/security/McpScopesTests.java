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

package io.gatool.boot.mcp.internal.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scope lists one server publishes and checks: the baseline first, each tool's own
 * once, and the unsafe switch that publishes everything.
 */
class McpScopesTests {

	private static final List<GATool> TOOLS = List.of(tool("topRatedMovies", List.of("movies:read")),
			tool("movieByLookup", List.of("movies:read", "movies:detail")), tool("searchMovies", List.of()),
			tool("moviesPage", null));

	@Test
	void requiredFor_aToolWithScopes_shouldListTheBaselineThenItsOwnWithoutRepeats() {
		McpScopes scopes = McpScopes.of(List.of("mcp:tools", "movies:read"), TOOLS, false);

		assertThat(scopes.requiredFor("movieByLookup")).containsExactly("mcp:tools", "movies:read", "movies:detail");
		assertThat(scopes.requiredFor("searchMovies")).containsExactly("mcp:tools", "movies:read");
		assertThat(scopes.requiredFor("moviesPage")).containsExactly("mcp:tools", "movies:read");
		assertThat(scopes.requiredFor(null)).containsExactly("mcp:tools", "movies:read");
	}

	@Test
	void all_shouldHoldEveryScopeOnceInPublicationOrder() {
		McpScopes scopes = McpScopes.of(List.of("mcp:tools"), TOOLS, false);

		assertThat(scopes.all()).containsExactly("mcp:tools", "movies:read", "movies:detail");
	}

	@Test
	void publishedAtSignIn_withTheSwitchOn_shouldNameEveryScopeWhateverTheCall() {
		McpScopes scopes = McpScopes.of(List.of("mcp:tools"), TOOLS, true);

		assertThat(scopes.publishedAtSignIn(null)).containsExactly("mcp:tools", "movies:read", "movies:detail");
		assertThat(scopes.publishedAtSignIn("searchMovies")).containsExactly("mcp:tools", "movies:read",
				"movies:detail");
	}

	@Test
	void known_shouldKeepTheScopesThisServerDefinesInItsOwnOrder() {
		McpScopes scopes = McpScopes.of(List.of("mcp:tools"), TOOLS, false);

		assertThat(scopes.knownAmong(List.of("movies:detail", "profile", "mcp:tools"))).containsExactly("mcp:tools",
				"movies:detail");
	}

	@Test
	void of_aToolWhoseListIsMutable_shouldCopyIt() {
		List<String> mutable = new ArrayList<>(List.of("movies:read"));
		McpScopes scopes = McpScopes.of(List.of("mcp:tools"), List.of(tool("t", mutable)), false);
		mutable.add("movies:write");

		assertThat(scopes.toolScopes("t")).containsExactly("movies:read");
	}

	private static GATool tool(String name, List<String> scopes) {
		return GATool.builder()
			.name(name)
			.description("A tool.")
			.inputSchema("{}")
			.readOnly(true)
			.callHandler((Map<String, Object> arguments) -> new ToolCallOutcome("{}", false))
			.scopes(scopes)
			.build();
	}

}
