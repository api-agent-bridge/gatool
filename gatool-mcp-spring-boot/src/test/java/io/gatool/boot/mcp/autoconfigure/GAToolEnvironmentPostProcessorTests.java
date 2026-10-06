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

package io.gatool.boot.mcp.autoconfigure;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

// The post-processor runs before the application context exists, so each test calls it
// against an Environment of its own, which is how Spring Boot tests its own.
class GAToolEnvironmentPostProcessorTests {

	private static final String PROTOCOL = "spring.ai.mcp.server.protocol";

	private static final String SERVER_NAME = "spring.ai.mcp.server.name";

	private static final String INSTRUCTIONS = "spring.ai.mcp.server.instructions";

	private static final String SERVER_VERSION = "spring.ai.mcp.server.version";

	private static final String MCP_SERVER_AUTO_CONFIGURATION = "org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration";

	private final GAToolEnvironmentPostProcessor postProcessor = new GAToolEnvironmentPostProcessor();

	@Test
	void postProcessEnvironment_httpDefaults_shouldContributeTheStatelessProtocol() {
		StandardEnvironment environment = new StandardEnvironment();

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(PROTOCOL)).isEqualTo("STATELESS");
	}

	@Test
	void postProcessEnvironment_applicationSetTheProtocol_shouldKeepTheApplicationsValue() {
		StandardEnvironment environment = environmentWith(Map.of(PROTOCOL, "STREAMABLE"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(PROTOCOL)).isEqualTo("STREAMABLE");
	}

	@Test
	void postProcessEnvironment_stdio_shouldContributeStreamableAndTheStdioDefaults() {
		StandardEnvironment environment = environmentWith(Map.of("spring.ai.mcp.server.stdio", "true"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(PROTOCOL)).isEqualTo("STREAMABLE");
		// stdout carries MCP messages alone, so console logging and the banner go off,
		// and the application runs without a web server.
		assertThat(environment.getProperty("logging.console.enabled")).isEqualTo("false");
		assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
		assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
	}

	@Test
	void postProcessEnvironment_stdioWithTheApplicationsOwnBannerMode_shouldKeepIt() {
		StandardEnvironment environment = environmentWith(
				Map.of("spring.ai.mcp.server.stdio", "true", "spring.main.banner-mode", "console"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("console");
		assertThat(environment.getProperty("logging.console.enabled")).isEqualTo("false");
	}

	@Test
	void postProcessEnvironment_stdio_shouldMarkTheConsoleDefaultAsItsOwn() {
		// The listener that sends the log to stderr acts only where the console went off
		// through this default, and the Environment reads a default and an application's
		// own value the same, so the marker carries the difference.
		StandardEnvironment environment = environmentWith(Map.of("spring.ai.mcp.server.stdio", "true"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(GAToolEnvironmentPostProcessor.CONSOLE_DEFAULT_APPLIED)).isEqualTo("true");
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "false" })
	void postProcessEnvironment_stdioWithTheApplicationsOwnConsoleSetting_shouldKeepItAndLeaveTheMarkerOut(
			String value) {
		StandardEnvironment environment = environmentWith(
				Map.of("spring.ai.mcp.server.stdio", "true", "logging.console.enabled", value));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty("logging.console.enabled")).isEqualTo(value);
		assertThat(environment.containsProperty(GAToolEnvironmentPostProcessor.CONSOLE_DEFAULT_APPLIED)).isFalse();
	}

	@Test
	void postProcessEnvironment_httpDefaults_shouldLeaveTheStdioDefaultsOut() {
		StandardEnvironment environment = new StandardEnvironment();

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.containsProperty("logging.console.enabled")).isFalse();
		assertThat(environment.containsProperty(GAToolEnvironmentPostProcessor.CONSOLE_DEFAULT_APPLIED)).isFalse();
		assertThat(environment.containsProperty("spring.main.web-application-type")).isFalse();
	}

	@Test
	void postProcessEnvironment_applicationNamed_shouldNameTheServerAfterIt() {
		// An application that adds the starter and leaves the configuration empty would
		// introduce itself to every client as Spring AI's default, mcp-server. The
		// application's own name is the one GATool can know.
		StandardEnvironment environment = environmentWith(Map.of("spring.application.name", "movies"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(SERVER_NAME)).isEqualTo("movies");
	}

	@Test
	void postProcessEnvironment_applicationUnnamed_shouldLeaveSpringAisDefaultName() {
		StandardEnvironment environment = new StandardEnvironment();

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(SERVER_NAME)).isEqualTo("mcp-server");
	}

	@Test
	void postProcessEnvironment_serverNameSetByHand_shouldKeepIt() {
		StandardEnvironment environment = environmentWith(
				Map.of("spring.application.name", "movies", SERVER_NAME, "movie-tools"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(SERVER_NAME)).isEqualTo("movie-tools");
	}

	@Test
	void postProcessEnvironment_instructionsUnset_shouldSayWhatAGAToolResultIs() {
		StandardEnvironment environment = new StandardEnvironment();

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(INSTRUCTIONS)).isEqualTo(GAToolEnvironmentPostProcessor.INSTRUCTIONS_TEXT)
			.contains("trusted GraphQL documents")
			.contains("errors array");
	}

	@ParameterizedTest
	@ValueSource(strings = { "dynamic-three-step", "DYNAMIC_THREE_STEP" })
	void postProcessEnvironment_dynamicThreeStepTools_shouldDescribeTheLoopInsteadOfTrustedDocuments(String value) {
		// Under this switch three of the tools run GraphQL the model wrote, so a sentence
		// that says every tool runs a trusted document misleads the model about the
		// tools it has. The value is compared the way Boot binds it, so both spellings
		// reach the same text.
		StandardEnvironment environment = environmentWith(Map.of("gatool.dev.experimental.generate-tools", value));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(INSTRUCTIONS)).doesNotStartWith("These tools run trusted GraphQL documents")
			.contains("search", "type", "run", "refus")
			.contains("errors array");
	}

	@Test
	void postProcessEnvironment_perRootFieldTools_shouldKeepTheTrustedDocumentsText() {
		// A generated tool per root field still runs a document GATool wrote and holds,
		// so the default sentence stays true there.
		StandardEnvironment environment = environmentWith(
				Map.of("gatool.dev.experimental.generate-tools", "all-root-queries"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(INSTRUCTIONS)).isEqualTo(GAToolEnvironmentPostProcessor.INSTRUCTIONS_TEXT);
	}

	@Test
	void postProcessEnvironment_applicationVersionSet_shouldVersionTheServerAfterIt() {
		// The server name follows spring.application.name, and the version would
		// otherwise stay at Spring AI's 1.0.0 whatever the application's own version
		// says.
		StandardEnvironment environment = environmentWith(Map.of("spring.application.version", "2.3.4"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(SERVER_VERSION)).isEqualTo("2.3.4");
	}

	@Test
	void postProcessEnvironment_applicationVersionUnset_shouldLeaveSpringAisDefaultVersion() {
		// Spring AI's properties class refuses an empty version, so the default stays
		// unset here and Spring AI's own 1.0.0 stands.
		StandardEnvironment environment = new StandardEnvironment();

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.containsProperty(SERVER_VERSION)).isFalse();
	}

	@Test
	void postProcessEnvironment_serverVersionSetByHand_shouldKeepIt() {
		StandardEnvironment environment = environmentWith(
				Map.of("spring.application.version", "2.3.4", SERVER_VERSION, "9.9.9"));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(SERVER_VERSION)).isEqualTo("9.9.9");
	}

	@Test
	void postProcessEnvironment_instructionsWrittenByTheApplication_shouldKeepThem() {
		StandardEnvironment environment = environmentWith(Map.of(INSTRUCTIONS, "Ask before you mutate."));

		this.postProcessor.postProcessEnvironment(environment, new SpringApplication());

		assertThat(environment.getProperty(INSTRUCTIONS)).isEqualTo("Ask before you mutate.");
	}

	@Test
	void postProcessEnvironment_mcpStarterAbsent_shouldContributeNothing() {
		// Boot asks a starter not to make assumptions about the project it is added to.
		// An
		// application that depends on gatool-mcp-spring-boot directly, without the Spring
		// AI
		// server the MCP starter brings, runs without a server that would read these
		// defaults.
		StandardEnvironment environment = environmentWith(
				Map.of("spring.application.name", "movies", "spring.ai.mcp.server.stdio", "true"));
		SpringApplication application = new SpringApplication();
		application.setResourceLoader(new DefaultResourceLoader(withoutClass(MCP_SERVER_AUTO_CONFIGURATION)));

		this.postProcessor.postProcessEnvironment(environment, application);

		assertThat(environment.containsProperty(PROTOCOL)).isFalse();
		assertThat(environment.containsProperty(SERVER_NAME)).isFalse();
		assertThat(environment.containsProperty(INSTRUCTIONS)).isFalse();
		assertThat(environment.containsProperty("logging.console.enabled")).isFalse();
	}

	private static StandardEnvironment environmentWith(Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		return environment;
	}

	// A class loader that hides one class, the way the in-process starter's classpath
	// lacks it, and delegates every other name to this test's loader.
	private static ClassLoader withoutClass(String hiddenClass) {
		return new ClassLoader(GAToolEnvironmentPostProcessorTests.class.getClassLoader()) {

			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (hiddenClass.equals(name)) {
					throw new ClassNotFoundException(name);
				}
				return super.loadClass(name, resolve);
			}
		};
	}

}
