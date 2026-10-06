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

import java.util.List;

/**
 * The tools that the operation files create, with every problem and warning.
 *
 * @param tools the tools, in the order of their files
 * @param problems the problems that stop startup
 * @param warnings the warnings that startup logs
 * @author Željko Kozina
 */
public record OperationCatalog(List<ToolOperation> tools, List<OperationProblem> problems,
		List<OperationWarning> warnings) {

	// The three lists are copied, so the catalog holds what the factory produced and
	// startup reads the same lists however the caller changes its own afterwards.
	public OperationCatalog {
		tools = List.copyOf(tools);
		problems = List.copyOf(problems);
		warnings = List.copyOf(warnings);
	}

	public List<ToolOperation> toolsFor(ToolExposureType toolExposureType) {
		return this.tools.stream().filter((tool) -> tool.toolExposureTypes().contains(toolExposureType)).toList();
	}

	public boolean hasProblems() {
		return !this.problems.isEmpty();
	}
}
