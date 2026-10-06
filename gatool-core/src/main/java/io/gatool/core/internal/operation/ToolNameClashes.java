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

package io.gatool.core.internal.operation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settles the tools that share one tool name among the tools of one exposure type.
 *
 * @author Željko Kozina
 */
final class ToolNameClashes {

	private ToolNameClashes() {
	}

	/**
	 * Returns the tools left once a clash on a tool name is settled: where a written
	 * operation publishes the name every generated tool sharing it is dropped, and where
	 * only generated tools share it the first is kept. Each drop is reported as a
	 * warning.
	 * @param tools the tools the operation files and the schema yielded
	 * @param diagnostics receives a warning for each dropped tool
	 * @return the tools kept, in their order
	 */
	// A tool name that two operations share stops startup, and only a written operation
	// can be renamed to settle it. So a generated tool sharing a name with a written one
	// is dropped, which is the rule a file winning already states. Where two generated
	// tools share a name the first is kept and the rest are dropped, since two root
	// fields whose names differ only in case collapse to one tool name under any
	// strategy.
	static List<ToolOperation> withoutGeneratedClashes(List<ToolOperation> tools, OperationDiagnostics diagnostics) {
		// Identity instead of equality, because two tools can be equal in every component
		// and still be two entries the catalog carries.
		Set<ToolOperation> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ToolExposureType toolExposureType : ToolExposureType.values()) {
			Map<String, List<ToolOperation>> byName = tools.stream()
				.filter((tool) -> tool.toolExposureTypes().contains(toolExposureType))
				.collect(Collectors.groupingBy(ToolOperation::toolName, LinkedHashMap::new, Collectors.toList()));
			byName.forEach((toolName, toolsSharingName) -> {
				if (toolsSharingName.size() < 2 || toolsSharingName.stream().noneMatch(ToolNameClashes::isGenerated)) {
					return;
				}
				boolean written = toolsSharingName.stream().anyMatch((tool) -> !isGenerated(tool));
				String reason = written ? "an operation file already publishes that name"
						: "another root field takes the same tool name";
				// A generated tool serves both exposure types, so a clash on either
				// exposure type drops it whole. Where every one of them is generated the
				// first is kept, so the schema still yields a tool for that name.
				//
				// The second branch takes a positional sublist, because indexOf consults
				// equals, and this method holds two equal entries as two entries: an
				// entry equal to an earlier one would report index 0 and stay.
				List<ToolOperation> clashing = written
						? toolsSharingName.stream().filter(ToolNameClashes::isGenerated).toList()
						: toolsSharingName.subList(1, toolsSharingName.size());
				clashing.forEach((tool) -> {
					if (dropped.add(tool)) {
						diagnostics.warning(tool.location(),
								"is left out, because " + reason + ": '" + toolName
										+ "'. Write an operation file for this root field, and give it a "
										+ "name of its own with @gatool(name:).");
					}
				});
			});
		}
		return tools.stream().filter((tool) -> !dropped.contains(tool)).toList();
	}

	private static boolean isGenerated(ToolOperation tool) {
		return RootFieldOperations.isGenerated(tool.location());
	}

	/**
	 * Reports a problem for every tool name that two or more tools of one exposure type
	 * share.
	 * @param tools the tools to check
	 * @param diagnostics receives a problem for each shared name
	 */
	static void reportNameClashes(List<ToolOperation> tools, OperationDiagnostics diagnostics) {
		for (ToolExposureType toolExposureType : ToolExposureType.values()) {
			Map<String, List<ToolOperation>> toolsByName = tools.stream()
				.filter((tool) -> tool.toolExposureTypes().contains(toolExposureType))
				.collect(Collectors.groupingBy(ToolOperation::toolName, LinkedHashMap::new, Collectors.toList()));
			toolsByName.forEach((toolName, toolsSharingName) -> {
				if (toolsSharingName.size() > 1) {
					String others = toolsSharingName.subList(1, toolsSharingName.size())
						.stream()
						.map(ToolOperation::location)
						.collect(Collectors.joining(", "));
					diagnostics.problem(toolsSharingName.getFirst().location(),
							"shares the tool name '" + toolName + "' with " + others + " among the "
									+ toolExposureType.label() + " tools; rename one of the operations");
				}
			});
		}
	}

}
