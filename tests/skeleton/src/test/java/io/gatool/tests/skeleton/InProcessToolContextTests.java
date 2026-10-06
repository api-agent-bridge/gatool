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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
 * A {@code ChatClient} passes its tool context to every tool it calls. Spring AI's
 * default for the two-argument call logs at INFO on each call that carries one, saying
 * the callback ignores it, which would fill the log of an application whose every call
 * carries a conversation id.
 */
@ExtendWith(OutputCaptureExtension.class)
class InProcessToolContextTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");

	@Test
	void call_withAToolContext_shouldAnswerWithoutTheDefaultsInfoLine(CapturedOutput output) {
		this.contextRunner.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl()).run((context) -> {
			ToolCallback callback = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks()[0];

			String text = callback.call("{\"first\": 1}", new ToolContext(Map.of("conversationId", "c-1")));

			assertThat(text).contains("Signal from Kepler");
		});
		assertThat(output.getAll()).doesNotContain("By default the tool context is not used");
	}

}
