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
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup checks of the MCP auto-configuration still run under
 * {@code spring.main.lazy-initialization=true}.
 *
 * <p>
 * Boot's lazy initialization defers every bean until something asks for it. Spring AI's
 * server asks for the tool lists on the first request, so the checks inside two lazy list
 * beans would run at the first call, where a stop reads as a failed call. The list beans
 * are eager by declaration, and so is the auto-configuration instance, whose own three
 * checks the lists reach through the limiter bean it builds.
 */
class McpLazyInitializationTests {

	private static final String LAZY_INITIALIZATION = "spring.main.lazy-initialization=true";

	// The runner leaves SpringApplication out, which is what reads the property and
	// installs the post-processor, so the post-processor the property installs is added
	// by hand and the property is set beside it, as an application sets it.
	private final WebApplicationContextRunner lazyRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"spring.ai.mcp.server.protocol=STATELESS", LAZY_INITIALIZATION)
		.withInitializer(
				(context) -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));

	@Test
	void lazyInitialization_httpWithoutTheUnsafeSwitch_shouldStillStopStartupAndNameTheProperty() {
		// Spring Security is on this test classpath, so it is hidden here: with it
		// present the security auto-configuration takes over and this stop stays quiet.
		this.lazyRunner.withClassLoader(new FilteredClassLoader(HttpSecurity.class))
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("allow-mcp-calls-without-authentication"));
	}

	@Test
	void lazyInitialization_asyncServerType_shouldStillStopStartupAndNameTheProperty() {
		// This stop runs while the auto-configuration instance is built, and the eager
		// tool list reaches that instance through the limiter bean it builds.
		this.lazyRunner
			.withPropertyValues("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
					"spring.ai.mcp.server.type=ASYNC")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring.ai.mcp.server.type"));
	}

}
