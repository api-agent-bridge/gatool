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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A running stdio server writes its log to stderr.
 *
 * <p>
 * {@code GAToolEnvironmentPostProcessor} turns console logging off under stdio, because
 * Spring Boot writes the log to stdout, the JSON-RPC channel. Without another place for
 * the log to go, every line a running server writes is lost unless the operator sets
 * {@code logging.file.name}: a tool call that cannot reach the API leaves both streams
 * without the WARN line that names the cause. MCP leaves stderr to a server for its
 * logging, so the log goes there while stdout keeps carrying JSON-RPC alone. stderr
 * carries WARN and above until {@code logging.threshold.console} says otherwise, and the
 * log is written by a thread of its own, so a host that leaves stderr unread loses lines
 * while the server keeps answering, and a line of the log tells a host that reads again
 * how many were dropped.
 *
 * <p>
 * Each test launches the server as a process of its own, calls a tool against an API
 * address that refuses the connection, which is a line GATool writes at WARN and again at
 * DEBUG with the stack, and reads both streams.
 */
class McpStdioLogTests {

	private static final String TOOL_CALL_RUNNER = "io.gatool.boot.internal.execution.ToolCallRunner";

	// The line as Boot's console pattern writes it: the level, then the message.
	private static final Pattern WARN_LINE = Pattern.compile(" WARN .*could not reach the GraphQL API");

	private static final Pattern DEBUG_LINE = Pattern.compile("DEBUG .*could not reach the GraphQL API");

	// Two lines every start writes at INFO: Boot's own, and GATool's list of tools.
	private static final Pattern STARTED_LINE = Pattern.compile(" INFO .*Started StdioServerApplication");

	private static final String TOOL_LIST = "GATool serves 1 MCP tool";

	// The line that says how many lines were dropped, as Boot's console pattern writes
	// it.
	private static final Pattern NOTICE = Pattern
		.compile(" WARN .*LogbackStderrLog +: GATool dropped (\\d+) log lines? while stderr was not being read");

	// The date that opens a line of the log in Boot's patterns for the console and for
	// the file. The lines of a stack trace follow their line of the log without one.
	private static final Pattern LINE_OF_THE_LOG = Pattern.compile("(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:");

	// More than a pipe and the queue behind it hold: a pipe holds 64 KB, and the lines
	// the queue takes at these levels come to about 250 KB.
	private static final long MORE_THAN_STDERR_HOLDS = 1_000_000;

	private static final Duration BOUND = Duration.ofSeconds(30);

	// The wait for jcmd's own answer, once the call has already gone unanswered
	// for the whole of BOUND.
	private static final Duration JCMD_BOUND = Duration.ofSeconds(10);

	// How many of the log file's last lines the timeout message carries.
	private static final int TAIL_OF_THE_LOG_FILE = 150;

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
			+ "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"log-probe\",\"version\":\"1\"}}}";

	private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

	private static final int FIRST_CALL = 2;

