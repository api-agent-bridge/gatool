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
 * What a stdio server on {@code java.util.logging} writes to its two streams.
 *
 * <p>
 * Spring Boot's configuration for {@code java.util.logging} keeps the console handler of
 * the JDK and leaves {@code logging.console.enabled} unread, and that handler writes to
 * {@code System.err}. So the log of such a server reaches stderr at INFO and above,
 * whatever GATool's settings for the stderr log say, and stdout carries JSON-RPC alone.
 * GATool stays silent there, because the line that calls the log lost would be wrong.
 *
 * <p>
 * Each test launches the server as a process of its own on the classpath
 * {@link LoggingSystemClasspath} builds, calls a tool against an API address that refuses
 * the connection, which is a line GATool writes at WARNING, and reads both streams at the
 * levels Boot sets by default, where the server answers. The bounds on each wait and the
 * forced end of the process are those of {@link McpStdioLogTests}, whose server these
 * tests start.
 */
class McpStdioLogOnJavaUtilLoggingTests {

	// The words that open the line GATool writes at the start of a server on Log4j2.
	private static final String LINE_OF_GATOOL = "GATool: the log of this stdio server is";

	// The console handler writes a line in the format of the JDK or in Boot's, and
	// both name the level ahead of the message.
	private static final Pattern WARNING_LINE = Pattern.compile("WARNING.*could not reach the GraphQL API");

	private static final Pattern STARTED_LINE = Pattern.compile("INFO.*Started StdioServerApplication");

	// The levels below INFO by the names java.util.logging gives them, at the place
	// either format writes the level.
	private static final Pattern BELOW_INFO = Pattern.compile("(?m)(^| )(CONFIG|FINE|FINER|FINEST)(:| \\[)");

	@Test
	void log_gaToolDefaults_shouldCarryInfoAndAboveToStderrAndLeaveStdoutToJsonRpc() throws Exception {
		try (Server server = Server.start(LoggingSystemClasspath.onJavaUtilLogging(), List.of())) {
			server.callTheTool();
			server.awaitOnStderr(WARNING_LINE);
			// The main thread writes the started line after the transport thread can
			// already answer a call, so the wait keeps the assertion below off a race.
			server.awaitOnStderr(STARTED_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(STARTED_LINE)
				.containsPattern(WARNING_LINE)
				.doesNotContainPattern(BELOW_INFO)
				.doesNotContain(LINE_OF_GATOOL);
		}
	}

	@Test
	void log_stderrLogTurnedOff_shouldStillCarryTheLogToStderr() throws Exception {
		// The property turns off the appender GATool attaches on Logback. The console
		// handler belongs to the JDK, and Boot's configuration keeps it.
		try (Server server = Server.start(LoggingSystemClasspath.onJavaUtilLogging(),
				List.of("--gatool.mcp.stdio.log-to-stderr=false"))) {
			server.callTheTool();
			server.awaitOnStderr(WARNING_LINE);
			// The main thread writes the started line after the transport thread can
			// already answer a call, so the wait keeps the assertion below off a race.
			server.awaitOnStderr(STARTED_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(STARTED_LINE).containsPattern(WARNING_LINE);
		}
	}

	@Test
	void log_logFileNamed_shouldWriteTheLogThereAndToStderr(@TempDir Path directory) throws Exception {
		Path log = directory.resolve("gatool-stdio.log");
		try (Server server = Server.start(LoggingSystemClasspath.onJavaUtilLogging(),
				List.of("--logging.file.name=" + log))) {
			server.callTheTool();
			server.awaitOnStderr(WARNING_LINE);
			// The main thread writes the started line after the transport thread can
			// already answer a call, so the wait keeps the assertion below off a race.
			server.awaitOnStderr(STARTED_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(STARTED_LINE).containsPattern(WARNING_LINE);
			// The file handler of the JDK keeps ten files in rotation and numbers them,
			// so the log is in the file whose name ends in 0.
			Path first = directory.resolve("gatool-stdio.log.0");
			assertThat(Files.readString(first, StandardCharsets.UTF_8)).containsPattern(STARTED_LINE)
				.containsPattern(WARNING_LINE);
		}
	}

}
