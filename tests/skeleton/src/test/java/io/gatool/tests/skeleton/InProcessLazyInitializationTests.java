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
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ParameterizedTypeReference;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.OperationFileProblemsException;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;
import io.gatool.boot.inprocess.autoconfigure.InProcessToolNameClashException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application on the in-process side alone still checks its operation files at startup
 * under {@code spring.main.lazy-initialization=true}.
 *
 * <p>
 * The MCP module keeps Spring AI's server bean eager, and the catalog is built as its
 * dependency. Without the MCP server a lazy catalog would let an application with a
 * broken operation file start and report ready, and the first chat request would fail
 * inside a bean creation, outside Spring Boot's failure analysis.
 *
 * <p>
 * The in-process callbacks bean carries two more startup stops: a tool marked elicit, and
 * a tool name that clashes with one of the application's tool beans. The in-process
 * auto-configuration keeps that bean eager too, so both stops still fail startup.
 */
class InProcessLazyInitializationTests {

	// The runner leaves SpringApplication out, which is what reads the property and
	// installs the post-processor, so the post-processor the property installs is added
	// by hand and the property is set beside it, as an application sets it.
	private final ApplicationContextRunner lazyRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.main.lazy-initialization=true")
		.withInitializer(
				(context) -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));

	@Test
	void lazyInitialization_inProcessSideWithABrokenOperationFile_shouldStillStopStartup() {
		this.lazyRunner.withPropertyValues("gatool.in-process.operations.locations=classpath:gatool/broken/")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(OperationFileProblemsException.class));
	}

	@Test
	void lazyInitialization_inProcessToolMarkedElicit_shouldStillStopStartup() {
		this.lazyRunner
			.withPropertyValues("gatool.in-process.operations.locations=classpath:gatool/elicit/",
					"gatool.mcp.operations.locations=optional:classpath*:gatool/none/")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("topRatedMovies"));
	}

	@Test
	void lazyInitialization_applicationToolOfTheSameName_shouldStillStopStartup() {
		this.lazyRunner.withPropertyValues("gatool.in-process.operations.locations=classpath:gatool/in-process/")
			.withBean("clashing", ToolCallback.class, () -> callback("topRatedMovies"))
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InProcessToolNameClashException.class)
				.hasMessageContaining("topRatedMovies"));
	}

	private static ToolCallback callback(String name) {
		return FunctionToolCallback.builder(name, (Map<String, Object> arguments) -> "{}")
			.description("A tool the application declares itself.")
			.inputType(new ParameterizedTypeReference<Map<String, Object>>() {
			})
			.build();
	}

}
