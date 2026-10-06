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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code gatool.call.outcome} tag of a stdio call whose tool needs a scope the server
 * was started without. This pins README.md's list of outcome values, which the code
 * writes a ninth value into: {@code insufficient-scope}.
 */
class McpStdioInsufficientScopeObservationTests {

	private static final ResolvableType STATEFUL_TOOL_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpServerFeatures.SyncToolSpecification.class);

	private static final List<Observation.Context> RECORDED = new CopyOnWriteArrayList<>();

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withBean(ObservationRegistry.class, McpStdioInsufficientScopeObservationTests::recordingRegistry)
		.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped/",
				"gatool.mcp.stdio.granted-scopes=movies:read", "gatool.api.url=" + MoviesApiServer.graphQlUrl());

	@Test
	void callTool_overStdioNeedingAScopeTheServerLacks_shouldRecordTheOutcomeInsufficientScope() {
		RECORDED.clear();
		AtomicReference<McpSchema.CallToolResult> result = new AtomicReference<>();

		this.contextRunner
			.run((context) -> result.set(call(context, "movieByLookup", Map.of("by", Map.of("id", "movie-1")))));

		assertThat(result.get().isError()).isTrue();
		assertThat(tags(toolCall())).contains(KeyValue.of("gatool.call.outcome", "insufficient-scope"));
	}

	@SuppressWarnings("unchecked")
	private static McpSchema.CallToolResult call(AssertableApplicationContext context, String tool,
			Map<String, Object> arguments) {
		List<McpServerFeatures.SyncToolSpecification> specifications = (List<McpServerFeatures.SyncToolSpecification>) context
			.getBeanProvider(STATEFUL_TOOL_SPECIFICATIONS)
			.getObject();
		McpServerFeatures.SyncToolSpecification specification = specifications.stream()
			.filter((candidate) -> candidate.tool().name().equals(tool))
			.findFirst()
			.orElseThrow();
		return specification.callHandler()
			.apply(null, McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
	}

	private static Observation.Context toolCall() {
		return RECORDED.stream()
			.filter((context) -> "gatool.call".equals(context.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the call left the gatool.call observation unrecorded"));
	}

	private static List<KeyValue> tags(Observation.Context context) {
		return context.getLowCardinalityKeyValues().stream().toList();
	}

	private static ObservationRegistry recordingRegistry() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				RECORDED.add(context);
			}
		});
		return registry;
	}

}
