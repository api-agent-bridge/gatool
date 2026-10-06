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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What Log4j2 reports about itself reaches stderr under stdio.
 *
 * <p>
 * Spring Boot registers a console listener with Log4j2's status logger when it starts the
 * log, and that listener writes to {@code System.out}. Each test stands in for Boot: it
 * begins the watch, registers a console listener the way Boot does, has the status logger
 * report, and reads the two streams. The tests of {@code tests/skeleton} start a server
 * on Log4j2 and read what Boot's own listener wrote.
 *
 * <p>
 * The status logger belongs to the Log4j API, which is on the classpath of these tests,
 * and the tests reach it through reflection, as the class under test does, because the
 * compiler warns about the annotations of the API and the build stops on a warning. The
 * status logger is one for the JVM, so each test removes the listeners it registered and
 * puts the streams back.
 */
class Log4j2StatusLogTests {

	private static final String REPORT = "the log file could not be created";

	private final ClassLoader classLoader = getClass().getClassLoader();

	private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

	private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

	private final PrintStream systemOut = System.out;

	private final PrintStream systemErr = System.err;

	private final List<Object> registered = new ArrayList<>();

	@BeforeEach
	void captureTheStreams() {
		System.setOut(new PrintStream(this.stdout, true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(this.stderr, true, StandardCharsets.UTF_8));
	}

	@AfterEach
	void removeTheListenersAndPutTheStreamsBack() throws Exception {
		Log4j2StatusLog.sendToStderr(this.classLoader);
		for (Object listener : this.registered) {
			statusLogger().getClass().getMethod("removeListener", statusListener()).invoke(statusLogger(), listener);
		}
		System.setOut(this.systemOut);
		System.setErr(this.systemErr);
	}

	@Test
	void watch_reportWhileTheLogStarts_shouldReachStderrAndLeaveStdoutEmpty() throws Exception {
		Log4j2StatusLog.watch(this.classLoader);
		registerAConsoleListenerTheWayBootDoes();

		report(REPORT);

		assertThat(written(this.stderr)).contains(REPORT);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void sendToStderr_startWithoutAReport_shouldSendALaterReportToStderr() throws Exception {
		Log4j2StatusLog.watch(this.classLoader);
		registerAConsoleListenerTheWayBootDoes();

		Log4j2StatusLog.sendToStderr(this.classLoader);
		report(REPORT);

		assertThat(written(this.stderr)).contains(REPORT);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void sendToStderr_shouldEndTheWatch() throws Exception {
		Log4j2StatusLog.watch(this.classLoader);
		Object ofBoot = registerAConsoleListenerTheWayBootDoes();

		Log4j2StatusLog.sendToStderr(this.classLoader);

		// The listener Boot registered is the one that is left, so a report that finds
		// it removed at the end of the application reaches Log4j2's own fallback.
		assertThat(listeners()).containsExactly(ofBoot);
	}

	@Test
	void watch_secondStartInTheSameJvm_shouldEndTheWatchOfTheFirst() throws Exception {
		Log4j2StatusLog.watch(this.classLoader);

		Log4j2StatusLog.watch(this.classLoader);

		assertThat(listeners()).filteredOn((listener) -> Proxy.isProxyClass(listener.getClass())).hasSize(1);
	}

	@Test
	void watch_listenerTheApplicationRegisteredAhead_shouldKeepTheStreamItWritesTo() throws Exception {
		ByteArrayOutputStream ofTheApplication = new ByteArrayOutputStream();
		registerAConsoleListener(new PrintStream(ofTheApplication, true, StandardCharsets.UTF_8));
		Log4j2StatusLog.watch(this.classLoader);
		registerAConsoleListenerTheWayBootDoes();

		report(REPORT);
		Log4j2StatusLog.sendToStderr(this.classLoader);
		report(REPORT);

		// The listener of the application and Boot's each wrote both reports, the first
		// to its own stream and the second to stderr.
		assertThat(written(ofTheApplication).lines().filter((line) -> line.contains(REPORT))).hasSize(2);
		assertThat(written(this.stderr).lines().filter((line) -> line.contains(REPORT))).hasSize(2);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void watchAndSendToStderr_classpathWithoutTheLog4jApi_shouldLeaveTheStartAlone() {
		ClassLoader withoutTheLog4jApi = new ClassLoader(this.classLoader) {

			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (name.startsWith("org.apache.logging.log4j.")) {
					throw new ClassNotFoundException(name);
				}
				return super.loadClass(name, resolve);
			}
		};

		assertThatCode(() -> {
			Log4j2StatusLog.watch(withoutTheLog4jApi);
			Log4j2StatusLog.sendToStderr(withoutTheLog4jApi);
		}).doesNotThrowAnyException();
		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	// Boot 4.1.1 registers new StatusConsoleListener(Level.WARN), and that constructor
	// takes System.out as it is at that moment, which is the stream these tests
	// capture.
	private Object registerAConsoleListenerTheWayBootDoes() throws Exception {
		Object listener = consoleListener().getConstructor(level()).newInstance(warn());
		return register(listener);
	}

	private Object registerAConsoleListener(PrintStream stream) throws Exception {
		Object listener = consoleListener().getConstructor(level(), PrintStream.class).newInstance(warn(), stream);
		return register(listener);
	}

	private Object register(Object listener) throws Exception {
		statusLogger().getClass().getMethod("registerListener", statusListener()).invoke(statusLogger(), listener);
		this.registered.add(listener);
		return listener;
	}

	private void report(String text) throws Exception {
		statusLogger().getClass().getMethod("error", String.class).invoke(statusLogger(), text);
	}

	private List<Object> listeners() throws Exception {
		List<Object> listeners = new ArrayList<>();
		Object registeredListeners = statusLogger().getClass().getMethod("getListeners").invoke(statusLogger());
		((Iterable<?>) registeredListeners).forEach(listeners::add);
		return listeners;
	}

	private Object statusLogger() throws Exception {
		return Class.forName("org.apache.logging.log4j.status.StatusLogger", true, this.classLoader)
			.getMethod("getLogger")
			.invoke(null);
	}

	private Class<?> statusListener() throws Exception {
		return Class.forName("org.apache.logging.log4j.status.StatusListener", true, this.classLoader);
	}

	private Class<?> consoleListener() throws Exception {
		return Class.forName("org.apache.logging.log4j.status.StatusConsoleListener", true, this.classLoader);
	}

	private Class<?> level() throws Exception {
		return Class.forName("org.apache.logging.log4j.Level", true, this.classLoader);
	}

	private Object warn() throws Exception {
		return level().getField("WARN").get(null);
	}

	private static String written(ByteArrayOutputStream stream) {
		return stream.toString(StandardCharsets.UTF_8);
	}

}
