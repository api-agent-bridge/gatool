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

package io.gatool.boot.autoconfigure;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.assertj.core.api.Assertions.assertThat;

class OperationFileProblemsFailureAnalyzerTests {

	private final OperationFileProblemsFailureAnalyzer analyzer = new OperationFileProblemsFailureAnalyzer();

	@Test
	void analyze_severalProblems_shouldListEveryOneAndNameTheLocationProperties() {
		OperationFileProblemsException failure = new OperationFileProblemsException(
				List.of("file:/gatool/mcp/Anonymous.graphql holds an anonymous operation",
						"file:/gatool/mcp/UnknownField.graphql fails validation against the schema"));

		FailureAnalysis analysis = this.analyzer.analyze(failure);

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).contains("2 problems", "Anonymous.graphql", "UnknownField.graphql");
		assertThat(analysis.getAction()).contains("gatool.mcp.operations.locations",
				"gatool.in-process.operations.locations");
	}

	@Test
	void analyze_oneProblem_shouldCountItInTheSingular() {
		OperationFileProblemsException failure = new OperationFileProblemsException(
				List.of("file:/gatool/mcp/Anonymous.graphql holds an anonymous operation"));

		FailureAnalysis analysis = this.analyzer.analyze(failure);

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).contains("1 problem:").doesNotContain("1 problems");
	}

	@Test
	void message_severalProblems_shouldCarryThemForALogWithoutTheAnalysis() {
		OperationFileProblemsException failure = new OperationFileProblemsException(
				List.of("file:/gatool/mcp/Anonymous.graphql holds an anonymous operation",
						"file:/gatool/mcp/UnknownField.graphql fails validation against the schema"));

		assertThat(failure.getMessage()).contains("Anonymous.graphql", "UnknownField.graphql");
		assertThat(failure.problems()).hasSize(2);
	}

}