	@Test
	void log_gaToolDefaults_shouldCarryWarnAndAboveToStderrAndLeaveStdoutToJsonRpc() throws Exception {
		try (Server server = Server.start(List.of())) {
			server.callTheTool();
			server.awaitOnStderr(WARN_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(WARN_LINE);
			// The lines of a normal start are written at INFO, below what stderr carries
			// by default, and the root logger stays at Boot's INFO, so the DEBUG line
			// with the stack stays out as well.
			assertThat(streams.stderr()).doesNotContainPattern(STARTED_LINE)
				.doesNotContain(TOOL_LIST)
				.doesNotContain(" INFO ")
				.doesNotContainPattern(DEBUG_LINE);
		}
	}

	@Test
	void log_consoleThresholdSetToInfo_shouldCarryTheLinesOfTheStart() throws Exception {
		try (Server server = Server.start(List.of("--logging.threshold.console=INFO"))) {
			server.callTheTool();
			server.awaitOnStderr(WARN_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(STARTED_LINE).contains(TOOL_LIST);
		}
	}

	@Test
	void log_loggerAndConsoleThresholdSetToDebug_shouldCarryTheDebugLineToStderr() throws Exception {
		try (Server server = Server
			.start(List.of("--logging.level." + TOOL_CALL_RUNNER + "=DEBUG", "--logging.threshold.console=DEBUG"))) {
			server.callTheTool();
			server.awaitOnStderr(DEBUG_LINE);

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).containsPattern(DEBUG_LINE);
		}
	}

	@Test
	void log_stderrLogTurnedOff_shouldLeaveBothStreamsFreeOfIt() throws Exception {
		try (Server server = Server.start(List.of("--gatool.mcp.stdio.log-to-stderr=false"))) {
			server.callTheTool();

			Streams streams = server.stop();

			assertThat(streams.stdout()).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			assertThat(streams.stderr()).isEmpty();
		}
	}

	@Test
	void log_consoleLoggingSetByTheApplication_shouldLeaveStderrEmpty() throws Exception {
		try (Server server = Server.start(List.of("--logging.console.enabled=true"))) {
			server.callTheTool();

			Streams streams = server.stop();

			// The application chose the console, which writes to stdout, so GATool leaves
			// the line in that one place.
			assertThat(String.join("\n", streams.stdout())).containsPattern(WARN_LINE);
			assertThat(streams.stderr()).isEmpty();
		}
	}

	@Test
	void log_hostThatLeavesStderrUnread_shouldStillAnswerTheCall(@TempDir Path directory) throws Exception {
		// The log file receives every line, so its size says how much the server wrote,
		// and the levels are lowered so that the start alone writes more than a pipe
		// holds.
		Path everyLine = directory.resolve("every-line.log");
		try (Server server = Server.startAndLeaveStderrUnread(List.of("--logging.level.root=DEBUG",
				"--logging.threshold.console=DEBUG", "--logging.file.name=" + everyLine))) {
			server.logsTo(everyLine);

			server.callTheTool();

			byte[] waitingInThePipe = server.stopAndReadWhatThePipeHeld();
			assertThat(server.stdout()).anySatisfy((line) -> assertThat(line).contains("\"id\":2"))
				.allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
			// The thread that waits on the pipe is a daemon, and the stop of the log
			// gives up on it after a second, so the server ends when its host asks it to.
			assertThat(server.endedWhenAsked()).isTrue();
			// A platform whose pipe held everything the server wrote cannot show a full
			// pipe, so the two assertions above say less there than they say here.
			assumeTrue(Files.size(everyLine) > waitingInThePipe.length,
					() -> "the pipe held all " + waitingInThePipe.length + " bytes the server wrote to stderr");
		}
	}

	@Test
	void log_hostThatReadsStderrAgain_shouldFindTheCountOfTheDroppedLines(@TempDir Path directory) throws Exception {
		// The log file receives every line, so it says how many lines the server wrote,
		// and the levels are lowered so that the calls write more than the pipe and the
		// queue hold.
		Path everyLine = directory.resolve("every-line.log");
		try (Server server = Server.startAndLeaveStderrUnread(List.of("--logging.level.root=DEBUG",
				"--logging.threshold.console=DEBUG", "--logging.file.name=" + everyLine))) {
			server.logsTo(everyLine);
			server.callTheTool();
			for (int call = 0; call < 500 && Files.size(everyLine) < MORE_THAN_STDERR_HOLDS; call++) {
				server.callTheToolAgain();
			}

			// The host reads again. Once it has read what the pipe and the queue held,
			// the lines of one more call find room in the queue, and the notice is
			// written ahead of the first of them.
			server.readStderr();
			server.awaitTheEndOfTheLog(everyLine);
			server.callTheToolAgain();
			server.awaitTheEndOfTheLog(everyLine);

			String stderr = server.stderr();
			List<Long> dropped = NOTICE.matcher(stderr)
				.results()
				.map((notice) -> Long.valueOf(notice.group(1)))
				.toList();
			long written = linesOfTheLog(Files.readString(everyLine));
			long arrived = linesOfTheLog(stderr) - dropped.size();
			// A platform whose pipe and queue held everything the server wrote cannot
			// show a dropped line.
			assumeTrue(written > arrived, () -> "stderr held all " + written + " lines the server wrote");
			assertThat(dropped.stream().mapToLong(Long::longValue).sum())
				.as("the lines the notices count, which hold %s, where the server wrote %d lines and %d arrived",
						dropped, written, arrived)
				.isEqualTo(written - arrived);
			assertThat(server.stdout()).allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
		}
	}

	private static long linesOfTheLog(String log) {
		return LINE_OF_THE_LOG.matcher(log).results().count();
	}

	/**
	 * What the server wrote while it answered one tool call.
	 *
	 * @param stdout the lines on stdout, up to the answer to the call
	 * @param stderr everything written to stderr until the server stopped
	 */
	record Streams(List<String> stdout, String stderr) {
	}

	/**
	 * The server as a process of its own, spoken to the way a host speaks to it. The
	 * tests of the other logging systems start it on a classpath of their own.
	 */
	static final class Server implements AutoCloseable {

		private final Process process;

		private final List<String> stdout = new CopyOnWriteArrayList<>();

		private final StderrReader stderr;

		private final Writer toServer;

		private final BufferedReader fromServer;

		private int lastCall = FIRST_CALL;

		private boolean endedWhenAsked;

		// The log file the two tests that pass --logging.file.name hand to the server
		// through logsTo(Path), so a stalled call can show its tail. Every other test
		// leaves it null.
		private Path logFile;

		private Server(Process process, boolean readStderr) {
			this.process = process;
			this.toServer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
			this.fromServer = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
			this.stderr = new StderrReader(process.getErrorStream());
			if (readStderr) {
				this.stderr.start();
			}
		}

		static Server start(List<String> extraArguments) throws IOException {
			return new Server(launch(extraArguments), true);
		}

		static Server start(String classpath, List<String> extraArguments) throws IOException {
			return new Server(launch(classpath, extraArguments), true);
		}

		// The host of this server starts it with stderr as a pipe and leaves the pipe
		// unread for as long as the server runs.
		static Server startAndLeaveStderrUnread(List<String> extraArguments) throws IOException {
			return new Server(launch(extraArguments), false);
		}

		// Names the file the server was started with, so a stalled call can show its
		// tail beside the thread dump.
		void logsTo(Path logFile) {
			this.logFile = logFile;
		}

		private static Process launch(List<String> extraArguments) throws IOException {
			return launch(System.getProperty("java.class.path"), extraArguments);
		}

		private static Process launch(String classpath, List<String> extraArguments) throws IOException {
			List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-cp",
					classpath, StdioServerApplication.class.getName(), "--spring.ai.mcp.server.stdio=true",
					"--spring.application.name=gatool-skeleton-tests", "--gatool.api.url=http://127.0.0.1:1/graphql",
					"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls"));
			command.addAll(extraArguments);
			return new ProcessBuilder(command).redirectErrorStream(false).start();
		}

