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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stderr report for a stdio server that stops at startup.
 *
 * <p>
 * Console logging is off under stdio, so Boot's own report, which goes through the log,
 * lands in a console appender that is off. The reporter runs on
 * {@code ApplicationFailedEvent}, which Boot publishes ahead of its own reporting, and
 * writes Boot's failure analysis to stderr, or, where every analyzer passes on the
 * failure, the exception's message chain. It steps back while the server runs over HTTP
 * or while the application turned console logging back on, because the report then lands
 * through the log.
 */
class McpStdioStartupFailureReporterTests {

	private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

	private final McpStdioStartupFailureReporter reporter = new McpStdioStartupFailureReporter(
			new PrintStream(this.stderr, true, StandardCharsets.UTF_8));

	@Test
	void onApplicationEvent_stdioWithConsoleLoggingOff_shouldWriteBootsAnalysisToStderr() {
		InvalidConfigurationPropertyValueException cause = new InvalidConfigurationPropertyValueException(
				"gatool.mcp.stdio.granted-scopes", null, "These tools require scopes: movieByLookup (movies:detail).");

		this.reporter.onApplicationEvent(event(environment(true, null), wrapped(cause)));

		String written = written();
		assertThat(written).contains("APPLICATION FAILED TO START")
			.contains("Description:")
			.contains("gatool.mcp.stdio.granted-scopes")
			.contains("These tools require scopes: movieByLookup (movies:detail).")
			.contains("Action:");
	}

	@Test
	void onApplicationEvent_failureWithoutAnAnalyzer_shouldWriteTheMessageChain() {
		IllegalStateException cause = new IllegalStateException("the schema file is unreadable");

		this.reporter.onApplicationEvent(event(environment(true, null), wrapped(cause)));

		assertThat(written()).contains("APPLICATION FAILED TO START")
			.contains("BeanCreationException")
			.contains("Caused by: java.lang.IllegalStateException: the schema file is unreadable");
	}

	@Test
	void onApplicationEvent_overHttp_shouldWriteNothing() {
		this.reporter.onApplicationEvent(event(environment(false, null), wrapped(new IllegalStateException("x"))));

		assertThat(written()).isEmpty();
	}

	@Test
	void onApplicationEvent_stdioWithConsoleLoggingTurnedBackOn_shouldWriteNothing() {
		this.reporter.onApplicationEvent(event(environment(true, "true"), wrapped(new IllegalStateException("x"))));

		assertThat(written()).isEmpty();
	}

	@Test
	void onApplicationEvent_failureBeforeAContextExists_shouldWriteNothing() {
		this.reporter.onApplicationEvent(new ApplicationFailedEvent(new SpringApplication(), new String[0], null,
				new IllegalStateException("x")));

		assertThat(written()).isEmpty();
	}

	@Test
	void factories_shouldRegisterTheReporterForBoot() {
		List<?> listeners = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
			.load(ApplicationListener.class);

		assertThat(listeners).anyMatch(McpStdioStartupFailureReporter.class::isInstance);
	}

	private String written() {
		return this.stderr.toString(StandardCharsets.UTF_8);
	}

	private static Throwable wrapped(Throwable cause) {
		return new BeanCreationException("gaToolMcpStatefulToolSpecifications", "failed", cause);
	}

	private static MockEnvironment environment(boolean stdio, String consoleLogging) {
		MockEnvironment environment = new MockEnvironment();
		if (stdio) {
			environment.setProperty("spring.ai.mcp.server.stdio", "true");
			environment.setProperty("logging.console.enabled", (consoleLogging != null) ? consoleLogging : "false");
		}
		return environment;
	}

	private static ApplicationFailedEvent event(MockEnvironment environment, Throwable failure) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.setEnvironment(environment);
		return new ApplicationFailedEvent(new SpringApplication(), new String[0], context, failure);
	}

}
