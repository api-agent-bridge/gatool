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

package io.gatool.boot.mcp.autoconfigure;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class McpStartupStopsTests {

	@Test
	void toolsAskingBeforeEachCall_oneTool_shouldBeSingular() {
		assertThat(McpStartupStops.toolsAskingBeforeEachCall(List.of("topRatedMovies")))
			.isEqualTo("1 tool asks the user before each call");
	}

	@Test
	void toolsAskingBeforeEachCall_twoTools_shouldBePlural() {
		assertThat(McpStartupStops.toolsAskingBeforeEachCall(List.of("topRatedMovies", "addMovie")))
			.isEqualTo("2 tools ask the user before each call");
	}

	@Test
	void stdioElicitationFix_applicationWithoutAWebServer_shouldSetTheProperty() {
		assertThat(McpStartupStops.stdioElicitationFix(new GenericApplicationContext()))
			.isEqualTo("Set gatool.mcp.stdio.elicitation=true");
	}

	@Test
	void stdioElicitationFix_servletApplication_shouldLeadWithServingStdioWithoutAWebServer() {
		// Setting the property alone would lead a servlet application to the stop on
		// stdio elicitation in a web application.
		assertThat(McpStartupStops.stdioElicitationFix(new GenericWebApplicationContext()))
			.isEqualTo("Serve stdio from an application without a web server, with "
					+ "spring.main.web-application-type=none, and set gatool.mcp.stdio.elicitation=true");
	}

	@Test
	void stopWhereToolsElicitWithoutAChannel_twoTools_shouldCountAndNameThem() {
		GAToolCatalog catalog = GAToolCatalog.builder()
			.mcpTools(List.of(tool("topRatedMovies"), tool("addMovie")))
			.build();

		assertThatExceptionOfType(InvalidConfigurationPropertyValueException.class)
			.isThrownBy(() -> McpStartupStops.stopWhereToolsElicitWithoutAChannel(catalog, new GAToolProperties(),
					"stdio while gatool.mcp.stdio.elicitation is false", "Set gatool.mcp.stdio.elicitation=true"))
			.extracting(InvalidConfigurationPropertyValueException::getReason)
			.isEqualTo("2 tools ask the user before each call (topRatedMovies, addMovie), and this deployment runs "
					+ "on stdio while gatool.mcp.stdio.elicitation is false, which cannot ask. Set "
					+ "gatool.mcp.stdio.elicitation=true, or set gatool.elicitation.ignore-where-unsupported=true "
					+ "to serve these tools without asking.");
	}

	private static GATool tool(String name) {
		return GATool.builder()
			.name(name)
			.inputSchema("{\"type\":\"object\",\"properties\":{}}")
			.readOnly(true)
			.callHandler((arguments) -> new ToolCallOutcome("{}", false))
			.elicit("Go ahead?")
			.build();
	}

}
