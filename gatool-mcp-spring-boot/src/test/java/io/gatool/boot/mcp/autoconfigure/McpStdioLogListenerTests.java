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
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The log of a running stdio server goes to stderr.
 *
 * <p>
 * Console logging is off under stdio, because Spring Boot writes the log to stdout, the
 * JSON-RPC channel. Without another place to go, a line written by a running server would
 * be missing from both streams. Each test starts an application the way
 * {@code SpringApplication} starts one, so the listeners named in
 * {@code META-INF/spring.factories} run in Boot's order, writes a line and reads what
 * reached the two streams.
 *
 * <p>
 * The log is written by a thread of its own, so that a host which leaves stderr unread
 * cannot stall the server. A test that reads stderr therefore writes one last line and
 * waits for it: one thread writes the queue in order, so the last line arrives behind
 * every line queued before it.
 *
 * <p>
 * A line that finds the queue full is dropped and counted, and the first line the queue
 * takes again is written behind a notice that holds the count. The tests of the notice
 * work out the count they expect from the lines that arrived, because how many lines the
 * queue takes while the host pauses depends on how far the thread that writes had come.
 * That thread takes one line out of the queue and then the rest, and a queue that fills
 * between the two steps finds room again within the same pause, which gives that pause
 * two notices. So a test that counts the notices of a pause first has the thread wait on
 * the stream with a line of its own, and sends the lines that fill the queue behind it.
 *
 * <p>
 * The appender is built on Logback. An application on Log4j2 has the console off and the
 * appender missing, so startup writes one line to stderr that says so, while the
 * application leaves the log file unset. Log4j2 is absent from the classpath of these
 * tests, so the application's class loader stands in for the classpath of such an
 * application: it hides Logback and holds the class Boot recognises Log4j2 by.
 *
 * <p>
 * The application's class loader hides this module's {@code logback-test.xml}, so Boot
 * loads its own defaults, as it does in an application that ships without a Logback file.
 * The streams, the system properties Boot sets for the log and the Logback configuration
 * are put back after each test, because the JVM is shared with the other tests of the
 * module. Every write made while the stream is held runs on a thread of its own under a
 * bound, so a write that waits fails its test, and the release after the test ends the
 * thread.
 */
@Timeout(60)
class McpStdioLogListenerTests {

	private static final String STDIO = "--spring.ai.mcp.server.stdio=true";

	private static final String LOG_OFF = "--gatool.mcp.stdio.log-to-stderr=false";

	private static final String MESSAGE = "the API answered 503";

	private static final String WARN_LINE = " WARN .*" + MESSAGE;

	private static final String LAST_LINE = "the last line of this test";

	private static final String BOOTS_REPORTER = "LoggingFailureAnalysisReporter";

	// The notice as Boot's console pattern writes it: the level, the logger, the message.
	private static final Pattern NOTICE = Pattern
		.compile(" WARN .*LogbackStderrLog +: GATool dropped (\\d+) log lines? while stderr was not being read");

	private static final String NOTICE_TEXT = "GATool dropped";

	private static final String LOGBACK = "ch.qos.logback.classic.LoggerContext";

	// The class Spring Boot looks for before it chooses Log4j2.
	private static final String LOG4J2 = "org.apache.logging.log4j.core.impl.Log4jContextFactory";

	private static final String LOG_IS_LOST = "the log of this stdio server is lost";

	private static final String BOOTS_LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";

	private static final Map<String, Object> STDIO_ALONE = Map.of("spring.ai.mcp.server.stdio", "true");

	private static final Log logger = LogFactory.getLog("io.gatool.test.stdio");

	private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

	private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

	private final HeldStream unreadPipe = new HeldStream();

	private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

	private final PrintStream systemOut = System.out;

	private final PrintStream systemErr = System.err;

	private final Properties systemProperties = (Properties) System.getProperties().clone();

	@BeforeEach
	void captureTheStreams() {
		System.setOut(new PrintStream(this.stdout, true, StandardCharsets.UTF_8));
		System.setErr(new PrintStream(this.stderr, true, StandardCharsets.UTF_8));
		// The line that says the log is lost is written once for the JVM, which the tests
		// share.
		McpStdioLogListener.forgetThatTheLogIsLostWasSaid();
	}

	@AfterEach
	void putTheStreamsAndTheLogBack() throws JoranException {
		// The release comes first, so a thread that waits on the held stream ends with
		// the test that started it.
		this.unreadPipe.release();
		this.contexts.forEach(ConfigurableApplicationContext::close);
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		context.reset();
		System.setOut(this.systemOut);
		System.setErr(this.systemErr);
		System.setProperties(this.systemProperties);
		new ContextInitializer(context).autoConfig();
	}

