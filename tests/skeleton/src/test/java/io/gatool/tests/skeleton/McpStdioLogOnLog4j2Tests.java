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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.gatool.tests.skeleton.McpStdioLogTests.Server;
import io.gatool.tests.skeleton.McpStdioLogTests.Streams;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a stdio server on Log4j2 writes to its two streams.
 *
 * <p>
 * GATool writes the log of a stdio server to stderr through an appender built on Logback.
 * On Log4j2 the console is off as well, because Spring Boot's Log4j2 configuration reads
 * the threshold that {@code logging.console.enabled=false} leads to, so the log is
 * written where the application sends it: to the file {@code logging.file.name} names, or
 * to the appenders of a Log4j2 configuration of its own. While both are missing, startup
 * says in one line on stderr that the log is lost.
 *
 * <p>
 * Each test launches the server as a process of its own on the classpath
 * {@link LoggingSystemClasspath} builds, calls a tool against an API address that refuses
 * the connection, which is a line GATool writes at WARN, and reads both streams. The
 * bounds on each wait and the forced end of the process are those of
 * {@link McpStdioLogTests}, whose server these tests start.
 */
class McpStdioLogOnLog4j2Tests {

	// The line GATool writes at the start, by the words that open it and by the part
	// that leaves room for a configuration of the application.
	private static final String LINE_OF_GATOOL = "GATool: the log of this stdio server is";

	private static final String ROOM_FOR_THE_APPLICATION = "unless the application's own Log4j2 configuration "
			+ "writes it";

	// The two lines as Boot's pattern for the log file writes them.
	private static final Pattern WARN_LINE = Pattern.compile(" WARN .*could not reach the GraphQL API");

	private static final Pattern STARTED_LINE = Pattern.compile(" INFO .*Started StdioServerApplication");

	// The same two lines as the pattern of the application's own configuration writes
	// them, which names the configuration at the start of each line.
	private static final Pattern OWN_WARN_LINE = Pattern.compile("(?m)^OWN WARN .*could not reach the GraphQL API");

	private static final Pattern OWN_STARTED_LINE = Pattern.compile("(?m)^OWN INFO .*Started StdioServerApplication");

	// A Log4j2 configuration of the application that sends the log to stderr.
	private static final String LOG_TO_STDERR = """
			<?xml version="1.0" encoding="UTF-8"?>
			<Configuration>
			    <Appenders>
			        <Console name="Stderr" target="SYSTEM_ERR">
			            <PatternLayout pattern="OWN %p %c{1.} : %m%n"/>
			        </Console>
			    </Appenders>
			    <Loggers>
			        <Root level="INFO">
			            <AppenderRef ref="Stderr"/>
			        </Root>
			    </Loggers>
			</Configuration>
			""";

	// A configuration of the application whose root logger names an appender the file
	// leaves out, which Log4j2 reports through its status logger while it loads the
	// file.
	private static final String NAMES_A_MISSING_APPENDER = LOG_TO_STDERR.replace("<AppenderRef ref=\"Stderr\"/>",
			"<AppenderRef ref=\"Stderr\"/><AppenderRef ref=\"Missing\"/>");

	private static final String MISSING_APPENDER = "Unable to locate appender \"Missing\"";

	// A configuration of the application that sends each line at WARN and above to a
	// collector at an address that refuses the connection, which Log4j2 reports
	// through its status logger each time it writes a line.
	private static final String LOG_TO_A_COLLECTOR = """
			<?xml version="1.0" encoding="UTF-8"?>
			<Configuration>
			    <Appenders>
			        <Http name="Collector" url="http://127.0.0.1:1/log" connectTimeoutMillis="1000">
			            <PatternLayout pattern="OWN %p %c{1.} : %m%n"/>
			        </Http>
			    </Appenders>
			    <Loggers>
			        <Root level="WARN">
			            <AppenderRef ref="Collector"/>
			        </Root>
			    </Loggers>
			</Configuration>
			""";

	private static final String COLLECTOR_REFUSED = "Unable to send HTTP in appender [Collector]";

