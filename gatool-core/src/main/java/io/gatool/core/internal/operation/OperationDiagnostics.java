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

import java.util.ArrayList;
import java.util.List;

/**
 * Collects every problem and warning of one run of the pipeline, so that startup reports
 * all of them at once.
 *
 * @author Željko Kozina
 */
public final class OperationDiagnostics {

	private final List<OperationProblem> problems = new ArrayList<>();

	private final List<OperationWarning> warnings = new ArrayList<>();

	public void problem(String location, String message) {
		this.problems.add(new OperationProblem(location, message));
	}

	public void warning(String location, String message) {
		this.warnings.add(new OperationWarning(location, message));
	}

	public int problemCount() {
		return this.problems.size();
	}

	public List<OperationProblem> problems() {
		return List.copyOf(this.problems);
	}

	public List<OperationWarning> warnings() {
		return List.copyOf(this.warnings);
	}

}
