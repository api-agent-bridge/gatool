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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;
import io.gatool.boot.inprocess.autoconfigure.InProcessToolNameClashException;

import static org.assertj.core.api.Assertions.assertThat;

// The application's own tools fall under the same name clash rules as GATool's tools.
// A ChatClient holds one tool per name and would answer from whichever tool it
// resolved, so a clash stops startup.
class InProcessToolClashTests {

	private static final Log logger = LogFactory.getLog(InProcessToolClashTests.class);

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String API_URL = "gatool.api.url=http://localhost:1/graphql";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
		.withPropertyValues(API_URL, SCHEMA);

	@Test
	void inProcessTools_applicationBeanCarryingAToolOfTheSameName_shouldStopStartupAndShowTheChatClientCode() {
		// GATool hands the in-process tools out as a bean of its own type, so they stay
		// off the MCP server. A ToolCallback bean carrying them undoes that. The stop is
		// a dedicated exception with its own analyzer, so the code lands in Boot's
		// failure analysis block.
		this.contextRunner.withBean("clashing", ToolCallback.class, () -> callback("topRatedMovies")).run((context) -> {
			Throwable rootCause = rootCauseOf(context.getStartupFailure());
			assertThat(rootCause).isInstanceOf(InProcessToolNameClashException.class)
				.hasMessageContaining("topRatedMovies")
				.hasMessageContaining("MCP converter is active");
			FailureAnalysis analysis = analysisOf(rootCause);
			assertThat(analysis).isNotNull();
			assertThat(analysis.getDescription()).contains("topRatedMovies");
			assertThat(analysis.getAction()).contains("defaultTools(tools.toolCallbackProvider())");
		});
	}

	@Test
	void inProcessTools_providerBeanCarryingAToolOfTheSameName_shouldStopStartupNamingTheChatClientCode() {
		// Spring AI's converter collects ToolCallbackProvider beans as well, so the guard
		// reads both types.
		ToolCallbackProvider provider = () -> new ToolCallback[] { callback("topRatedMovies") };

		this.contextRunner.withBean("clashingProvider", ToolCallbackProvider.class, () -> provider).run((context) -> {
			Throwable rootCause = rootCauseOf(context.getStartupFailure());
			assertThat(rootCause).isInstanceOf(InProcessToolNameClashException.class);
			assertThat(((InProcessToolNameClashException) rootCause).action())
				.contains("defaultTools(tools.toolCallbackProvider())");
		});
	}

	@Test
	void inProcessTools_converterSwitchedOff_shouldStopStartupWithoutNamingTheMcpServer() {
		// The clash still stops startup, because a ChatClient holds one tool per name.
		// The sentence about the MCP server applies while the converter runs.
		this.contextRunner.withPropertyValues("spring.ai.mcp.server.tool-callback-converter=false")
			.withBean("clashing", ToolCallback.class, () -> callback("topRatedMovies"))
			.run((context) -> {
				Throwable rootCause = rootCauseOf(context.getStartupFailure());
				assertThat(rootCause).isInstanceOf(InProcessToolNameClashException.class)
					.hasMessageNotContaining("MCP converter is active");
				assertThat(((InProcessToolNameClashException) rootCause).action())
					.contains("defaultTools(tools.toolCallbackProvider())");
			});
	}

	@Test
	void inProcessTools_twoApplicationBeansSharingAName_shouldStopStartupAskingForARename() {
		// Neither name is GATool's, so the action is the rename, without the ChatClient
		// code that belongs to the other case.
		this.contextRunner.withBean("weather", ToolCallback.class, () -> callback("weatherToday"))
			.withBean("weatherAgain", ToolCallback.class, () -> callback("weatherToday"))
			.run((context) -> {
				Throwable rootCause = rootCauseOf(context.getStartupFailure());
				assertThat(rootCause).isInstanceOf(InProcessToolNameClashException.class)
					.hasMessageContaining("weatherToday");
				assertThat(((InProcessToolNameClashException) rootCause).action()).contains("Rename one of each pair")
					.doesNotContain("defaultTools(");
			});
	}

	@Test
	void inProcessTools_applicationToolWithAnotherName_shouldStartAndKeepBoth() {
		this.contextRunner.withBean("other", ToolCallback.class, () -> callback("weatherToday")).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCallbacks.class);
			// GATool's catalog holds its own tool alone, and the application's bean
			// reaches a ChatClient the way the application registers it.
			assertThat(context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks()).singleElement()
				.satisfies((tool) -> assertThat(tool.getToolDefinition().name()).isEqualTo("topRatedMovies"));
		});
	}

	@Test
	void inProcessTools_applicationWithoutToolBeans_shouldStart() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCallbacks.class);
		});
	}

	// The analyzer is package-private, as Boot's own analyzers are, so this test reaches
	// it the way Boot reaches it: through the name in META-INF/spring.factories. An
	// analyzer whose constructor takes an argument this loader lacks is logged and
	// skipped, and the constructor under test has an empty parameter list.
	private static FailureAnalysis analysisOf(Throwable failure) {
		List<FailureAnalyzer> analyzers = SpringFactoriesLoader
			.forDefaultResourceLocation(InProcessToolClashTests.class.getClassLoader())
			.load(FailureAnalyzer.class, FailureHandler.logging(logger));
		for (FailureAnalyzer analyzer : analyzers) {
			FailureAnalysis analysis = analyzer.analyze(failure);
			if (analysis != null) {
				return analysis;
			}
		}
		throw new AssertionError("every analyzer passed on " + failure);
	}

	private static Throwable rootCauseOf(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	private static ToolCallback callback(String name) {
		return FunctionToolCallback.builder(name, (Map<String, Object> arguments) -> "{}")
			.description("A tool the application declares itself.")
			.inputType(new ParameterizedTypeReference<Map<String, Object>>() {
			})
			.build();
	}

}