	@Test
	void log_gaToolDefaults_shouldLeaveStdoutToJsonRpcAndSayOnStderrThatTheLogIsLost() throws Exception {
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(), List.of())) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			// The line of GATool is everything stderr carries, so both streams stayed
			// free of the log, and Log4j2's status logger stayed silent through the
			// start.
			assertThat(streams.stderr().lines()).singleElement()
				.asString()
				.startsWith(LINE_OF_GATOOL)
				.contains(ROOM_FOR_THE_APPLICATION);
		}
	}

	@Test
	void log_logFileNamed_shouldWriteTheLogThereAndLeaveStderrEmpty(@TempDir Path directory) throws Exception {
		Path log = directory.resolve("gatool-stdio.log");
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(), List.of("--logging.file.name=" + log))) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).isEmpty();
			assertThat(Files.readString(log, StandardCharsets.UTF_8)).containsPattern(STARTED_LINE)
				.containsPattern(WARN_LINE);
		}
	}

	@Test
	void log_configurationNamedInLoggingConfig_shouldCarryItsLogToStderrAndKeepGAToolSilent(@TempDir Path directory)
			throws Exception {
		Path configuration = Files.writeString(directory.resolve("log-to-stderr.xml"), LOG_TO_STDERR);
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(),
				List.of("--logging.config=" + configuration))) {
			server.callTheTool();
			server.awaitOnStderr(OWN_WARN_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(OWN_STARTED_LINE)
				.containsPattern(OWN_WARN_LINE)
				.doesNotContain(LINE_OF_GATOOL);
		}
	}

	@Test
	void log_configurationFoundOnTheClasspath_shouldCarryItsLogToStderrBehindTheLineOfGATool(@TempDir Path directory)
			throws Exception {
		// Log4j2 finds a file of this name by itself, and GATool cannot tell that the
		// application brought one, so its line is written and leaves room for the file.
		Files.writeString(directory.resolve("log4j2.xml"), LOG_TO_STDERR);
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(directory), List.of())) {
			server.callTheTool();
			server.awaitOnStderr(OWN_WARN_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).startsWith(LINE_OF_GATOOL)
				.contains(ROOM_FOR_THE_APPLICATION)
				.containsPattern(OWN_STARTED_LINE)
				.containsPattern(OWN_WARN_LINE);
		}
	}

	@Test
	void log_consoleThresholdSet_shouldKeepTheConsoleOffAndLeaveStdoutToJsonRpc() throws Exception {
		// On Logback the property sets what stderr carries. On Log4j2 Boot hands it to
		// the console appender, which writes to stdout, and Boot leaves the OFF of
		// logging.console.enabled=false out once the threshold has a value.
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(),
				List.of("--logging.threshold.console=INFO"))) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr().lines()).singleElement().asString().startsWith(LINE_OF_GATOOL);
		}
	}

	@Test
	void log_logFileThatCannotBeCreated_shouldCarryWhatLog4j2ReportsToStderr(@TempDir Path directory) throws Exception {
		// The folder of the log file is a file, so Log4j2 cannot create the log in it,
		// and it reports that while Boot starts the log.
		Path file = Files.writeString(directory.resolve("a-file"), "A file, which cannot hold a log file.");
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(),
				List.of("--logging.file.name=" + file.resolve("gatool-stdio.log")))) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).contains("Unable to create file");
		}
	}

	@Test
	void log_configurationInLoggingConfigThatNamesAMissingAppender_shouldCarryTheReportToStderr(@TempDir Path directory)
			throws Exception {
		Path configuration = Files.writeString(directory.resolve("missing-appender.xml"), NAMES_A_MISSING_APPENDER);
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(),
				List.of("--logging.config=" + configuration))) {
			server.callTheTool();
			server.awaitOnStderr(OWN_WARN_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).contains(MISSING_APPENDER).containsPattern(OWN_WARN_LINE);
		}
	}

	@Test
	void log_configurationOnTheClasspathThatNamesAMissingAppender_shouldCarryTheReportToStderr(@TempDir Path directory)
			throws Exception {
		Files.writeString(directory.resolve("log4j2.xml"), NAMES_A_MISSING_APPENDER);
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(directory), List.of())) {
			server.callTheTool();
			server.awaitOnStderr(OWN_WARN_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).contains(MISSING_APPENDER).containsPattern(OWN_WARN_LINE);
		}
	}

	@Test
	void log_appenderThatFailsWhileTheServerRuns_shouldCarryWhatLog4j2ReportsToStderr(@TempDir Path directory)
			throws Exception {
		// The file loads without a report, so the first one is written once Boot has
		// started the log, and the call adds one between the two answers.
		Path configuration = Files.writeString(directory.resolve("log-to-a-collector.xml"), LOG_TO_A_COLLECTOR);
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(),
				List.of("--logging.config=" + configuration))) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).contains(COLLECTOR_REFUSED);
		}
	}

	@Test
	void log_stderrLogTurnedOff_shouldLeaveBothStreamsFreeOfIt() throws Exception {
		try (Server server = Server.start(LoggingSystemClasspath.onLog4j2(),
				List.of("--gatool.mcp.stdio.log-to-stderr=false"))) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).isEmpty();
		}
	}

}