	@Test
	void log_stdioDefaults_shouldWriteAWarnLineToStderrAndLeaveStdoutEmpty() {
		run(STDIO);

		logger.warn(MESSAGE);

		assertThat(stderrOnceWritten()).containsPattern(WARN_LINE);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void log_stdioDefaults_shouldKeepAnInfoLineOut() {
		// stderr carries WARN and above until the application sets the console
		// threshold, so the lines of a normal start stay out of a host's error log.
		run(STDIO);

		logger.info(MESSAGE);

		assertThat(stderrOnceWritten()).doesNotContain(MESSAGE).doesNotContain(" INFO ");
	}

	@Test
	void log_consoleThresholdSetToInfo_shouldWriteTheInfoLine() {
		run(STDIO, "--logging.threshold.console=INFO");

		logger.info(MESSAGE);

		assertThat(stderrOnceWritten()).containsPattern(" INFO .*" + MESSAGE);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void log_loggerSetToDebugUnderTheDefaultThreshold_shouldKeepTheDebugLineOut() {
		run(STDIO, "--logging.level.io.gatool.test.stdio=DEBUG");

		logger.debug(MESSAGE);

		assertThat(stderrOnceWritten()).doesNotContain(MESSAGE);
	}

	@Test
	void log_loggerAndConsoleThresholdSetToDebug_shouldWriteTheDebugLine() {
		run(STDIO, "--logging.level.io.gatool.test.stdio=DEBUG", "--logging.threshold.console=DEBUG");

		logger.debug(MESSAGE);

		assertThat(stderrOnceWritten()).containsPattern("DEBUG .*" + MESSAGE);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void log_consoleThresholdWrittenAsYamlReadsOff_shouldWriteNothing() {
		// YAML reads an unquoted OFF as false, and Boot maps the word back for its own
		// appender, so stderr reads it the same way.
		run(STDIO, "--logging.threshold.console=false");

		logger.error(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isTrue();
		awaitAnEmptyQueue();
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_hostThatLeavesStderrUnread_shouldKeepTheServerAnswering() {
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		ConfigurableApplicationContext context = run(STDIO);
		int lines = 5000;
		this.unreadPipe.hold();

		// A write that waits on the pipe would hold this loop at its first line.
		withinTwoSeconds(() -> {
			for (int line = 0; line < lines; line++) {
				logger.warn(MESSAGE + ", call " + line + ".");
			}
			assertThat(context.getBean(Answering.class).answer()).isEqualTo("answered");
		});

		this.unreadPipe.release();
		awaitAnEmptyQueue();
		logger.error(LAST_LINE);
		await(() -> this.unreadPipe.written().contains(LAST_LINE), this.unreadPipe::written);
		// The lines that found room in the queue arrive in the order they were written,
		// and the lines that found it full were dropped.
		long arrived = this.unreadPipe.written().lines().filter((line) -> line.contains(MESSAGE)).count();
		assertThat(this.unreadPipe.written()).contains(MESSAGE + ", call 0.");
		assertThat(arrived).isBetween((long) stderrAppender().getQueueSize(), lines - 1L);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void log_queueWithRoomBesideAHostThatPauses_shouldKeepTheLinesBelowWarn() {
		// Logback drops the lines below WARN once a fifth of the queue is all that is
		// free. An operator who lowered the threshold asked for those lines, so they stay
		// for as long as the queue has room.
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO, "--logging.threshold.console=INFO");
		awaitAnEmptyQueue();
		int lines = stderrAppender().getQueueSize() - 10;
		this.unreadPipe.hold();

		withinTwoSeconds(() -> {
			for (int line = 0; line < lines; line++) {
				logger.info(MESSAGE + ", call " + line + ".");
			}
		});

		this.unreadPipe.release();
		awaitAnEmptyQueue();
		logger.error(LAST_LINE);
		await(() -> this.unreadPipe.written().contains(LAST_LINE), this.unreadPipe::written);
		assertThat(this.unreadPipe.written().lines().filter((line) -> line.contains(MESSAGE)).count()).isEqualTo(lines);
	}

	@Test
	void log_linesDroppedWhileTheHostLeftStderrUnread_shouldBeCountedInOneNotice() {
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		awaitAnEmptyQueue();
		int lines = 4 * stderrAppender().getQueueSize();

		logWhileTheHostPauses(logger::warn, "the pause", lines);
		logOnceTheHostReadsAgain("the pause");

		List<String> written = this.unreadPipe.written().lines().toList();
		long arrived = arrived(written, "the pause");
		assertThat(arrived).isLessThan(lines);
		assertThat(droppedLineCounts(written)).containsExactly(lines - arrived);
		// The notice stands where the lines are missing: behind the last line the queue
		// took before it was full, and ahead of the line that found room again.
		assertThat(written.get(written.size() - 3)).contains(MESSAGE);
		assertThat(written.get(written.size() - 2)).containsPattern(NOTICE);
		assertThat(written.get(written.size() - 1)).contains("the host reads again after the pause");
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void log_hostThatPausesTwice_shouldWriteANoticeForEachPause() {
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		awaitAnEmptyQueue();
		int first = 3 * stderrAppender().getQueueSize();
		int second = 5 * stderrAppender().getQueueSize();

		logWhileTheHostPauses(logger::warn, "the first pause", first);
		logOnceTheHostReadsAgain("the first pause");
		logWhileTheHostPauses(logger::warn, "the second pause", second);
		logOnceTheHostReadsAgain("the second pause");

		List<String> written = this.unreadPipe.written().lines().toList();
		assertThat(droppedLineCounts(written)).containsExactly(first - arrived(written, "the first pause"),
				second - arrived(written, "the second pause"));
	}

	@Test
	void droppedLines_oneLineAndSeveral_shouldNameTheCount() {
		assertThat(LogbackStderrLog.droppedLines(1))
			.isEqualTo("GATool dropped 1 log line while stderr was not being read");
		assertThat(LogbackStderrLog.droppedLines(412))
			.isEqualTo("GATool dropped 412 log lines while stderr was not being read");
	}

	@Test
	void log_queueThatTookEveryLine_shouldLeaveTheNoticeOut() {
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		awaitAnEmptyQueue();
		int lines = stderrAppender().getQueueSize() - 10;

		logWhileTheHostPauses(logger::warn, "the pause", lines);
		logOnceTheHostReadsAgain("the pause");

		List<String> written = this.unreadPipe.written().lines().toList();
		assertThat(arrived(written, "the pause")).isEqualTo(lines);
		assertThat(notices(written)).isEmpty();
	}

	@Test
	void log_consoleThresholdAboveWarn_shouldKeepTheNoticeOut() {
		// The notice is a WARN line, so an operator who asked for ERROR and above reads
		// the lines that arrived, and the count of the others stays out.
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO, "--logging.threshold.console=ERROR");
		awaitAnEmptyQueue();
		int lines = 4 * stderrAppender().getQueueSize();

		logWhileTheHostPauses(logger::error, "the pause", lines);
		logOnceTheHostReadsAgain("the pause");

		List<String> written = this.unreadPipe.written().lines().toList();
		assertThat(arrived(written, "the pause")).isPositive().isLessThan(lines);
		assertThat(notices(written)).isEmpty();
	}

	@Test
	void log_loggerOfTheNoticeSetAboveWarn_shouldKeepTheNoticeOut() {
		// The notice carries a logger name, so the level an operator set for that logger
		// decides about it as it decides about every line written under the name.
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO, "--logging.level." + LogbackStderrLog.class.getName() + "=ERROR");
		awaitAnEmptyQueue();
		int lines = 4 * stderrAppender().getQueueSize();

		logWhileTheHostPauses(logger::warn, "the pause", lines);
		logOnceTheHostReadsAgain("the pause");

		List<String> written = this.unreadPipe.written().lines().toList();
		assertThat(arrived(written, "the pause")).isPositive().isLessThan(lines);
		assertThat(notices(written)).isEmpty();
	}

	@Test
	void log_linesDroppedWhileSeveralThreadsLog_shouldBeCountedExactly() throws Exception {
		// The host reads slowly, so the queue fills and finds room again many times
		// while the threads log. Each change from one to the other is a place where a
		// count kept beside the queue could miss a line.
		SlowStream slowPipe = new SlowStream();
		System.setErr(new PrintStream(slowPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		awaitAnEmptyQueue();
		int threads = 4;
		int linesOfEach = 50000;
		ExecutorService servers = Executors.newFixedThreadPool(threads);
		try {
			List<Future<?>> logging = new ArrayList<>();
			for (int thread = 0; thread < threads; thread++) {
				int number = thread;
				logging.add(servers.submit(() -> {
					for (int line = 0; line < linesOfEach; line++) {
						logger.warn(MESSAGE + " in the burst, thread " + number + ", call " + line + ".");
					}
				}));
			}
			for (Future<?> thread : logging) {
				thread.get(10, TimeUnit.SECONDS);
			}
		}
		finally {
			servers.shutdownNow();
		}

		slowPipe.readAtOnce();
		awaitAnEmptyQueue();
		logger.error(LAST_LINE);
		await(() -> slowPipe.written().contains(LAST_LINE),
				() -> slowPipe.written().lines().count() + " lines, and the last one is missing");
		List<String> written = slowPipe.written().lines().toList();
		long arrived = arrived(written, "the burst");
		assertThat(arrived).isLessThan((long) threads * linesOfEach);
		assertThat(droppedLineCounts(written).stream().mapToLong(Long::longValue).sum())
			.isEqualTo(threads * linesOfEach - arrived);
	}

	@Test
	void log_stdioDefaults_shouldWriteOnADaemonThread() {
		// A thread that waits on a full pipe for good must leave the JVM free to exit.
		run(STDIO);

		assertThat(Thread.getAllStackTraces().keySet())
			.filteredOn((thread) -> thread.isAlive()
					&& thread.getName().equals("AsyncAppender-Worker-" + LogbackStderrLog.APPENDER_NAME))
			.singleElement()
			.satisfies((thread) -> assertThat(thread.isDaemon()).isTrue());
	}

	@Test
	void shutdown_linesStillQueued_shouldBeWrittenBeforeTheLogStops() throws InterruptedException {
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		this.unreadPipe.hold();
		withinTwoSeconds(() -> {
			logger.warn(MESSAGE + ", call 0.");
			logger.warn(MESSAGE + ", call 1.");
		});
		Thread host = new Thread(() -> {
			try {
				TimeUnit.MILLISECONDS.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			this.unreadPipe.release();
		});
		host.start();

		// What Boot's shutdown hook runs for Logback.
		((LoggerContext) LoggerFactory.getILoggerFactory()).stop();

		assertThat(this.unreadPipe.written()).contains(MESSAGE + ", call 0.").contains(MESSAGE + ", call 1.");
		host.join();
	}

	@Test
	void shutdown_hostThatLeavesStderrUnread_shouldEndOnceTheFlushTimeIsOver() {
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		this.unreadPipe.hold();
		withinTwoSeconds(() -> logger.warn(MESSAGE));

		assertCompletesWithin(Duration.ofSeconds(5), ((LoggerContext) LoggerFactory.getILoggerFactory())::stop);

		assertThat(this.unreadPipe.written()).isEmpty();
	}

	@Test
	void shutdown_gapStillOpenWhenTheLogStops_shouldEndTheStreamWithTheNotice() {
		// The server wrote its last line while the queue was full, so a line that would
		// bring the notice along does not follow, and the host reads again ahead of the
		// stop.
		System.setErr(new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));
		run(STDIO);
		awaitAnEmptyQueue();
		int lines = 4 * stderrAppender().getQueueSize();
		logWhileTheHostPauses(logger::warn, "the pause", lines);

		// What Boot's shutdown hook runs for Logback.
		assertCompletesWithin(Duration.ofSeconds(5), ((LoggerContext) LoggerFactory.getILoggerFactory())::stop);

		List<String> written = this.unreadPipe.written().lines().toList();
		assertThat(droppedLineCounts(written)).containsExactly(lines - arrived(written, "the pause"));
		assertThat(written.get(written.size() - 1)).containsPattern(NOTICE);
	}

	@Test
	void log_stderrLogTurnedOff_shouldAttachNothing() {
		run(STDIO, LOG_OFF);

		logger.warn(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isFalse();
		assertThat(written(this.stdout)).isEmpty();
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_propertyThatIsNotABoolean_shouldKeepTheDefault() {
		// An exception from the listener would stop startup before a context exists,
		// where the failure is missing from both streams. The binder refuses the value
		// later.
		run(STDIO, "--gatool.mcp.stdio.log-to-stderr=maybe");

		logger.warn(MESSAGE);

		assertThat(stderrOnceWritten()).containsPattern(WARN_LINE);
	}

	@Test
	void log_overHttp_shouldLeaveTheConsoleAsBootConfiguredIt() {
		run("--spring.main.web-application-type=none", "--spring.main.banner-mode=off");

		logger.warn(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isFalse();
		assertThat(written(this.stdout)).containsPattern(WARN_LINE);
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_consoleTurnedOnByTheApplication_shouldAttachNothing() {
		run(STDIO, "--logging.console.enabled=true");

		logger.warn(MESSAGE);

		// The application chose the console, which writes to stdout, so the line lands
		// in that one place.
		assertThat(LogbackStderrLog.attached()).isFalse();
		assertThat(written(this.stdout)).containsPattern(WARN_LINE);
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_consoleTurnedOffByTheApplication_shouldAttachNothing() {
		run(STDIO, "--logging.console.enabled=false");

		logger.warn(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isFalse();
		assertThat(written(this.stdout)).isEmpty();
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_applicationNamingItsOwnLogbackFile_shouldAttachNothing() {
		run(STDIO, "--logging.config=classpath:io/gatool/boot/mcp/autoconfigure/application-logback.xml");

		logger.warn(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isFalse();
		assertThat(written(this.stdout)).contains("own WARN " + MESSAGE);
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_applicationWithALogbackFileFoundByConvention_shouldAttachNothing() {
		// This module's logback-test.xml stands in for the file of an application, which
		// Boot finds under the names Logback itself looks for.
		run(getClass().getClassLoader(), STDIO);

		logger.warn(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isFalse();
		assertThat(written(this.stderr)).isEmpty();
	}

	@Test
	void log_consolePatternOfTheApplication_shouldShapeTheLine() {
		run(STDIO, "--logging.pattern.console=%level|%msg%n");

		logger.warn(MESSAGE);

		assertThat(stderrOnceWritten()).contains("WARN|" + MESSAGE);
	}

	@Test
	void log_consoleThresholdOfTheApplication_shouldKeepTheLinesBelowItOut() {
		run(STDIO, "--logging.threshold.console=ERROR");

		logger.warn(MESSAGE);
		logger.error("the API answered 500");

		assertThat(stderrOnceWritten()).doesNotContain(MESSAGE).containsPattern("ERROR .*the API answered 500");
	}

	@Test
	void log_structuredConsoleFormatOfTheApplication_shouldWriteTheLineInThatFormat() {
		run(STDIO, "--logging.structured.format.console=logstash");

		logger.warn(MESSAGE);

		assertThat(stderrOnceWritten().lines()).anySatisfy((line) -> assertThat(line).startsWith("{")
			.contains("\"level\":\"WARN\"")
			.contains("\"message\":\"" + MESSAGE + "\""));
	}

	@Test
	void log_secondApplicationAfterTheFirstClosed_shouldWriteTheLineOnce() {
		// Boot initialises the log again for the second application, and the reset that
		// opens the initialisation drops the appender of the first.
		run(STDIO).close();
		run(STDIO);

		logger.warn(MESSAGE);

		assertThat(LogbackStderrLog.attached()).isTrue();
		assertThat(stderrOnceWritten()).containsOnlyOnce(MESSAGE);
	}

	@Test
	void log_secondApplicationBesideTheFirst_shouldWriteTheLineOnce() {
		// Boot leaves a log that is already initialised as it is, so the appender of the
		// first application is still attached when the listener runs for the second.
		run(STDIO);
		run(STDIO);

		logger.warn(MESSAGE);

		assertThat(stderrOnceWritten()).containsOnlyOnce(MESSAGE);
	}

	@Test
	void onApplicationEvent_logbackAbsent_shouldLeaveTheLogAsItIs() {
		run(STDIO, LOG_OFF);
		SpringApplication onAnotherLoggingSystem = new SpringApplication();
		onAnotherLoggingSystem.setResourceLoader(new DefaultResourceLoader(withoutClass(LOGBACK)));

		new McpStdioLogListener().onApplicationEvent(event(onAnotherLoggingSystem));

		assertThat(LogbackStderrLog.attached()).isFalse();
	}

	@Test
	void onApplicationEvent_log4j2WithoutALogFile_shouldSayOnStderrThatTheLogIsLost() {
		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(), STDIO_ALONE));

		// One line, which names the two ways to get the log.
		assertThat(written(this.stderr).lines()).singleElement()
			.satisfies((line) -> assertThat(line).startsWith("GATool: ")
				.contains(LOG_IS_LOST)
				.contains("logging.file.name")
				.contains("an appender that writes to stderr"));
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_secondApplicationOnLog4j2_shouldWriteTheLineOnce() {
		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(), STDIO_ALONE));
		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(), STDIO_ALONE));

		assertThat(written(this.stderr)).containsOnlyOnce(LOG_IS_LOST);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2WithALogFileNamed_shouldLeaveStderrEmpty() {
		new McpStdioLogListener().onApplicationEvent(
				event(onLog4j2(), Map.of("spring.ai.mcp.server.stdio", "true", "logging.file.name", "server.log")));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2WithADirectoryForTheLogFile_shouldLeaveStderrEmpty() {
		new McpStdioLogListener().onApplicationEvent(
				event(onLog4j2(), Map.of("spring.ai.mcp.server.stdio", "true", "logging.file.path", "logs")));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2WithAConfigurationOfTheApplication_shouldLeaveStderrEmpty() {
		// The application named a Log4j2 file of its own, and GATool cannot tell where
		// that file sends the log.
		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(),
				Map.of("spring.ai.mcp.server.stdio", "true", "logging.config", "classpath:log4j2-server.xml")));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2OverHttp_shouldLeaveStderrEmpty() {
		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(), Map.of()));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2WithTheStderrLogTurnedOff_shouldLeaveStderrEmpty() {
		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(),
				Map.of("spring.ai.mcp.server.stdio", "true", McpStdioLogListener.LOG_TO_STDERR, "false")));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2WithTheConsoleTurnedOffByTheApplication_shouldLeaveStderrEmpty() {
		new McpStdioLogListener().onApplicationEvent(
				event(onLog4j2(), Map.of("spring.ai.mcp.server.stdio", "true", "logging.console.enabled", "false")));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_log4j2BesideALoggingSystemNamedByTheApplication_shouldLeaveStderrEmpty() {
		// The system property makes Boot run the logging system it names, whatever the
		// classpath holds.
		System.setProperty(BOOTS_LOGGING_SYSTEM, "none");

		new McpStdioLogListener().onApplicationEvent(event(onLog4j2(), STDIO_ALONE));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_javaUtilLogging_shouldLeaveStderrEmpty() {
		// Without Logback and Log4j2 Boot runs java.util.logging, whose console handler
		// writes to stderr, so the log of such a server arrives.
		SpringApplication onJavaUtilLogging = new SpringApplication();
		onJavaUtilLogging.setResourceLoader(new DefaultResourceLoader(withoutClass(LOGBACK)));

		new McpStdioLogListener().onApplicationEvent(event(onJavaUtilLogging));

		assertThat(written(this.stderr)).isEmpty();
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void onApplicationEvent_logbackPresent_shouldAttachTheAppender() {
		// The control for the test above: the same log and the same environment, with
		// Logback visible to the application's class loader.
		run(STDIO, LOG_OFF);

		new McpStdioLogListener().onApplicationEvent(event(new SpringApplication()));

		assertThat(LogbackStderrLog.attached()).isTrue();
	}

	@Test
	void startupFailure_stdioWithTheLogOnStderr_shouldWriteTheAnalysisOnceAndDirectly() {
		assertThatExceptionOfType(Throwable.class)
			.isThrownBy(() -> run(FailingApplication.class, withoutLogbackFiles(), STDIO))
			.withRootCauseInstanceOf(InvalidConfigurationPropertyValueException.class);

		// The reporter writes the analysis itself, so it arrives whatever the queue of
		// the log holds at exit, and Boot's copy of it stays out of the stderr log. The
		// two writers reach the stream in either order.
		assertThat(stderrOnceWritten()).containsOnlyOnce("APPLICATION FAILED TO START")
			.contains("gatool.test.value")
			.doesNotContain(BOOTS_REPORTER);
		assertThat(written(this.stdout)).isEmpty();
	}

	@Test
	void startupFailure_stdioWithTheStderrLogTurnedOff_shouldWriteTheAnalysisOnce() {
		assertThatExceptionOfType(Throwable.class)
			.isThrownBy(() -> run(FailingApplication.class, withoutLogbackFiles(), STDIO, LOG_OFF))
			.withRootCauseInstanceOf(InvalidConfigurationPropertyValueException.class);

		assertThat(written(this.stdout)).isEmpty();
		assertThat(written(this.stderr)).containsOnlyOnce("APPLICATION FAILED TO START")
			.contains("gatool.test.value")
			.doesNotContain(BOOTS_REPORTER);
	}

	@Test
	void startupFailure_withoutAnAnalysis_shouldKeepBootsStackTraceInTheLog() {
		assertThatExceptionOfType(Throwable.class)
			.isThrownBy(() -> run(UnanalysedFailure.class, withoutLogbackFiles(), STDIO))
			.withRootCauseInstanceOf(IllegalStateException.class);

		// The reporter writes the message chain, and the ERROR line Boot writes for a
		// failure that every analyzer passed on stays in the log with its stack trace.
		assertThat(stderrOnceWritten()).containsOnlyOnce("APPLICATION FAILED TO START")
			.containsPattern("ERROR .*Application run failed")
			.contains("the schema file is unreadable");
	}

	@Test
	void onApplicationEvent_failureWithTheLogOnStderr_shouldWriteTheReport() {
		ConfigurableApplicationContext context = run(STDIO);
		ByteArrayOutputStream report = new ByteArrayOutputStream();

		new McpStdioStartupFailureReporter(new PrintStream(report, true, StandardCharsets.UTF_8))
			.onApplicationEvent(new ApplicationFailedEvent(new SpringApplication(), new String[0], context,
					new IllegalStateException("the schema file is unreadable")));

		assertThat(written(report)).contains("APPLICATION FAILED TO START").contains("the schema file is unreadable");
	}

	@Test
	void report_streamThatBlocks_shouldReturnWithinTwoSeconds() {
		ConfigurableApplicationContext context = run(STDIO);
		this.unreadPipe.hold();
		McpStdioStartupFailureReporter reporter = new McpStdioStartupFailureReporter(
				new PrintStream(this.unreadPipe, true, StandardCharsets.UTF_8));

		// The pipe stays full for the whole call, so the reporter itself has to return
		// within a bound, or a failed start would stay alive as long as the host leaves
		// the pipe unread.
		assertCompletesWithin(Duration.ofSeconds(5),
				() -> reporter.onApplicationEvent(new ApplicationFailedEvent(new SpringApplication(), new String[0],
						context, new IllegalStateException("the schema file is unreadable"))));
	}

	@Test
	void factories_shouldRegisterTheListenerOnePlaceBehindBootsLoggingListener() {
		List<?> listeners = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
			.load(ApplicationListener.class);

		assertThat(listeners).filteredOn(McpStdioLogListener.class::isInstance)
			.singleElement()
			.satisfies((listener) -> assertThat(((McpStdioLogListener) listener).getOrder())
				.isEqualTo(LoggingApplicationListener.DEFAULT_ORDER + 1));
	}

	private ConfigurableApplicationContext run(String... arguments) {
		return run(withoutLogbackFiles(), arguments);
	}

	private ConfigurableApplicationContext run(ClassLoader classLoader, String... arguments) {
		return run(Application.class, classLoader, arguments);
	}

	private ConfigurableApplicationContext run(Class<?> source, ClassLoader classLoader, String... arguments) {
		SpringApplication application = new SpringApplication(source);
		application.setResourceLoader(new DefaultResourceLoader(classLoader));
		application.setRegisterShutdownHook(false);
		List<String> all = new ArrayList<>(List.of(arguments));
		all.add("--logging.register-shutdown-hook=false");
		ConfigurableApplicationContext context = application.run(all.toArray(String[]::new));
		this.contexts.add(context);
		return context;
	}

	// Writes the lines while the host leaves stderr unread, and lets the host read
	// again. The thread that writes first waits on the stream with a line of its own,
	// so the queue fills once and stays full for the rest of the pause. Every write
	// to the log made while the stream is held runs under the bound.
	private void logWhileTheHostPauses(Consumer<Object> atLevel, String pause, int lines) {
		this.unreadPipe.hold();
		withinTwoSeconds(() -> atLevel.accept("the host stops reading, which starts " + pause));
		await(this.unreadPipe::holdsAWrite, () -> "the write of the line that starts " + pause + " is missing");
		withinTwoSeconds(() -> {
			for (int line = 0; line < lines; line++) {
				atLevel.accept(MESSAGE + " in " + pause + ", call " + line + ".");
			}
		});
		this.unreadPipe.release();
		awaitAnEmptyQueue();
	}

	// Writes one more line, which the queue takes, and waits until it has arrived.
	private void logOnceTheHostReadsAgain(String pause) {
		String readingAgain = "the host reads again after " + pause;
		logger.error(readingAgain);
		await(() -> this.unreadPipe.written().contains(readingAgain), this.unreadPipe::written);
	}

	private static long arrived(List<String> written, String pause) {
		return written.stream().filter((line) -> line.contains(MESSAGE + " in " + pause)).count();
	}

	// The lines that speak of dropped lines, whatever their level and their logger.
	private static List<String> notices(List<String> written) {
		return written.stream().filter((line) -> line.contains(NOTICE_TEXT)).toList();
	}

	// The counts the notices hold, in the order the notices were written.
	private static List<Long> droppedLineCounts(List<String> written) {
		return written.stream()
			.flatMap((line) -> NOTICE.matcher(line).results())
			.map((notice) -> Long.valueOf(notice.group(1)))
			.toList();
	}

	// Runs what a server thread does while the host leaves stderr unread. A write that
	// waits on the stream keeps the thread past the bound, which fails the test.
	private static void withinTwoSeconds(Runnable serverWork) {
		assertCompletesWithin(Duration.ofSeconds(2), serverWork);
	}

	// Runs the work on its own thread and fails when the bound passes first. The thread
	// is interrupted on the way out, so a write that waits on a full pipe lets go.
	private static void assertCompletesWithin(Duration bound, Runnable work) {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			assertThat(executor.submit(work)).succeedsWithin(bound);
		}
		finally {
			executor.shutdownNow();
		}
	}

	// Writes one last line and returns stderr once that line is there, which is when
	// every line written before it has arrived or was kept out.
	private String stderrOnceWritten() {
		logger.error(LAST_LINE);
		await(() -> written(this.stderr).contains(LAST_LINE), () -> written(this.stderr));
		return written(this.stderr);
	}

	private static void awaitAnEmptyQueue() {
		AsyncAppender appender = stderrAppender();
		await(() -> appender.getNumberOfElementsInQueue() == 0,
				() -> appender.getNumberOfElementsInQueue() + " lines in the queue");
	}

	private static AsyncAppender stderrAppender() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		return (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(LogbackStderrLog.APPENDER_NAME);
	}

	private static void await(BooleanSupplier condition, Supplier<String> state) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Waited five seconds for the log and found: " + state.get());
			}
			try {
				TimeUnit.MILLISECONDS.sleep(5);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for the log", ex);
			}
		}
	}

	// The event as Boot publishes it for a stdio server that left the log to GATool's
	// defaults: the environment has passed through the post-processor.
	private static ApplicationEnvironmentPreparedEvent event(SpringApplication application) {
		return event(application, STDIO_ALONE);
	}

	static ApplicationEnvironmentPreparedEvent event(SpringApplication application, Map<String, Object> properties) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
		new GAToolEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
		return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(), application, new String[0],
				environment);
	}

	// An application whose classpath holds Log4j2 where the classpath of these tests
	// holds Logback.
	static SpringApplication onLog4j2() {
		SpringApplication application = new SpringApplication();
		application.setResourceLoader(new DefaultResourceLoader(withLog4j2InPlaceOfLogback()));
		return application;
	}

	private static String written(ByteArrayOutputStream stream) {
		return stream.toString(StandardCharsets.UTF_8);
	}

	// A class loader that hides the Logback files on the test classpath, the way an
	// application that ships without one looks to Boot, and delegates every other
	// name to this test's loader.
	private static ClassLoader withoutLogbackFiles() {
		return new ClassLoader(McpStdioLogListenerTests.class.getClassLoader()) {

			@Override
			public URL getResource(String name) {
				return name.startsWith("logback") ? null : super.getResource(name);
			}
		};
	}

	// A class loader that hides the class Boot recognises Logback by and holds the one
	// it recognises Log4j2 by, which is what both look like from an application on
	// Log4j2. The class it holds has the name and is empty otherwise, because a check
	// for the presence of a class reads the name alone.
	private static ClassLoader withLog4j2InPlaceOfLogback() {
		return new ClassLoader(withoutClass(LOGBACK)) {

			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				if (!LOG4J2.equals(name)) {
					throw new ClassNotFoundException(name);
				}
				byte[] classFile = emptyClass(name);
				return defineClass(name, classFile, 0, classFile.length);
			}
		};
	}

	// A class file as the Java Virtual Machine Specification lays it out, for a public
	// class that extends Object and is empty otherwise.
	private static byte[] emptyClass(String name) {
		ByteArrayOutputStream classFile = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(classFile)) {
			out.writeInt(0xCAFEBABE);
			// The version of the format, which is the one Java 8 writes.
			out.writeShort(0);
			out.writeShort(52);
			// The constant pool: the class, its name, the superclass, its name.
			out.writeShort(5);
			out.writeByte(7);
			out.writeShort(2);
			out.writeByte(1);
			out.writeUTF(name.replace('.', '/'));
			out.writeByte(7);
			out.writeShort(4);
			out.writeByte(1);
			out.writeUTF("java/lang/Object");
			// The access flags for a public class, then the class and the superclass as
			// places in the constant pool.
			out.writeShort(0x0021);
			out.writeShort(1);
			out.writeShort(3);
			// The counts of the interfaces, the fields, the methods and the attributes,
			// which are all zero.
			out.writeShort(0);
			out.writeShort(0);
			out.writeShort(0);
			out.writeShort(0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return classFile.toByteArray();
	}

	// A class loader that hides one class, the way the classpath of an application on
	// another logging system lacks it.
	private static ClassLoader withoutClass(String hiddenClass) {
		return new ClassLoader(McpStdioLogListenerTests.class.getClassLoader()) {

			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (hiddenClass.equals(name)) {
					throw new ClassNotFoundException(name);
				}
				return super.loadClass(name, resolve);
			}
		};
	}

	/**
	 * The stderr of a server whose host stopped reading: once held, a write waits the way
	 * a write to a full pipe waits, until the test releases it. An interrupt leaves the
	 * write waiting, as it leaves a write to a pipe.
	 */
	private static final class HeldStream extends OutputStream {

		private final ByteArrayOutputStream written = new ByteArrayOutputStream();

		private final AtomicInteger waiting = new AtomicInteger();

		private volatile CountDownLatch held = new CountDownLatch(0);

		void hold() {
			this.held = new CountDownLatch(1);
		}

		void release() {
			this.held.countDown();
		}

		// Whether a write waits for the release, which is where the thread that writes
		// the log stands once it has taken a line out of the queue.
		boolean holdsAWrite() {
			return this.waiting.get() > 0;
		}

		String written() {
			return this.written.toString(StandardCharsets.UTF_8);
		}

		@Override
		public void write(int value) {
			awaitTheRelease();
			this.written.write(value);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) {
			awaitTheRelease();
			this.written.write(bytes, offset, length);
		}

		private void awaitTheRelease() {
			CountDownLatch release = this.held;
			if (release.getCount() == 0) {
				return;
			}
			boolean interrupted = false;
			this.waiting.incrementAndGet();
			while (true) {
				try {
					release.await();
					break;
				}
				catch (InterruptedException ex) {
					interrupted = true;
				}
			}
			this.waiting.decrementAndGet();
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}

	}

	/**
	 * The stderr of a server whose host reads slowly: every write takes a moment, so
	 * threads that log without a pause fill the queue faster than it is written, until
	 * the test lets the host read at once.
	 */
	private static final class SlowStream extends OutputStream {

		private final ByteArrayOutputStream written = new ByteArrayOutputStream();

		private volatile boolean slow = true;

		void readAtOnce() {
			this.slow = false;
		}

		String written() {
			return this.written.toString(StandardCharsets.UTF_8);
		}

		@Override
		public void write(int value) {
			this.written.write(value);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) {
			if (this.slow) {
				LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(50));
			}
			this.written.write(bytes, offset, length);
		}

	}

	/**
	 * A bean that writes to the log while it answers, the way a tool call does.
	 */
	static class Answering {

		String answer() {
			logger.warn("answering a call");
			return "answered";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Application {

		@Bean
		Answering answering() {
			return new Answering();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class FailingApplication {

		@Bean
		String value() {
			throw new InvalidConfigurationPropertyValueException("gatool.test.value", "x", "The value is refused.");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UnanalysedFailure {

		@Bean
		String value() {
			throw new IllegalStateException("the schema file is unreadable");
		}

	}

}
