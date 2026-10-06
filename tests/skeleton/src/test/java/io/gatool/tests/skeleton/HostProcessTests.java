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

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What the launcher of the host tests reports about a process.
 *
 * <p>
 * A host that outlives its timeout fails its test with the end of what it wrote to both
 * streams, because the name of the class and the timeout alone cannot explain the run.
 */
class HostProcessTests {

	@Test
	void run_processThatOutlivesTheTimeout_shouldStopItAndCarryWhatItWrote() {
		long startedAt = System.nanoTime();

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> HostProcess.run(Waiting.class, System.getProperty("java.class.path"), List.of(),
					Duration.ofSeconds(3)))
			.withMessageContaining("Waiting was still running after PT3S")
			.withMessageContaining("stdout tail: the host reached its wait")
			.withMessageContaining("stderr tail: and wrote this to stderr");

		assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(30));
	}

	@Test
	void run_processThatExits_shouldReturnItsExitCodeAndBothStreams() throws Exception {
		HostProcess.Run run = HostProcess.run(Exiting.class, System.getProperty("java.class.path"), List.of("seven"),
				Duration.ofSeconds(30));

		assertThat(run.exitCode()).isEqualTo(7);
		assertThat(run.stdout()).isEqualTo("argument seven" + System.lineSeparator());
		assertThat(run.stderr()).isEqualTo("on stderr" + System.lineSeparator());
	}

	/**
	 * Writes a line to each stream and then waits longer than the test allows.
	 *
	 * <p>
	 * The class and its main method are public because Java 21 starts a program through a
	 * public main method alone; Java 25 accepts a package-private one.
	 */
	public static final class Waiting {

		private Waiting() {
		}

		public static void main(String[] arguments) throws InterruptedException {
			System.out.println("the host reached its wait");
			System.err.println("and wrote this to stderr");
			Thread.sleep(Duration.ofMinutes(5).toMillis());
		}

	}

	/**
	 * Writes a line to each stream and exits with a code of its own.
	 */
	public static final class Exiting {

		private Exiting() {
		}

		public static void main(String[] arguments) {
			System.out.println("argument " + arguments[0]);
			System.err.println("on stderr");
			System.exit(7);
		}

	}

}
