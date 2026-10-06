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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches an application in a JVM of its own and captures what it wrote and how it
 * exited.
 *
 * <p>
 * Three tests need the whole process: the stdio server, whose stdout is the JSON-RPC
 * channel; the WebFlux host, which runs on a classpath the test JVM cannot carry; and the
 * host with its own MCP server, which runs on the in-process starter's classpath. Each
 * one closes the child's stdin, reads both streams to their end on threads of their own,
 * and gives up after the timeout. A process that outlives the timeout is stopped, and the
 * failure carries the end of what it wrote to both streams, which is the one account of
 * why it was still running.
 */
final class HostProcess {

	private static final long STREAM_END_MILLIS = 5_000;

	private HostProcess() {
	}

	/**
	 * Runs the main class on the classpath with the arguments, and waits for the process
	 * to exit.
	 * @param mainClass the class whose main method runs
	 * @param classpath the classpath of the child JVM
	 * @param arguments the program arguments
	 * @param timeout how long the process may run
	 * @return the exit code and both streams
	 * @throws Exception when the launch fails or the process outlives the timeout
	 */
	static Run run(Class<?> mainClass, String classpath, List<String> arguments, Duration timeout) throws Exception {
		List<String> command = new ArrayList<>(
				List.of(System.getProperty("java.home") + "/bin/java", "-cp", classpath, mainClass.getName()));
		command.addAll(arguments);
		Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
		process.getOutputStream().close();
		StreamReader stdout = new StreamReader(process.getInputStream());
		StreamReader stderr = new StreamReader(process.getErrorStream());
		stdout.start();
		stderr.start();
		if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
			process.destroyForcibly();
			// The streams end once the process is gone. The wait is bounded all the
			// same, because a child of the process can hold a stream open.
			stdout.join(STREAM_END_MILLIS);
			stderr.join(STREAM_END_MILLIS);
			throw new AssertionError(mainClass.getSimpleName() + " was still running after " + timeout
					+ "; stdout tail: " + Run.tail(stdout.text()) + " stderr tail: " + Run.tail(stderr.text()));
		}
		stdout.join();
		stderr.join();
		return new Run(process.exitValue(), stdout.text(), stderr.text());
	}

	/**
	 * What a launched application left behind.
	 *
	 * @param exitCode the exit code
	 * @param stdout everything written to stdout
	 * @param stderr everything written to stderr
	 */
	record Run(int exitCode, String stdout, String stderr) {

		/**
		 * The first stdout line opening with the prefix, or a message carrying the tail
		 * of both streams so that a failed assertion says what the process did.
		 */
		String line(String prefix) {
			return this.stdout.lines()
				.filter((candidate) -> candidate.startsWith(prefix))
				.findFirst()
				.orElse("no line opening with " + prefix + "; stdout tail: " + tail(this.stdout) + " stderr tail: "
						+ tail(this.stderr));
		}

		private static String tail(String text) {
			return text.substring(Math.max(0, text.length() - 3000));
		}
	}

	private static final class StreamReader extends Thread {

		private final InputStream stream;

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		StreamReader(InputStream stream) {
			this.stream = stream;
		}

		// Read in pieces, so that what the process wrote so far can be asked for while
		// the stream is still open.
		@Override
		public void run() {
			byte[] piece = new byte[8192];
			try {
				int count;
				while ((count = this.stream.read(piece)) != -1) {
					synchronized (this.bytes) {
						this.bytes.write(piece, 0, count);
					}
				}
			}
			catch (IOException ex) {
				byte[] note = (" [could not read on: " + ex + "]").getBytes(StandardCharsets.UTF_8);
				synchronized (this.bytes) {
					this.bytes.write(note, 0, note.length);
				}
			}
		}

		String text() {
			synchronized (this.bytes) {
				return this.bytes.toString(StandardCharsets.UTF_8);
			}
		}

	}

}
