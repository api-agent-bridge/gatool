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

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzedException;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;
import org.springframework.util.StringUtils;

import io.gatool.boot.internal.SpringAiMcpKeys;

/**
 * Writes the startup failure of a stdio server to stderr.
 *
 * <p>
 * {@link GAToolEnvironmentPostProcessor} turns console logging off under stdio, because
 * Spring Boot writes the log to stdout, the JSON-RPC channel. Boot reports a startup
 * failure through the log alone, so a stdio server that stops would exit with 1 and leave
 * the host that launched it reading two empty streams. MCP leaves stderr to a server for
 * its logging, so the report goes there: Boot's failure analysis, formatted as Boot's own
 * reporter formats it, or, where every analyzer passes on the failure, the exception's
 * message chain. stdout stays untouched.
 *
 * <p>
 * {@code SpringApplication} publishes {@link ApplicationFailedEvent} ahead of its own
 * reporting, and a listener named in {@code META-INF/spring.factories} receives it
 * whether or not the context refreshed. Boot's {@code FailureAnalyzers} is
 * package-private, so the analyzers are loaded here the way it loads them: through
 * {@link SpringFactoriesLoader}, with the bean factory and the environment offered to
 * their constructors.
 *
 * <p>
 * The reporter steps back while the server runs over HTTP, and while the application
 * turned console logging back on, because Boot's report then lands through the log.
 *
 * <p>
 * It keeps writing while {@link McpStdioLogListener} has the log on stderr. That log
 * passes through a queue, which a thread of its own writes, so a line of the log arrives
 * only where the queue is written out before the process exits, and a server that stopped
 * at startup exits at once. A host reads the analysis to learn why the server stopped. It
 * is written here, on a daemon thread {@link #onApplicationEvent(ApplicationFailedEvent)}
 * waits for at most two seconds, and {@link LogbackStderrLog} keeps Boot's copy of it out
 * of the stderr log, which leaves one analysis on the stream. Beside a pipe a host has
 * left full past that wait, the report is lost, and exit code 1 is what a host reads
 * instead. A host that reads later may find a truncated report as well, where the pipe
 * took part of the text before it filled.
 *
 * @author Željko Kozina
 */
class McpStdioStartupFailureReporter implements ApplicationListener<ApplicationFailedEvent> {

	private static final Log logger = LogFactory.getLog(McpStdioStartupFailureReporter.class);

	private static final String CONSOLE_LOGGING = "logging.console.enabled";

	/**
	 * A failed start beside an unread pipe exits with code 1 about 4.4 seconds after its
	 * start under this bound, and a host that reads stderr receives the report well
	 * inside it.
	 */
	private static final Duration WRITE_BOUND = Duration.ofSeconds(2);

	private final @Nullable PrintStream stream;

	/**
	 * Creates the reporter Boot instantiates, which writes to {@code System.err} as it is
	 * at the time of the failure.
	 */
	McpStdioStartupFailureReporter() {
		this(null);
	}

	// For the test, which reads what was written.
	McpStdioStartupFailureReporter(@Nullable PrintStream stream) {
		this.stream = stream;
	}

	@Override
	public void onApplicationEvent(ApplicationFailedEvent event) {
		ConfigurableApplicationContext context = event.getApplicationContext();
		// Without a context the failure came before the environment was prepared, and
		// Boot's logging is still at its default then, which writes to the console.
		if (context == null) {
			return;
		}
		Environment environment = context.getEnvironment();
		if (!SpringAiMcpKeys.servesStdio(environment)
				|| Boolean.TRUE.equals(environment.getProperty(CONSOLE_LOGGING, Boolean.class, Boolean.TRUE))) {
			return;
		}
		// The write can wait where the pipe is full, so it runs on a daemon thread, and
		// this method waits for it for at most two seconds. A server that fails at
		// startup has written a few lines at WARN and above by then, so the pipe has
		// room. Where the wait runs out, the report is lost and exit code 1 is what a
		// host reads instead. A host that reads later may find a truncated report as
		// well, where the pipe took part of the text before it filled.
		PrintStream out = (this.stream != null) ? this.stream : System.err;
		String text = report(event.getException(), context);
		Thread write = new Thread(() -> {
			out.print(text);
			out.flush();
		}, "gatool-mcp-stdio-startup-failure-report");
		write.setDaemon(true);
		write.start();
		try {
			write.join(WRITE_BOUND.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static String report(Throwable failure, ConfigurableApplicationContext context) {
		FailureAnalysis analysis = analyze(failure, context);
		StringBuilder report = new StringBuilder();
		report.append(String.format("%n%n"));
		report.append(String.format("***************************%n"));
		report.append(String.format("APPLICATION FAILED TO START%n"));
		report.append(String.format("***************************%n%n"));
		if (analysis != null) {
			report.append(String.format("Description:%n%n"));
			report.append(String.format("%s%n", analysis.getDescription()));
			if (StringUtils.hasText(analysis.getAction())) {
				report.append(String.format("%nAction:%n%n"));
				report.append(String.format("%s%n", analysis.getAction()));
			}
			return report.toString();
		}
		report.append(String.format("%s%n", failure));
		for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
			report.append(String.format("Caused by: %s%n", cause));
		}
		return report.toString();
	}

	private static @Nullable FailureAnalysis analyze(Throwable failure, ConfigurableApplicationContext context) {
		for (FailureAnalyzer analyzer : loadAnalyzers(context)) {
			try {
				FailureAnalysis analysis = analyzer.analyze(failure);
				if (analysis != null) {
					return analysis;
				}
			}
			catch (Throwable ex) {
				logger.trace("FailureAnalyzer " + analyzer + " failed", ex);
			}
		}
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof FailureAnalyzedException analyzed) {
				return analyzed.analysis();
			}
		}
		return null;
	}

	private static List<FailureAnalyzer> loadAnalyzers(ConfigurableApplicationContext context) {
		ArgumentResolver arguments = ArgumentResolver.of(Environment.class, context.getEnvironment());
		try {
			arguments = arguments.and(BeanFactory.class, context.getBeanFactory());
		}
		catch (IllegalStateException ex) {
			// A context whose bean factory is gone offers the environment alone.
			logger.trace("The failed context's bean factory is gone, so the analyzers get the environment alone", ex);
		}
		return SpringFactoriesLoader.forDefaultResourceLocation(context.getClassLoader())
			.load(FailureAnalyzer.class, arguments, FailureHandler.logging(logger));
	}

}
