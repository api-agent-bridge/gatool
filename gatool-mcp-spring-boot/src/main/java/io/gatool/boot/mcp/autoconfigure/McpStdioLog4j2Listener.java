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

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LoggingSystemProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import io.gatool.boot.internal.SpringAiMcpKeys;

/**
 * Keeps stdout of a stdio server on Log4j2 to the MCP messages while Spring Boot starts
 * the log: the console stays off, and what Log4j2 reports about itself goes to stderr.
 *
 * <p>
 * {@link GAToolEnvironmentPostProcessor} turns console logging off under stdio, because
 * Spring Boot writes the console to stdout, which carries the MCP messages. On Log4j2 the
 * switch reaches the console appender through a system property,
 * {@code CONSOLE_LOG_THRESHOLD}, which Boot's Log4j2 configuration reads as the threshold
 * of that appender. Boot's {@code LoggingSystemProperties} writes a system property only
 * while it is unset, and it writes the value of {@code logging.threshold.console} ahead
 * of the {@code OFF} that {@code logging.console.enabled=false} stands for. So a server
 * started with {@code logging.threshold.console=INFO} would keep its console, and its log
 * would be written to stdout between the MCP messages. On Logback that property sets what
 * stderr carries, which is why an operator of a stdio server sets it.
 *
 * <p>
 * This listener writes {@code OFF} into the system property ahead of Boot, which then
 * finds the property set and leaves it as it is. It runs one place ahead of
 * {@link LoggingApplicationListener}, on the event that prepared the environment, so it
 * reads the same environment as Boot does. The property stays set for the JVM, as it does
 * where Boot writes it.
 *
 * <p>
 * The property is written under the conditions {@link McpStdioLogListener} writes its
 * line under, the log file aside: the server runs over stdio, the console went off
 * through GATool's default, Boot chose Log4j2 itself, and {@code logging.config} is
 * unset. An application that names its configuration in {@code logging.config} took the
 * log over, and the property may be what that configuration reads for an appender of its
 * own.
 *
 * <p>
 * The listener also begins the watch of {@link Log4j2StatusLog}, which sends the reports
 * of Log4j2's status logger to stderr. Boot registers the listener that writes them to
 * stdout whichever configuration it loads, so the watch begins on Log4j2 whatever
 * {@code logging.config} says, and {@link McpStdioLogListener} ends it once Boot has
 * started the log.
 *
 * @author Željko Kozina
 */
class McpStdioLog4j2Listener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

	private static final String CONSOLE_THRESHOLD = LoggingSystemProperty.CONSOLE_THRESHOLD
		.getEnvironmentVariableName();

	/**
	 * Creates the listener, which Spring Boot instantiates from
	 * {@code META-INF/spring.factories}.
	 */
	McpStdioLog4j2Listener() {
	}

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		Environment environment = event.getEnvironment();
		if (!SpringAiMcpKeys.servesStdio(environment)
				|| !McpStdioLogListener.consoleOffThroughTheDefault(environment)) {
			return;
		}
		ClassLoader classLoader = event.getSpringApplication().getClassLoader();
		if (McpStdioLogListener.onLog4j2AsBootConfiguresIt(environment, classLoader)) {
			System.setProperty(CONSOLE_THRESHOLD, "OFF");
		}
		if (McpStdioLogListener.onLog4j2(classLoader)) {
			Log4j2StatusLog.watch(classLoader);
		}
	}

	// One place ahead of Boot's listener, which reads the system property and
	// registers its status listener when it initialises the log.
	@Override
	public int getOrder() {
		return LoggingApplicationListener.DEFAULT_ORDER - 1;
	}

}
