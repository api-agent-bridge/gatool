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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An in-process tool answers a ChatClient in the same JVM, without a client to ask, so a
 * tool marked elicit stops startup there unless the ignore property says to serve it
 * without asking.
 */
@ExtendWith(OutputCaptureExtension.class)
class InProcessElicitStartupTests {

	private static final String WARNING = "gatool.elicitation.ignore-where-unsupported is true, so these in-process "
			+ "tools run without asking the user: ";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.in-process.operations.locations=classpath:gatool/elicit/",
				"gatool.mcp.operations.locations=optional:classpath*:gatool/none/");

	@Test
	void startup_inProcessToolMarkedElicit_shouldStopNamingTheToolAndTheProperty() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("topRatedMovies")
				.hasMessageContaining("in-process")
				.hasMessageContaining("gatool.elicitation.ignore-where-unsupported=true");
		});
	}

	@Test
	void startup_twoInProcessToolsMarkedElicit_shouldStopNamingBothInThePlural() {
		this.contextRunner.withPropertyValues("gatool.in-process.operations.locations=classpath:gatool/elicit-pair/")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOfSatisfying(InvalidConfigurationPropertyValueException.class, (stop) -> {
						assertThat(stop.getName()).isEqualTo("gatool.elicitation.ignore-where-unsupported");
						assertThat(stop.getReason()).isEqualTo("2 in-process tools ask the user before each call "
								+ "(movieByLookup, topRatedMovies), and an in-process tool answers a ChatClient in the "
								+ "same JVM, which cannot ask. Serve the tool through the MCP starter on the stateful "
								+ "Streamable HTTP transport, or set gatool.elicitation.ignore-where-unsupported=true "
								+ "to serve it without asking.");
					});
			});
	}

	@Test
	void startup_inProcessToolMarkedElicitUnderTheIgnoreProperty_shouldStartAndWarn(CapturedOutput output) {
		this.contextRunner.withPropertyValues("gatool.elicitation.ignore-where-unsupported=true").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(GAToolCallbacks.class);
		});

		// The line separator after the full stop pins the end of the sentence.
		assertThat(output).contains(WARNING + "topRatedMovies." + System.lineSeparator());
	}

	@Test
	void startup_ignorePropertyWithoutAToolMarkedElicit_shouldStartWithoutTheWarning(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("gatool.in-process.operations.locations=classpath:gatool/in-process/",
					"gatool.elicitation.ignore-where-unsupported=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks())
					.singleElement()
					.satisfies((tool) -> assertThat(tool.getToolDefinition().name()).isEqualTo("topRatedMovies"));
			});

		assertThat(output).doesNotContain("gatool.elicitation.ignore-where-unsupported is true");
	}

}
