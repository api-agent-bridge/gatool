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

package io.gatool.tests.consumer.mcp;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An application on the MCP starter alone, without Spring Security and without the unsafe
 * switch, reads the designed stop.
 *
 * <p>
 * The starter leaves Spring Security out, so on this module's classpath the check that
 * refuses unauthenticated callers runs without the security classes, and it has to reach
 * the message that names the dependency to add without a {@code NoClassDefFoundError} for
 * {@code org.springframework.security.core.Authentication}. A test that hides the package
 * with a {@code FilteredClassLoader} on the skeleton's classpath passes either way, so
 * the real classpath is the one to hold this on. The other test in this module sets the
 * switch, so this one is where the stop itself is held.
 */
class McpStarterWithoutSecurityTests {

	@Test
	void startup_withoutSpringSecurityAndWithTheUnsafeSwitchOff_shouldStopNamingTheResourceServerStarter() {
		assertThat(
				ClassUtils.isPresent("org.springframework.security.core.Authentication", getClass().getClassLoader()))
			.as("Spring Security absent from the MCP starter's classpath")
			.isFalse();

		Throwable failure = catchThrowable(
				() -> new SpringApplicationBuilder(SecurityFreeApplication.class).web(WebApplicationType.SERVLET)
					.properties("server.port=0", "spring.main.banner-mode=off",
							"spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
							"gatool.api.schema.location=classpath:greetings.graphqls")
					.run()
					.close());

		assertThat(failure).isNotNull();
		List<String> causeTypes = new ArrayList<>();
		StringBuilder messages = new StringBuilder();
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			causeTypes.add(cause.getClass().getName());
			messages.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage()).append('\n');
		}
		assertThat(causeTypes).doesNotContain(NoClassDefFoundError.class.getName())
			.contains(InvalidConfigurationPropertyValueException.class.getName());
		assertThat(messages).doesNotContain("org/springframework/security/core/Authentication")
			.contains("gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication")
			.contains("Add spring-boot-starter-security-oauth2-resource-server");
	}

	// Without a component scan, so that the other test's application stays out.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class SecurityFreeApplication {

	}

}
