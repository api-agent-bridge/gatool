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

package io.gatool.boot.inprocess.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.assertj.core.api.Assertions.assertThat;

class InProcessToolNameClashFailureAnalyzerTests {

	private final InProcessToolNameClashFailureAnalyzer analyzer = new InProcessToolNameClashFailureAnalyzer();

	@Test
	void analyze_clash_shouldReportTheDescriptionAndTheActionApart() {
		InProcessToolNameClashException failure = new InProcessToolNameClashException(
				"A bean of the application declares the GATool tools topRatedMovies.",
				"Attach them to a ChatClient instead.");

		FailureAnalysis analysis = this.analyzer.analyze(failure);

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription())
			.isEqualTo("A bean of the application declares the GATool tools " + "topRatedMovies.");
		assertThat(analysis.getAction()).isEqualTo("Attach them to a ChatClient instead.");
		assertThat(analysis.getCause()).isSameAs(failure);
	}

}
