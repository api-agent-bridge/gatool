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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import io.gatool.core.internal.naming.ToolNameRules;

/**
 * Checks every tool name the MCP server is about to serve.
 *
 * <p>
 * Two rules run here, and both come ahead of the MCP Java SDK's own
 * {@code ToolNameValidator}:
 *
 * <ul>
 * <li><b>Clashes.</b> Spring AI collects every {@code List} of tool specifications and
 * hands them all to {@code serverBuilder.tools(...)}, which keeps one tool per name. Two
 * tools sharing a name therefore leave one of them unreachable, so startup stops and the
 * message names both sources.</li>
 * <li><b>Portability.</b> The SDK accepts letters, digits, {@code _}, {@code -} and
 * {@code .} up to 128 characters, and its message names the tool alone. This check keeps
 * GATool to the narrower set of letters, digits, {@code _} and {@code -} up to 64
 * characters, so a name outside it gets a warning naming the source while the SDK's own
 * limit stays the hard stop.</li>
 * </ul>
 *
 * <p>
 * The specifications arrive from beans read by name, because Spring AI publishes
 * {@code toolSpecs} and {@code syncTools} with the same bean type GATool publishes, so a
 * lookup by type would find GATool's own list and compare it with itself.
 *
 * @author Željko Kozina
 */
public final class McpToolNameCheck {

	private static final String AND = " and ";

	private McpToolNameCheck() {
	}

	/**
	 * Checks the names of every tool the server will serve.
	 * @param toolsBySource each source of tools, named for the message, in the order the
	 * message should list them
	 * @return the warnings for names outside GATool's portable set, which startup logs
	 * @throws IllegalStateException if two tools share a name
	 */
	public static List<String> requireServable(
			Map<String, List<McpStatelessServerFeatures.SyncToolSpecification>> toolsBySource) {
		Map<String, List<String>> namesBySource = new LinkedHashMap<>();
		toolsBySource.forEach((source, specifications) -> namesBySource.put(source,
				specifications.stream()
					.map(McpStatelessServerFeatures.SyncToolSpecification::tool)
					.map(McpSchema.Tool::name)
					.toList()));
		return requireServableNames(namesBySource);
	}

	/**
	 * Checks tool names a caller has already read from its own specifications.
	 *
	 * <p>
	 * A stateful server, which is what stdio runs on, builds a different specification
	 * type from the stateless one above, and both carry the same tool names. Taking the
	 * names here lets either server run the same two rules.
	 * @param namesBySource the tool names of each source, named for the message, in the
	 * order the message should list them
	 * @return the warnings for names outside GATool's portable set, which startup logs
	 * @throws IllegalStateException if two tools share a name
	 */
	public static List<String> requireServableNames(Map<String, List<String>> namesBySource) {
		Map<String, List<String>> sourcesByName = new LinkedHashMap<>();
		namesBySource.forEach((source, names) -> names
			.forEach((name) -> sourcesByName.computeIfAbsent(name, (key) -> new ArrayList<>()).add(source)));
		stopOnClashes(sourcesByName);
		stopOnNamesOutsideAHeader(sourcesByName);
		return collectWarnings(sourcesByName);
	}

	// The compliance filter refuses a tools/call whose name cannot travel in a header,
	// so a tool registered under such a name cannot be called, and its scopes stay
	// unchecked. The SDK's own validation stops such a name first unless
	// an application switched it off, and this stop holds either way.
	private static void stopOnNamesOutsideAHeader(Map<String, List<String>> sourcesByName) {
		List<String> outside = new ArrayList<>();
		sourcesByName.forEach((name, sources) -> {
			if (!ToolNameRules.fitsInHeader(name)) {
				outside.add("a " + name.length() + "-character name served by " + String.join(AND, sources));
			}
		});
		if (!outside.isEmpty()) {
			throw new IllegalStateException("These MCP tool names fall outside printable ASCII of at most 256 "
					+ "characters, which the MCP endpoint can carry in a challenge: " + String.join("; ", outside)
					+ ". Rename them.");
		}
	}

	private static void stopOnClashes(Map<String, List<String>> sourcesByName) {
		Set<String> clashes = new LinkedHashSet<>();
		sourcesByName.forEach((name, sources) -> {
			if (sources.size() > 1) {
				clashes.add(name + ", served by " + String.join(AND, sources));
			}
		});
		if (clashes.isEmpty()) {
			return;
		}
		throw new IllegalStateException("These MCP tool names clash: " + String.join("; ", clashes)
				+ ". An MCP server holds one tool per name, so rename one of each pair. The MCP Java SDK reports "
				+ "such a clash with the tool name alone, which is why GATool names the source here.");
	}

	// Returns a warning for each tool name outside GATool's portable set, naming the
	// sources that serve it. The portable set GATool keeps to is narrower than the one
	// the SDK allows, so a name between the two sets works today and travels badly. That
	// is a warning instead of a stop, because the application's own tool named it.
	private static List<String> collectWarnings(Map<String, List<String>> sourcesByName) {
		List<String> warnings = new ArrayList<>();
		sourcesByName.forEach((name, sources) -> {
			if (!ToolNameRules.isPortable(name)) {
				warnings.add("The tool " + name + ", served by " + String.join(AND, sources)
						+ ", uses a name outside the portable set. A tool name travels furthest with letters, "
						+ "digits, '_' and '-', and at most 64 characters.");
			}
		});
		return List.copyOf(warnings);
	}

}
