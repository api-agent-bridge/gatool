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
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A name shared by an operation file and one of the application's own tools, found
 * through Spring AI's own scanner and converter beans, stops startup naming both sources.
 */
class McpToolNameClashTests {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class,
				StatelessToolCallbackConverterAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_mcpToolMethodSharingAnOperationFilesName_shouldStopNamingBothSources() {
		this.contextRunner.withUserConfiguration(AnnotatedTool.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("topRatedMovies")
				.hasStackTraceContaining("@McpTool");
		});
	}

	@Test
	void startup_toolCallbackBeanSharingAnOperationFilesName_shouldStopNamingBothSources() {
		this.contextRunner.withUserConfiguration(CallbackTool.class).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("topRatedMovies")
				.hasStackTraceContaining("ToolCallback");
		});
	}

	@Test
	void startup_applicationToolWithAnotherName_shouldStart() {
		this.contextRunner.withUserConfiguration(OtherTool.class).run((context) -> assertThat(context).hasNotFailed());
	}

	@Configuration(proxyBeanMethods = false)
	static class AnnotatedTool {

		@Bean
		ClashingTools clashingTools() {
			return new ClashingTools();
		}

	}

	static class ClashingTools {

		@McpTool(name = "topRatedMovies", description = "The application's own list.")
		String topRatedMovies() {
			return "[]";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CallbackTool {

		@Bean
		ToolCallback topRatedMoviesCallback() {
			return FunctionToolCallback.builder("topRatedMovies", (String input) -> "[]")
				.description("The application's own list.")
				.inputType(String.class)
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OtherTool {

		@Bean
		ToolCallback weatherCallback() {
			return FunctionToolCallback.builder("weather", (String input) -> "sunny")
				.description("The weather.")
				.inputType(String.class)
				.build();
		}

	}

}
