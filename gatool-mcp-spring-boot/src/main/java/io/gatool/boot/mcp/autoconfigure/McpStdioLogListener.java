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

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import io.gatool.boot.internal.SpringAiMcpKeys;

/**
 * Sends the log of a stdio server to stderr.
 *
 * <p>
 * {@link GAToolEnvironmentPostProcessor} turns console logging off under stdio, because
 * Spring Boot writes the log to stdout, the JSON-RPC channel. With the console off and
 * {@code logging.file.name} unset, every line a running server writes would be lost: a
 * refused credential, an API answering 5xx and a rate limit would each leave both streams
 * empty. MCP says a stdio server may write UTF-8 strings to stderr for its logging, and
 * that a client may capture, forward or ignore them, so the log goes to stderr while the
 * console stays off. stderr carries WARN and above until
 * {@code logging.threshold.console} says otherwise, and the lines pass through a queue
 * that a thread of its own writes, so a host that leaves the pipe unread loses lines
 * while the server keeps answering, and a line of the log says how many once the queue
 * takes lines again. {@link LogbackStderrLog} holds the reasons for each.
 *
 * <p>
 * Spring Boot 4.1.1 does not offer a property that points the console at stderr. Its
 * {@code DefaultLogbackConfiguration} creates the console appender without a target,
 * which leaves Logback's default, {@code System.out}, and its
 * {@code console-appender.xml} leaves the target out as well. So this listener attaches
 * an appender of GATool's own, which {@link LogbackStderrLog} builds from the settings
 * Boot reads for the console.
 *
 * <p>
 * The appender is attached on {@link ApplicationEnvironmentPreparedEvent}, one place
 * behind {@link LoggingApplicationListener}, which initialises the log on that event.
 * Boot initialises the log for every {@code SpringApplication} that finds it
 * uninitialised, and each initialisation resets the Logback context, which drops every
 * appender. Every {@code SpringApplication} publishes this event, so an application
 * started again in the same JVM, which is how the developer tools restart one, passes
 * through this listener behind Boot's reset and gets the appender again. Beside an
 * application that is still running Boot leaves the log as it is, and the listener finds
 * its appender attached and leaves it alone. Code that resets the Logback context by hand
 * while the server runs drops the appender until the next start. Between Boot's
 * initialisation and the attachment the console is already off, so a line written there
 * is lost and stdout stays clean. A startup failure is written by
 * {@link McpStdioStartupFailureReporter}, directly, so it arrives without the queue.
 *
 * <p>
 * The listener steps back while the server runs over HTTP, while
 * {@code gatool.mcp.stdio.log-to-stderr} is {@code false}, and while the application set
 * {@code logging.console.enabled} itself, whichever value it chose.
 * {@link LogbackStderrLog} steps back where the application brought a Logback
 * configuration of its own, and the listener stays silent there: the application took the
 * log over, and GATool cannot tell where that configuration writes, so a line that called
 * the log lost would be wrong for each application that sends it to a file or to stderr
 * itself.
 *
 * <p>
 * On another logging system the listener leaves the log as it is, and the logging system
 * decides what becomes of it. On Log4j2 Boot's configuration reads the threshold that
 * turns the console off, and {@link McpStdioLog4j2Listener} keeps that threshold at
 * {@code OFF} where the application set {@code logging.threshold.console}, so the log is
 * lost unless the application sets a log file or brings a Log4j2 configuration that
 * writes it. What Log4j2 reports about itself goes to stderr, through
 * {@link Log4j2StatusLog}. Startup says so in one line on stderr, once for the JVM, while
 * {@code logging.file.name}, {@code logging.file.path} and {@code logging.config} are
 * unset and Boot chose the logging system itself. On {@code java.util.logging} Boot's
 * configuration leaves {@code logging.console.enabled} unread, and the console handler of
 * the JDK writes to {@code System.err}: a server started on it writes its log to stderr
 * at INFO and above, on the thread that logs, so the listener stays silent.
 *
 * @author Željko Kozina
 */
class McpStdioLogListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

	static final String LOG_TO_STDERR = "gatool.mcp.stdio.log-to-stderr";

	// The class every appender hangs on. LogbackStderrLog names Logback's types and is
	// loaded only behind this check, so an application on Log4j2 or on
	// java.util.logging starts without Logback on its classpath.
	private static final String LOGBACK = "ch.qos.logback.classic.LoggerContext";

	// The class Boot looks for before it chooses Log4j2, which it does where Logback is
	// absent. With both absent it chooses java.util.logging.
	private static final String LOG4J2 = "org.apache.logging.log4j.core.impl.Log4jContextFactory";

	private static final String LOGGING_CONFIG = "logging.config";

	/**
	 * The line startup writes to stderr on Log4j2. It leaves room for a Log4j2 file the
	 * application brought under a name Log4j2 finds by itself, such as
	 * {@code log4j2.xml}, because reading which configuration Log4j2 loaded takes
	 * Log4j2's own types.
	 */
	static final String LOG_IS_LOST = "GATool: the log of this stdio server is lost, unless the "
			+ "application's own Log4j2 configuration writes it. Console logging is off, because the console "
			+ "writes to stdout, which carries the MCP messages, and GATool writes the log to stderr on Logback "
			+ "alone. Set logging.file.name to write the log to a file, or configure an appender that writes to "
			+ "stderr in the application's Log4j2 configuration.";

	// Every SpringApplication publishes the event, so an application started again in
	// this JVM passes through the listener again, and so does one started beside
	// another. The line is for the operator of the process, who reads it once.
	private static final AtomicBoolean logIsLostSaid = new AtomicBoolean();

	/**
	 * Creates the listener, which Spring Boot instantiates from
	 * {@code META-INF/spring.factories}.
	 */
	McpStdioLogListener() {
	}

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		Environment environment = event.getEnvironment();
		if (!SpringAiMcpKeys.servesStdio(environment) || !consoleOffThroughTheDefault(environment)) {
			return;
		}
		ClassLoader classLoader = event.getSpringApplication().getClassLoader();
		// What Log4j2 reports about itself leaves stdout whatever the stderr log is set
		// to, because stdout carries the MCP messages.
		if (onLog4j2(classLoader)) {
			Log4j2StatusLog.sendToStderr(classLoader);
		}
		if (!logToStderr(environment)) {
			return;
		}
		if (ClassUtils.isPresent(LOGBACK, classLoader)) {
			LogbackStderrLog.attach(environment);
		}
		else if (onLog4j2AsBootConfiguresIt(environment, classLoader) && LogFile.get(environment) == null) {
			sayThatTheLogIsLost();
		}
	}

	// For the tests, each of which starts as a JVM that has yet to write the line.
	static void forgetThatTheLogIsLostWasSaid() {
		logIsLostSaid.set(false);
	}

	// One place behind Boot's listener, so the log is initialised by the time the
	// appender is attached, and the lines lost in between are the few that the
	// listeners sharing this place write.
	@Override
	public int getOrder() {
		return LoggingApplicationListener.DEFAULT_ORDER + 1;
	}

	// The marker says the application left logging.console.enabled unset, and the value
	// is read beside it because a source added behind the post-processor could still
	// turn the console on.
	static boolean consoleOffThroughTheDefault(Environment environment) {
		return environment.getProperty(GAToolEnvironmentPostProcessor.CONSOLE_DEFAULT_APPLIED, Boolean.class,
				Boolean.FALSE)
				&& !environment.getProperty(GAToolEnvironmentPostProcessor.CONSOLE_LOGGING, Boolean.class,
						Boolean.TRUE);
	}

	// Whether the log is in the hands of Log4j2 and of the configuration Boot brings for
	// it, which keeps the console silent under the threshold GATool's default leads to.
	// An application that names a configuration in logging.config, or a logging system in
	// Boot's system property, took that decision over, and GATool cannot tell where its
	// log goes. Asking Boot for the logging system, through LoggingSystem.get, would
	// answer with a second instance of the system, and Boot's factories look for the
	// classes in Boot's own class loader, where this listener is handed the
	// application's.
	static boolean onLog4j2AsBootConfiguresIt(Environment environment, ClassLoader classLoader) {
		return onLog4j2(classLoader) && !StringUtils.hasLength(System.getProperty(LoggingSystem.SYSTEM_PROPERTY))
				&& !StringUtils.hasText(environment.getProperty(LOGGING_CONFIG));
	}

	// Whether the classpath holds Log4j2 where it would hold Logback, which is where
	// Boot chooses Log4j2.
	static boolean onLog4j2(ClassLoader classLoader) {
		return !ClassUtils.isPresent(LOGBACK, classLoader) && ClassUtils.isPresent(LOG4J2, classLoader);
	}

	// The line is written here, on the event that prepared the environment. That is
	// early, ahead of the context, and everything the line depends on is known by then.
	// It is a write to System.err of its own, like the report of a startup failure,
	// because the log it speaks of is the one that is missing. Such a write waits where
	// the pipe is full. At this point of the start the pipe holds what Log4j2 reported
	// while Boot started the log, and a configuration that loads without a fault leaves
	// it empty, so the write returns. A bean of the auto-configuration would write later,
	// behind whatever the start has sent to stderr, and stay silent where the start fails
	// ahead of it.
	private static void sayThatTheLogIsLost() {
		if (logIsLostSaid.compareAndSet(false, true)) {
			System.err.print(LOG_IS_LOST + System.lineSeparator());
			System.err.flush();
		}
	}

	// The key is read here because the log starts before GAToolProperties is bound. A
	// value that is not a boolean counts as the default: an exception thrown from this
	// event stops startup before a context exists, where the console is off and the
	// failure would be missing from both streams. The binder refuses the same value
	// later, and that failure is written to the log this listener attached.
	private static boolean logToStderr(Environment environment) {
		try {
			return environment.getProperty(LOG_TO_STDERR, Boolean.class, Boolean.TRUE);
		}
		catch (ConversionException ex) {
			return true;
		}
	}

}
