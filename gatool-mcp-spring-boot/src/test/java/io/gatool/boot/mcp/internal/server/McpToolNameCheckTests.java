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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class McpToolNameCheckTests {

	private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

	private static final McpJsonMapper JSON = new JacksonMcpJsonMapper(JsonMapper.builder().build());

	@Test
	void check_twoSourcesSharingAName_shouldStopStartupAndNameBoth() {
		Map<String, List<McpStatelessServerFeatures.SyncToolSpecification>> tools = new LinkedHashMap<>();
		tools.put("a GATool operation file", List.of(specification("topRatedMovies")));
		tools.put("an @McpTool method", List.of(specification("topRatedMovies")));

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> McpToolNameCheck.requireServable(tools))
			.satisfies((failure) -> assertThat(failure.getMessage()).contains("topRatedMovies",
					"a GATool operation file", "an @McpTool method"));
	}

	@Test
	void check_nameLongerThanAHeaderCarries_shouldStopStartup() {
		// The compliance filter refuses a tools/call naming such a tool, so it cannot
		// run and its scopes stay unchecked; startup says so instead.
		Map<String, List<McpStatelessServerFeatures.SyncToolSpecification>> tools = new LinkedHashMap<>();
		tools.put("a GATool operation file", List.of(specification("a".repeat(257))));

		assertThatIllegalStateException().isThrownBy(() -> McpToolNameCheck.requireServable(tools))
			.withMessageContaining("257-character name")
			.withMessageContaining("a GATool operation file");
	}

	@Test
	void check_distinctNames_shouldPassWithoutWarnings() {
		Map<String, List<McpStatelessServerFeatures.SyncToolSpecification>> tools = new LinkedHashMap<>();
		tools.put("a GATool operation file", List.of(specification("topRatedMovies")));
		tools.put("an @McpTool method", List.of(specification("weatherToday")));

		assertThat(McpToolNameCheck.requireServable(tools)).isEmpty();
	}

	@Test
	void check_nameTheSdkAcceptsAndGAToolCallsUnportable_shouldWarnAndNameTheSource() {
		// The SDK allows a dot and up to 128 characters. GATool keeps tool names to a
		// narrower set, so this name works today and travels badly.
		Map<String, List<McpStatelessServerFeatures.SyncToolSpecification>> tools = Map.of("an @McpTool method",
				List.of(specification("weather.today")));

		List<String> warnings = McpToolNameCheck.requireServable(tools);

		assertThat(warnings).singleElement()
			.satisfies((warning) -> assertThat(warning).contains("weather.today", "an @McpTool method", "portable"));
	}

	@Test
	void check_oneSourceWithTwoToolsOfOneName_shouldStopStartup() {
		Map<String, List<McpStatelessServerFeatures.SyncToolSpecification>> tools = Map.of("the tool callback beans",
				List.of(specification("movies"), specification("movies")));

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> McpToolNameCheck.requireServable(tools))
			.satisfies((failure) -> assertThat(failure.getMessage()).contains("movies"));
	}

	private static McpStatelessServerFeatures.SyncToolSpecification specification(String name) {
		return new McpStatelessServerFeatures.SyncToolSpecification(McpSchema.Tool.builder(name, JSON, SCHEMA).build(),
				(context, request) -> McpSchema.CallToolResult.builder().addTextContent("{}").build());
	}

}
