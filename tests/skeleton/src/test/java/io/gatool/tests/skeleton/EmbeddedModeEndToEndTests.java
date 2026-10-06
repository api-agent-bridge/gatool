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

package io.gatool.tests.skeleton;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.internal.execution.EmbeddedGraphQlExecutor;
import io.gatool.core.model.ToolCallOutcome;
import io.gatool.fixtures.movies.api.MoviesApiApplication;

import static org.assertj.core.api.Assertions.assertThat;

// In embedded mode GATool runs inside the application that serves the GraphQL API, so this
// test starts the movie API itself and leaves gatool.api.url unset. The MCP and in-process
// end-to-end tests run the same operation against the same fixture over HTTP, and all
// three assert the same text, so a difference between the two modes fails one of them.
@SpringBootTest(classes = MoviesApiApplication.class, webEnvironment = WebEnvironment.MOCK,
		properties = "spring.ai.mcp.server.enabled=false")
class EmbeddedModeEndToEndTests {

	private static final String TOP_RATED_MOVIES_RESULT = """
			{"data":{"topRatedMovies":[\
			{"id":"movie-1","title":"Signal from Kepler","rating":8.6},\
			{"id":"movie-2","title":"The Last Lighthouse","rating":7.9},\
			{"id":"movie-3","title":"Midnight Recipe","rating":7.1},\
			{"id":"movie-4","title":"Iron Harbor","rating":6.4}]}}\
			""";

	@Autowired
	private GAToolCatalog catalog;

	@Autowired
	private GAToolCallbacks tools;

	@Autowired
	private GraphQlExecutor executor;

	@Test
	void gaToolGraphQlExecutor_withoutApiUrl_shouldRunInsideTheApplication() {
		assertThat(this.executor).isInstanceOf(EmbeddedGraphQlExecutor.class);
	}

	@Test
	void gaToolCatalog_withoutSchemaLocationOrApiUrl_shouldRunItsToolsThroughTheApplicationsOwnGraphQlEngine() {
		// In embedded mode GATool reads the running schema, so the operation file below
		// only built a tool because it validated against the schema the application
		// itself serves. The executor this catalog's tool runs through is the embedded
		// one, so the call below ran the query inside the application rather than over
		// a network call to a schema fetched some other way.
		assertThat(this.executor).isInstanceOf(EmbeddedGraphQlExecutor.class);

		ToolCallOutcome outcome = this.catalog.mcpTools().getFirst().call(Map.of());

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).isEqualTo(TOP_RATED_MOVIES_RESULT);
	}

	@Test
	void call_mcpTool_shouldReturnTheTextTheRemoteModeTestsAssert() {
		ToolCallOutcome outcome = this.catalog.mcpTools().getFirst().call(Map.of());

		assertThat(outcome.isError()).isFalse();
		assertThat(outcome.text()).isEqualTo(TOP_RATED_MOVIES_RESULT);
	}

	@Test
	void call_inProcessTool_shouldReturnTheSameTextAsTheMcpSide() {
		ToolCallback[] callbacks = this.tools.toolCallbackProvider().getToolCallbacks();

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks[0].call("{}")).isEqualTo(TOP_RATED_MOVIES_RESULT);
	}

	@Test
	void call_firstTwo_shouldPassTheVariableToTheApplicationsDataFetcher() {
		ToolCallOutcome outcome = this.catalog.mcpTools().getFirst().call(Map.of("first", 2));

		assertThat(outcome.text()).contains("Signal from Kepler", "The Last Lighthouse")
			.doesNotContain("Midnight Recipe");
	}

}
