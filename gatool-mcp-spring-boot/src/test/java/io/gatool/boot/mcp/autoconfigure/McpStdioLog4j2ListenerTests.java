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

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console of a stdio server on Log4j2 stays off, whatever
 * {@code logging.threshold.console} says.
 *
 * <p>
 * Spring Boot hands the console switch to its Log4j2 configuration through the system
 * property {@code CONSOLE_LOG_THRESHOLD}, and it writes that property only while it is
 * unset, the value of {@code logging.threshold.console} first. The listener writes
 * {@code OFF} ahead of Boot. Each test publishes the event to the listener the way Boot
 * does and reads the system property, which is what Boot's Log4j2 configuration reads.
 * The tests of {@code tests/skeleton} start a server on Log4j2 and read its streams.
 *
 * <p>
 * Log4j2 is absent from the classpath of these tests, so the application's class loader
 * stands in for the classpath of an application on Log4j2, as it does in
 * {@link McpStdioLogListenerTests}. The system properties are put back after each test,
 * because the JVM is shared with the other tests of the module.
 */
class McpStdioLog4j2ListenerTests {

	private static final String CONSOLE_THRESHOLD = "CONSOLE_LOG_THRESHOLD";

	private static final String BOOTS_LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";

	private static final Map<String, Object> STDIO_ALONE = Map.of("spring.ai.mcp.server.stdio", "true");

	private final Properties systemProperties = (Properties) System.getProperties().clone();

	@BeforeEach
	void clearTheThreshold() {
		System.clearProperty(CONSOLE_THRESHOLD);
		System.clearProperty(BOOTS_LOGGING_SYSTEM);
	}

	@AfterEach
	void putTheSystemPropertiesBackAndEndTheWatch() {
		System.setProperties(this.systemProperties);
		// The listener begins the watch of the status logger, which is one for the JVM.
		Log4j2StatusLog.sendToStderr(getClass().getClassLoader());
	}

	@Test
	void onApplicationEvent_log4j2WithTheConsoleThresholdSet_shouldWriteOffIntoTheSystemProperty() {
		new McpStdioLog4j2Listener()
			.onApplicationEvent(McpStdioLogListenerTests.event(McpStdioLogListenerTests.onLog4j2(),
					Map.of("spring.ai.mcp.server.stdio", "true", "logging.threshold.console", "INFO")));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isEqualTo("OFF");
	}

	@Test
	void onApplicationEvent_log4j2WithTheThresholdAsASystemProperty_shouldWriteOffOverIt() {
		// Boot leaves a property that is set as it is, so a value that was there ahead of
		// the start would keep the console on.
		System.setProperty(CONSOLE_THRESHOLD, "DEBUG");

		new McpStdioLog4j2Listener()
			.onApplicationEvent(McpStdioLogListenerTests.event(McpStdioLogListenerTests.onLog4j2(), STDIO_ALONE));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isEqualTo("OFF");
	}

	@Test
	void onApplicationEvent_logback_shouldLeaveTheSystemPropertyUnset() {
		// On Logback GATool's appender reads logging.threshold.console for stderr, and
		// Boot leaves the console appender out while the console is off.
		new McpStdioLog4j2Listener().onApplicationEvent(McpStdioLogListenerTests.event(new SpringApplication(),
				Map.of("spring.ai.mcp.server.stdio", "true", "logging.threshold.console", "INFO")));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isNull();
	}

	@Test
	void onApplicationEvent_log4j2OverHttp_shouldLeaveTheSystemPropertyUnset() {
		new McpStdioLog4j2Listener()
			.onApplicationEvent(McpStdioLogListenerTests.event(McpStdioLogListenerTests.onLog4j2(), Map.of()));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isNull();
	}

	@Test
	void onApplicationEvent_log4j2WithTheConsoleSetByTheApplication_shouldLeaveTheSystemPropertyUnset() {
		new McpStdioLog4j2Listener()
			.onApplicationEvent(McpStdioLogListenerTests.event(McpStdioLogListenerTests.onLog4j2(),
					Map.of("spring.ai.mcp.server.stdio", "true", "logging.console.enabled", "true")));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isNull();
	}

	@Test
	void onApplicationEvent_log4j2WithAConfigurationOfTheApplication_shouldLeaveTheSystemPropertyUnset() {
		// The application named a Log4j2 file of its own, which may read the property
		// for an appender that writes to stderr.
		new McpStdioLog4j2Listener()
			.onApplicationEvent(McpStdioLogListenerTests.event(McpStdioLogListenerTests.onLog4j2(),
					Map.of("spring.ai.mcp.server.stdio", "true", "logging.config", "classpath:log4j2-server.xml")));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isNull();
	}

	@Test
	void onApplicationEvent_log4j2BesideALoggingSystemNamedByTheApplication_shouldLeaveTheSystemPropertyUnset() {
		System.setProperty(BOOTS_LOGGING_SYSTEM, "none");

		new McpStdioLog4j2Listener()
			.onApplicationEvent(McpStdioLogListenerTests.event(McpStdioLogListenerTests.onLog4j2(), STDIO_ALONE));

		assertThat(System.getProperty(CONSOLE_THRESHOLD)).isNull();
	}

	@Test
	void factories_shouldRegisterTheListenerOnePlaceAheadOfBootsLoggingListener() {
		List<?> listeners = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
			.load(ApplicationListener.class);

		assertThat(listeners).filteredOn(McpStdioLog4j2Listener.class::isInstance)
			.singleElement()
			.satisfies((listener) -> assertThat(((McpStdioLog4j2Listener) listener).getOrder())
				.isEqualTo(LoggingApplicationListener.DEFAULT_ORDER - 1));
	}

}
