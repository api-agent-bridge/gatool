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

package io.gatool.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ToolCallOutcomeTests {

	@Test
	void structuredContent_shouldBeACopyOfTheMapTheOutcomeWasGivenWith() {
		// The MCP adapter sends the structured content after the runner built the
		// outcome, so a runner that reuses its envelope map must leave what was sent
		// alone.
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("data", Map.of("tagline", "Films, indexed."));
		ToolCallOutcome outcome = new ToolCallOutcome("{\"data\":{\"tagline\":\"Films, indexed.\"}}", false, envelope);
		envelope.put("errors", "[]");

		assertThat(outcome.structuredContent()).containsOnlyKeys("data");
	}

	@Test
	void structuredContent_holdingANullData_shouldKeepTheNull() {
		// An API that answers with errors alone sends "data": null, and the structured
		// content carries the same envelope the text does, null included. Map.copyOf
		// would refuse it.
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("data", null);
		envelope.put("errors", "[]");

		ToolCallOutcome outcome = new ToolCallOutcome("{\"data\":null,\"errors\":[]}", true, envelope);

		assertThat(outcome.structuredContent()).containsOnlyKeys("data", "errors").containsEntry("data", null);
	}

	@Test
	void structuredContent_leftOut_shouldStayNull() {
		assertThat(new ToolCallOutcome("{}", false).structuredContent()).isNull();
	}

	@Test
	void kind_leftOut_shouldFollowTheErrorFlag() {
		// The tools of the dynamic layer build their outcomes from the flag alone, and
		// the kind follows the flag.
		assertThat(new ToolCallOutcome("{}", false).kind()).isEqualTo(ToolCallOutcome.Kind.SUCCESS);
		assertThat(new ToolCallOutcome("{\"errors\":[]}", true).kind()).isEqualTo(ToolCallOutcome.Kind.GRAPHQL_ERRORS);
	}

	@Test
	void kind_disagreeingWithTheErrorFlag_shouldBeRefused() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ToolCallOutcome("{}", false, null, ToolCallOutcome.Kind.API_REFUSED));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ToolCallOutcome("{}", true, null, ToolCallOutcome.Kind.SUCCESS));
	}

	@Test
	void label_shouldBeTheNameInLowerCaseWithHyphens() {
		assertThat(ToolCallOutcome.Kind.GRAPHQL_ERRORS.label()).isEqualTo("graphql-errors");
		assertThat(ToolCallOutcome.Kind.RESULT_TOO_LARGE.label()).isEqualTo("result-too-large");
	}

}
