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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import io.gatool.boot.mcp.internal.server.McpToolNameCheck;

/**
 * Reads the names of the tools Spring AI publishes beside GATool's, and warns where one
 * of them clashes with a tool built from an operation file.
 *
 * <p>
 * The class holds both steps so that {@link GAToolMcpAutoConfiguration} keeps its bean
 * methods and little else. The log lines keep the auto-configuration's category, so a
 * level set on that class still covers them.
 *
 * @author Željko Kozina
 */
final class SpringAiToolNames {

	private static final Log logger = LogFactory.getLog(GAToolMcpAutoConfiguration.class);

	// GATool checks its own names against the application's own tools before Spring
	// AI builds the server. Spring AI publishes toolSpecs from the annotation scanner and
	// syncTools from the converter, and both carry the bean type GATool publishes, so a
	// lookup by type would find GATool's own list. Reading them by name keeps the three
	// apart, and a name the conditions left undefined stays absent here.
	private static final Map<String, String> SPRING_AI_TOOL_BEANS = Map.of("toolSpecs", "an @McpTool method",
			"syncTools", "a ToolCallback bean");

	private SpringAiToolNames() {
	}

	/**
	 * Returns the names of the tools Spring AI publishes, listed under the kind of
	 * declaration each one came from.
	 * @param beanFactory holds Spring AI's specification list beans
	 * @return the tool names by the kind of declaration, for each list bean the factory
	 * holds
	 */
	// Spring AI publishes these beans from its common auto-configuration, so a stateless
	// server and a stateful one use the same bean names and build different specification
	// types. Reading the tool names covers both kinds, where a cast to one of the two
	// types would serve the stateless server alone and leave stdio without the check.
	static Map<String, List<String>> read(ConfigurableListableBeanFactory beanFactory) {
		Map<String, List<String>> names = new LinkedHashMap<>();
		SPRING_AI_TOOL_BEANS.forEach((beanName, source) -> {
			if (!beanFactory.containsBean(beanName)) {
				return;
			}
			List<?> specifications = beanFactory.getBean(beanName, List.class);
			names.put(source,
					specifications.stream()
						.map(SpringAiToolNames::toolNameOf)
						.filter((name) -> !name.isEmpty())
						.toList());
		});
		return names;
	}

	// Returns the tool name a Spring AI tool specification carries, and an empty string
	// for an object of any other type.
	// A list holding anything else leaves an empty name, which the caller drops. Spring
	// AI owns those beans, so a type this release has yet to meet stays out of the check
	// instead of stopping an application that is otherwise correct.
	private static String toolNameOf(Object specification) {
		if (specification instanceof McpStatelessServerFeatures.SyncToolSpecification stateless) {
			return stateless.tool().name();
		}
		if (specification instanceof McpServerFeatures.SyncToolSpecification stateful) {
			return stateful.tool().name();
		}
		return "";
	}

	static void warnOnClashingToolNames(List<String> gaToolNames, Map<String, List<String>> springAiToolNames) {
		Map<String, List<String>> toolNamesBySource = new LinkedHashMap<>();
		toolNamesBySource.put("a GATool operation file", gaToolNames);
		toolNamesBySource.putAll(springAiToolNames);
		McpToolNameCheck.requireServableNames(toolNamesBySource).forEach(logger::warn);
	}

}