		List<String> stdout() {
			return this.stdout;
		}

		// Whether the server exited after the request to stop, ahead of the forced end
		// that follows twenty seconds later. A platform that knows the forced end alone
		// answers true.
		boolean endedWhenAsked() {
			return this.endedWhenAsked;
		}

		// Initializes and calls the tool, and fails where the answer to the call is
		// still missing once the bound has passed, which is what a server that waits on
		// its own log looks like from outside.
		void callTheTool() throws Exception {
			withinTheBound(this::converse);
		}

		// One more call in the conversation that callTheTool opened.
		void callTheToolAgain() throws Exception {
			int id = ++this.lastCall;
			withinTheBound(() -> {
				try {
					send(call(id));
					readUntilTheAnswer(id);
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			});
		}

		private void withinTheBound(Runnable messages) throws Exception {
			CompletableFuture<Void> conversation = CompletableFuture.runAsync(messages);
			try {
				conversation.get(BOUND.toSeconds(), TimeUnit.SECONDS);
			}
			catch (TimeoutException ex) {
				throw new AssertionError("The server left the call unanswered for " + BOUND.toSeconds()
						+ " seconds. It wrote these lines to stdout: " + this.stdout + System.lineSeparator()
						+ "The threads of the server when the bound passed:" + System.lineSeparator()
						+ threadDumpOfTheServer() + System.lineSeparator() + "The last lines of the server's log file:"
						+ System.lineSeparator() + tailOfTheLogFile());
			}
			catch (ExecutionException ex) {
				throw new AssertionError("The conversation with the server broke off", ex.getCause());
			}
		}

		// A server seen from outside shows only the missing answer once it has
		// stalled, so the assertion above adds a thread dump that says what the
		// server's threads were doing.
		//
		// jcmd's own output is written to a file instead of read from a pipe, so a
		// dump larger than a pipe holds cannot block jcmd on its own write while
		// this method waits on it. jcmd sits next to java in the JDK's bin
		// directory on a Unix-style layout, the same assumption launch() above
		// makes for java itself.
		private String threadDumpOfTheServer() {
			String jcmd = System.getProperty("java.home") + "/bin/jcmd";
			Path output;
			try {
				output = Files.createTempFile("mcp-stdio-log-test-thread-dump-", ".txt");
			}
			catch (IOException ex) {
				return "the file for jcmd's output could not be created: " + ex.getMessage();
			}
			Process dump;
			try {
				dump = new ProcessBuilder(jcmd, Long.toString(this.process.pid()), "Thread.print")
					.redirectErrorStream(true)
					.redirectOutput(output.toFile())
					.start();
			}
			catch (IOException ex) {
				deleteQuietly(output);
				return "jcmd could not be started: " + ex.getMessage();
			}
			try {
				if (!dump.waitFor(JCMD_BOUND.toSeconds(), TimeUnit.SECONDS)) {
					dump.destroyForcibly();
					dump.waitFor();
				}
				String text = Files.readString(output);
				return text.isBlank() ? "jcmd's output was empty" : text;
			}
			catch (IOException ex) {
				return "jcmd's output could not be read: " + ex.getMessage();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return "the wait for jcmd was interrupted: " + ex.getMessage();
			}
			finally {
				if (dump.isAlive()) {
					dump.destroyForcibly();
				}
				deleteQuietly(output);
			}
		}

		// The log file holds what the SDK wrote at DEBUG, which a host that leaves
		// stderr unread does not see, so the tail joins the thread dump in the
		// assertion message. A test that starts the server without a log file gets a
		// sentence in its place, and a file that cannot be read gets one too, so a
		// missing file does not hide the thread dump above it.
		private String tailOfTheLogFile() {
			if (this.logFile == null) {
				return "the server was started without a log file";
			}
			List<String> lines;
			try {
				lines = Files.readAllLines(this.logFile);
			}
			catch (IOException ex) {
				return "the log file could not be read: " + ex.getMessage();
			}
			int from = Math.max(0, lines.size() - TAIL_OF_THE_LOG_FILE);
			return String.join(System.lineSeparator(), lines.subList(from, lines.size()));
		}

		// Removes the temporary file that held jcmd's output. The file sits under
		// the JVM's own temporary directory, so a rare failure of the delete leaves
		// only that one file behind.
		private static void deleteQuietly(Path file) {
			try {
				Files.deleteIfExists(file);
			}
			catch (IOException ex) {
				// The file is cleaned up on a best effort basis; its content already
				// reached the assertion message before this runs.
			}
		}

		private void converse() {
			try {
				send(INITIALIZE);
				readUntilTheAnswer(1);
				send(INITIALIZED);
				send(call(FIRST_CALL));
				readUntilTheAnswer(FIRST_CALL);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		private static String call(int id) {
			return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":"
					+ "{\"name\":\"topRatedMovies\",\"arguments\":{}}}";
		}

		private void send(String message) throws IOException {
			this.toServer.write(message + System.lineSeparator());
			this.toServer.flush();
		}

		// The server answers and then waits for more input, so the reader stops at the
		// answer it was sent for.
		private void readUntilTheAnswer(int id) throws IOException {
			String line;
			while ((line = this.fromServer.readLine()) != null) {
				this.stdout.add(line);
				if (line.startsWith("{\"jsonrpc\"") && line.contains("\"id\":" + id)) {
					return;
				}
			}
			throw new IOException("stdout ended ahead of the answer to request " + id);
		}

		// The log is written by a thread of its own, so a line may reach stderr a moment
		// after the answer it belongs to reached stdout.
		void awaitOnStderr(Pattern line) throws InterruptedException {
			long deadline = System.nanoTime() + BOUND.toNanos();
			while (!line.matcher(this.stderr.text()).find()) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError("Waited " + BOUND.toSeconds() + " seconds for " + line
							+ " and stderr carried: " + this.stderr.text());
				}
				TimeUnit.MILLISECONDS.sleep(20);
			}
		}

		// The host reads stderr from here on, which it left unread since the start.
		void readStderr() {
			this.stderr.start();
		}

		String stderr() {
			return this.stderr.text();
		}

		// Waits until the server has written what it had to write: the log file, which
		// receives every line, and stderr have both stayed as they are for a second.
		void awaitTheEndOfTheLog(Path everyLine) throws IOException, InterruptedException {
			long deadline = System.nanoTime() + BOUND.toNanos();
			long file = -1;
			int stream = -1;
			int unchanged = 0;
			while (unchanged < 10) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError(
							"The server kept writing to its log for " + BOUND.toSeconds() + " seconds");
				}
				TimeUnit.MILLISECONDS.sleep(100);
				long fileNow = Files.size(everyLine);
				int streamNow = this.stderr.length();
				unchanged = (fileNow == file && streamNow == stream) ? unchanged + 1 : 0;
				file = fileNow;
				stream = streamNow;
			}
		}

		// Stops the server the way a host does and reads stderr to its end.
		Streams stop() throws InterruptedException {
			end();
			this.stderr.join(TimeUnit.SECONDS.toMillis(20));
			return new Streams(List.copyOf(this.stdout), this.stderr.text());
		}

		// What the pipe held when the server stopped, which the process keeps for its
		// parent once it has exited.
		byte[] stopAndReadWhatThePipeHeld() throws IOException, InterruptedException {
			end();
			return this.process.getErrorStream().readAllBytes();
		}

		// The request goes through the handle, which leaves the streams of the process
		// open, where Process.destroy() closes them ahead of the lines the server writes
		// while it stops.
		private void end() throws InterruptedException {
			this.process.toHandle().destroy();
			this.endedWhenAsked = this.process.waitFor(20, TimeUnit.SECONDS);
			if (!this.endedWhenAsked) {
				this.process.destroyForcibly();
				this.process.waitFor(20, TimeUnit.SECONDS);
			}
		}

		// A test that failed ahead of its stop leaves the server running, so the
		// process is ended here, where the test has read everything it will read.
		@Override
		public void close() {
			this.process.destroyForcibly();
		}

	}

	// stderr is read on a thread of its own and piece by piece, so a test reads what
	// has arrived while the server is still running.
	private static final class StderrReader extends Thread {

		private final InputStream stream;

		private final ByteArrayOutputStream read = new ByteArrayOutputStream();

		StderrReader(InputStream stream) {
			this.stream = stream;
			setDaemon(true);
		}

		@Override
		public void run() {
			byte[] piece = new byte[8192];
			try {
				int length;
				while ((length = this.stream.read(piece)) != -1) {
					synchronized (this.read) {
						this.read.write(piece, 0, length);
					}
				}
			}
			catch (IOException ex) {
				// The stream closes with the process, and what was read until then
				// stands.
			}
		}

		String text() {
			synchronized (this.read) {
				return this.read.toString(StandardCharsets.UTF_8);
			}
		}

		int length() {
			synchronized (this.read) {
				return this.read.size();
			}
		}

	}

}
