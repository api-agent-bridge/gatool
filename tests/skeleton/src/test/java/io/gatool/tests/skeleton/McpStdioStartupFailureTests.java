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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stdio server that stops at startup says why on stderr.
 *
 * <p>
 * {@code GAToolEnvironmentPostProcessor} turns console logging off under stdio, because
 * Spring Boot writes the log to stdout, the JSON-RPC channel. Boot reports a startup
 * failure through the log alone, so with the console off the host that launched the
 * process would read exit code 1 and two empty streams.
 * {@code McpStdioStartupFailureReporter} writes Boot's failure analysis to stderr, the
 * channel MCP leaves to a server for its logging, while stdout stays empty. The log of a
 * stdio server goes to stderr as well, through a queue, so the reporter keeps writing the
 * analysis itself and Boot's copy of it stays out of the stderr log. The analysis is
 * written once, on a daemon thread the reporter waits for at most two seconds. The second
 * test turns the stderr log off and reads the same analysis. The third and fourth tests
 * hold what the two logging properties do beside it: a log file receives the analysis,
 * and console logging turned back on sends it down stdout. The fifth test starts the
 * server at DEBUG beside a host that leaves stderr unread: beside a full pipe past that
 * wait, the report is lost, and exit code 1 is what remains.
 */
class McpStdioStartupFailureTests {

	@Test
	void startup_brokenOperationFilesOverStdio_shouldWriteTheFailureAnalysisToStderrAlone() throws Exception {
		HostProcess.Run run = run(List.of());

		// The reporter and the log reach stderr in either order, so the assertions read
		// what the stream carries and leave the order alone. Boot's copy of the analysis
		// would arrive as a log line naming Boot's reporter.
		assertThat(run.exitCode()).isEqualTo(1);
		assertThat(run.stdout()).isEmpty();
		assertThat(run.stderr()).containsOnlyOnce("APPLICATION FAILED TO START")
			.contains("TwoOperations.graphql")
			.doesNotContain("LoggingFailureAnalysisReporter")
			.doesNotContain(" INFO ");
	}

	@Test
	void startup_brokenOperationFilesWithTheStderrLogOff_shouldWriteTheAnalysisToStderrOnce() throws Exception {
		HostProcess.Run run = run(List.of("--gatool.mcp.stdio.log-to-stderr=false"));

		// With the log off, the reporter's analysis is everything stderr carries.
		assertThat(run.exitCode()).isEqualTo(1);
		assertThat(run.stdout()).isEmpty();
		assertThat(run.stderr()).containsOnlyOnce("APPLICATION FAILED TO START")
			.contains("TwoOperations.graphql")
			.doesNotContain(" WARN ");
	}

	@Test
	void startup_brokenOperationFilesWithALogFile_shouldWriteTheFailureAnalysisThere(@TempDir Path directory)
			throws Exception {
		Path log = directory.resolve("gatool-stdio.log");

		HostProcess.Run run = run(List.of("--logging.file.name=" + log));

		// The file keeps Boot's copy of the analysis, which stays out of stderr alone.
		assertThat(run.exitCode()).isEqualTo(1);
		assertThat(Files.readString(log, StandardCharsets.UTF_8)).contains("APPLICATION FAILED TO START")
			.contains("TwoOperations.graphql");
		assertThat(run.stderr()).containsOnlyOnce("APPLICATION FAILED TO START");
	}

	@Test
	void startup_brokenOperationFilesWithConsoleLoggingOn_shouldWriteTheFailureToStdout() throws Exception {
		HostProcess.Run run = run(List.of("--logging.console.enabled=true"));

		// The console appender writes to stdout, the JSON-RPC channel, which is why the
		// default keeps it off. The stderr report steps back while an application
		// chose the console, so the analysis lands once.
		assertThat(run.exitCode()).isEqualTo(1);
		assertThat(run.stdout()).contains("APPLICATION FAILED TO START");
		assertThat(run.stderr()).isEmpty();
	}

	@Test
	void startup_brokenOperationFilesAtDebugBesideAnUnreadStderr_shouldExitWithinTheBound() throws Exception {
		// The reporter and the DEBUG log both write to stderr here, and the host leaves
		// the pipe unread, so a process that waited on the full pipe would keep running.
		// Neither stream is read while the process runs: stdout stays empty on a start
		// that fails, and reading stderr here would drain the pipe the test means to
		// leave full.
		List<String> arguments = new ArrayList<>(List.of("--spring.ai.mcp.server.stdio=true",
				"--spring.application.name=gatool-skeleton-tests", "--gatool.api.url=http://127.0.0.1:1/graphql",
				"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"--gatool.mcp.operations.locations=classpath:gatool/broken/", "--logging.level.root=DEBUG",
				"--logging.threshold.console=DEBUG"));
		List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-cp",
				System.getProperty("java.class.path"), StdioServerApplication.class.getName()));
		command.addAll(arguments);
		Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
		try {
			process.getOutputStream().close();
			// 15 seconds is more than three times the 4.4 seconds this case takes, so a
			// loaded machine has room. A process that waits on the full pipe stays alive
			// past this bound.
			boolean exited = process.waitFor(15, TimeUnit.SECONDS);
			assertThat(exited)
				.as("the process was still running 15 seconds after its start; the block measured it still running after 20")
				.isTrue();
			assertThat(process.exitValue()).isEqualTo(1);
		}
		finally {
			process.destroyForcibly();
			process.waitFor(10, TimeUnit.SECONDS);
		}
	}

	private static HostProcess.Run run(List<String> extraArguments) throws Exception {
		List<String> arguments = new ArrayList<>(List.of("--spring.ai.mcp.server.stdio=true",
				"--spring.application.name=gatool-skeleton-tests", "--gatool.api.url=http://127.0.0.1:1/graphql",
				"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"--gatool.mcp.operations.locations=classpath:gatool/broken/"));
		arguments.addAll(extraArguments);
		return HostProcess.run(StdioServerApplication.class, System.getProperty("java.class.path"), arguments,
				Duration.ofSeconds(90));
	}

}
