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

package io.gatool.core.check;

import java.util.List;

/**
 * What {@link OperationFileCheck} found.
 *
 * <p>
 * The problems and the warnings are plain text, so a failing assertion prints what a
 * startup failure would print, and a test asserts on them without reaching into the
 * core's own types.
 *
 * @param tools the tools the operation files would serve
 * @param problems the problems that would stop startup, each already written to name the
 * operation file, the folder or the schema file it is about
 * @param warnings the warnings that startup would log
 * @author Željko Kozina
 */
public record CheckResult(List<CheckedTool> tools, List<String> problems, List<String> warnings) {

	/**
	 * Copies the three lists, so the result keeps what the check found however the caller
	 * changes its own lists afterwards.
	 */
	public CheckResult {
		tools = List.copyOf(tools);
		problems = List.copyOf(problems);
		warnings = List.copyOf(warnings);
	}

	/**
	 * Tells whether the operation files would start an application.
	 * @return {@code true} while every file passes every check
	 */
	public boolean passes() {
		return this.problems.isEmpty();
	}
}
