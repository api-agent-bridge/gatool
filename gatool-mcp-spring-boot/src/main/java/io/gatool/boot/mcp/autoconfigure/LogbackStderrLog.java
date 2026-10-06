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

import java.nio.charset.Charset;
import java.util.concurrent.locks.ReentrantLock;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.joran.spi.ConsoleTarget;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.status.InfoStatus;
import ch.qos.logback.core.status.Status;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * The Logback appender that writes the log of a stdio server to stderr.
 *
 * <p>
 * Every Logback type GATool names lives in this class, and {@link McpStdioLogListener}
 * loads it only while Logback is on the classpath.
 *
 * <p>
 * Two appenders do the work. The one on the root logger is Logback's
 * {@code AsyncAppender}, which puts a line into a queue and returns, and a console
 * appender behind it writes the queue to {@code System.err} on a thread of its own.
 * stderr is a pipe the host reads, and a write to a pipe that is full waits until the
 * host reads again: if the thread that logs did the writing, a server would stop
 * answering once its host had left about 64 KB unread. With the queue between them, the
 * thread that logs returns whatever the pipe does, and a line that finds the queue full
 * is dropped.
 *
 * <p>
 * The queue counts the lines it drops. The first line it takes again is queued behind a
 * notice, a WARN line under the logger named after this class, which says how many lines
 * were dropped, so an operator who reads stderr finds the count at the place where the
 * lines are missing. A host that reads again while the server stays silent finds the
 * notice ahead of the next line the server writes, or at the end of the stream once the
 * server has stopped.
 *
 * <p>
 * The console appender takes the place of Boot's, which stays attached and silent under
 * {@code logging.console.enabled=false}, so it is built the way
 * {@code DefaultLogbackConfiguration} builds that one: the pattern, the charset and the
 * structured format come from the properties Boot put into the Logback context, which
 * hold {@code logging.pattern.console}, {@code logging.charset.console} and
 * {@code logging.structured.format.console} or Boot's defaults for them. Logback's
 * console appender resolves {@code System.err} at every write, so a stream replaced after
 * the attachment receives the lines that follow.
 *
 * <p>
 * stderr carries WARN and above, so the stream holds what went wrong and the lines of a
 * normal start, which are written at INFO, stay out of it.
 * {@code logging.threshold.console} replaces that threshold with the application's own.
 * It is read from the {@link Environment}, because Boot turns the console off by writing
 * {@code OFF} over the threshold it keeps in the context. The appender hangs on the root
 * logger, so the levels that {@code logging.level} sets decide which lines reach the
 * threshold at all.
 *
 * @author Željko Kozina
 */
final class LogbackStderrLog {

	/**
	 * The name of the appender on the root logger, which is the one that queues. The
	 * attachment is recognised by this name.
	 */
	static final String APPENDER_NAME = "GATOOL_STDERR";

	private static final String WRITER_NAME = "GATOOL_STDERR_WRITER";

	private static final String THRESHOLD = "logging.threshold.console";

	private static final String DEFAULT_THRESHOLD = "WARN";

	private static final String PATTERN = "CONSOLE_LOG_PATTERN";

	private static final String CHARSET = "CONSOLE_LOG_CHARSET";

	private static final String STRUCTURED_FORMAT = "CONSOLE_LOG_STRUCTURED_FORMAT";

	// Logback's default. The size matters only while the host has stopped reading: a host
	// that reads receives every line of a start at DEBUG through a queue of 16 as through
	// this one. While the host pauses, the pipe takes what it holds, about 64 KB, and the
	// queue takes this many lines more. The lines beyond both are dropped. At WARN and
	// above that is room for minutes of a failing API. A queued line keeps its message
	// and its exception in memory until it is written, which is the reason to leave the
	// queue this small.
	private static final int QUEUE_SIZE = 256;

	// Logback drops TRACE, DEBUG and INFO lines once less of the queue than this is
	// free, which is a fifth by default. Zero leaves every level in while the queue
	// has room, so an operator who lowered the threshold loses a line to a full queue
	// alone, and to that at every level alike. Logback drops those lines ahead of its
	// offer and without a count, so zero is also what lets the queue count every line
	// it drops.
	private static final int DISCARDING_THRESHOLD = 0;

	// How long a stop waits for the thread that writes, in milliseconds, which is
	// Logback's default. Boot stops the Logback context from the shutdown hook it
	// registers for the log (logging.register-shutdown-hook, on by default) and ahead of
	// each initialisation that follows the first. The stop lets the thread write what the
	// queue still holds, so the last lines of a server that ends normally arrive. Beside
	// a pipe that stays full the stop gives up when this time is over and the queue is
	// lost, so such a host holds up the exit by one second. With Boot's hook turned off,
	// the JVM exits over whatever the queue holds.
	private static final int MAX_FLUSH_TIME = 1000;

	private static final String BOOTS_FAILURE_REPORTER = "org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter";

	private LogbackStderrLog() {
	}

	/**
	 * Attaches the appender to the root logger, where Boot configured Logback from its
	 * own defaults and the appender is still absent.
	 * @param environment the environment the threshold is read from
	 */
	static void attach(Environment environment) {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
			return;
		}
		context.getConfigurationLock().lock();
		try {
			String pattern = context.getProperty(PATTERN);
			Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
			if (configuredFromAFile(context) || pattern == null || root.getAppender(APPENDER_NAME) != null) {
				return;
			}
			root.addAppender(queue(context, environment, writer(context, pattern)));
		}
		finally {
			context.getConfigurationLock().unlock();
		}
	}

	/**
	 * Whether the appender hangs on the root logger.
	 * @return true while it is attached
	 */
	static boolean attached() {
		return LoggerFactory.getILoggerFactory() instanceof LoggerContext context
				&& context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender(APPENDER_NAME) != null;
	}

	/**
	 * What the notice says about the lines the queue dropped.
	 * @param lines how many lines were dropped since the last notice
	 * @return the message of the notice
	 */
	static String droppedLines(long lines) {
		return "GATool dropped " + lines + ((lines == 1) ? " log line" : " log lines")
				+ " while stderr was not being read";
	}

	// Logback keeps the model of the last configuration file it applied in the context,
	// and Boot does the same with a configuration it generated ahead of time. Boot resets
	// the context before it loads its own defaults, which clears the entry, so an entry
	// says the application brought a file through logging.config or under one of the
	// names Boot finds by convention, such as logback-spring.xml. Asking Boot which file
	// it would find is out of reach, because the search that answers is protected in
	// LogbackLoggingSystem.
	private static boolean configuredFromAFile(LoggerContext context) {
		return context.getObject(CoreConstants.SAFE_JORAN_CONFIGURATION) != null;
	}

	// The appender on the root logger. Both filters hang here, ahead of the queue, so
	// the room of the queue goes to the lines that stderr carries.
	//
	// neverBlock makes a full queue drop the line, where Logback's default has the
	// thread that logs wait for room, which is the stall the queue is there to end.
	// Caller data stays off: Logback would walk the stack of every line on the thread
	// that logs, and Boot's console pattern leaves the class, the method and the line
	// number of the caller out. A pattern of the application's own that asks for them
	// prints a question mark in their place.
	//
	// Logback starts the thread that writes as a daemon, so one that waits on a full
	// pipe leaves the JVM free to exit.
	private static AsyncAppender queue(LoggerContext context, Environment environment,
			ConsoleAppender<ILoggingEvent> writer) {
		AsyncAppender queue = new QuietQueue(context.getLogger(LogbackStderrLog.class));
		queue.setContext(context);
		queue.setName(APPENDER_NAME);
		queue.setQueueSize(QUEUE_SIZE);
		queue.setDiscardingThreshold(DISCARDING_THRESHOLD);
		queue.setNeverBlock(true);
		queue.setIncludeCallerData(false);
		queue.setMaxFlushTime(MAX_FLUSH_TIME);
		queue.addFilter(threshold(environment));
		queue.addFilter(bootsFailureReport());
		queue.addAppender(writer);
		queue.start();
		return queue;
	}

	private static ConsoleAppender<ILoggingEvent> writer(LoggerContext context, String pattern) {
		ConsoleAppender<ILoggingEvent> writer = new ConsoleAppender<>();
		writer.setContext(context);
		writer.setName(WRITER_NAME);
		writer.setTarget(ConsoleTarget.SystemErr.getName());
		Encoder<ILoggingEvent> encoder = encoder(context, pattern);
		encoder.setContext(context);
		encoder.start();
		writer.setEncoder(encoder);
		writer.start();
		return writer;
	}

	private static ThresholdFilter threshold(Environment environment) {
		String level = environment.getProperty(THRESHOLD, DEFAULT_THRESHOLD);
		ThresholdFilter filter = new ThresholdFilter();
		// YAML reads an unquoted OFF as false, which Boot maps back for its own appender.
		filter.setLevel("false".equals(level) ? "OFF" : level);
		filter.start();
		return filter;
	}

	private static Filter<ILoggingEvent> bootsFailureReport() {
		Filter<ILoggingEvent> filter = new BootsFailureReport();
		filter.start();
		return filter;
	}

	private static Encoder<ILoggingEvent> encoder(LoggerContext context, String pattern) {
		String charsetName = context.getProperty(CHARSET);
		Charset charset = (charsetName != null) ? Charset.forName(charsetName) : Charset.defaultCharset();
		String structuredFormat = context.getProperty(STRUCTURED_FORMAT);
		if (StringUtils.hasLength(structuredFormat)) {
			StructuredLogEncoder encoder = new StructuredLogEncoder();
			encoder.setFormat(structuredFormat);
			encoder.setCharset(charset);
			return encoder;
		}
		PatternLayoutEncoder encoder = new PatternLayoutEncoder();
		encoder.setCharset(charset);
		encoder.setPattern(pattern);
		return encoder;
	}

	/**
	 * The queue, which counts the lines it drops and keeps its own warnings in Logback's
	 * status list.
	 *
	 * <p>
	 * A line that finds the queue full is dropped, and so is every line that follows
	 * until the host reads again. The stream then holds the lines on both sides of the
	 * gap, and an operator who reads it cannot tell that lines are missing between them.
	 * So the queue counts each line it drops, and the first line it takes again is queued
	 * behind a notice that holds the count. The notice is a line of the log like every
	 * other: it is written at WARN under the logger of {@link LogbackStderrLog}, in the
	 * pattern of the console, and the level of that logger and the threshold of stderr
	 * decide whether it is written. It passes through the queue like the lines around it,
	 * so it cannot make the server wait, and the count is set back only where the queue
	 * had a place for the notice. Each gap gives one notice, and a gap that is still open
	 * when the log stops gives its notice at the stop, where the queue has room for it.
	 * One pause of the host can hold two gaps: Logback's thread takes one line out of the
	 * queue and then the rest, and where the queue fills between the two steps it finds
	 * room again while the host is still away.
	 *
	 * <p>
	 * Boot prints every warning and error that joins Logback's status list to
	 * {@code System.err}, on the thread that added it. A stop beside a full pipe ends
	 * with such a warning, which says the flush time is over, and the thread that stops
	 * the log at exit is Boot's shutdown hook: printing the warning would hold that
	 * thread on the pipe, and the JVM with it. Recorded as information, the message stays
	 * in the list, where Logback's own status tools read it, and the stop returns.
	 */
	private static final class QuietQueue extends AsyncAppender {

		private final Logger noticeLogger;

		private final ReentrantLock offers = new ReentrantLock();

		// The lines dropped since the last notice, read and written under the lock.
		private long dropped;

		QuietQueue(Logger noticeLogger) {
			this.noticeLogger = noticeLogger;
		}

		// Logback offers a line to the queue in AsyncAppenderBase.put, which is private
		// and leaves the answer of the offer unread, and the queue is a field Logback
		// keeps to its package. So this method asks whether the queue has room, ahead of
		// Logback's offer, and the lock makes the answer hold until the offer is made:
		// every thread that logs offers under the lock, and the thread that writes only
		// takes lines out, so the room found here is still there when Logback offers.
		// The count is read and set back under the same lock, so a line that another
		// thread drops meanwhile is counted by this notice or by the next one.
		//
		// The lock is held for a count or for an offer, while the writing happens on the
		// other side of the queue. Logback's queue guards each offer with a lock of its
		// own, so the threads that log already share a lock of this length.
		@Override
		protected void append(ILoggingEvent event) {
			// Logback formats the message on the thread that logs, ahead of its offer,
			// and formatting calls toString() on the arguments of the line, which is code
			// of the application. Formatting here keeps that code outside the lock, and
			// Logback finds the message formatted.
			event.prepareForDeferredProcessing();
			this.offers.lock();
			try {
				offer(event);
			}
			finally {
				this.offers.unlock();
			}
		}

		private void offer(ILoggingEvent event) {
			int free = getRemainingCapacity();
			if (free == 0) {
				this.dropped++;
				return;
			}
			if (this.dropped > 0) {
				ILoggingEvent notice = notice(this.dropped);
				if (written(notice)) {
					// The notice and the line take a place each. A line that finds one
					// place is dropped like the lines ahead of it, so the notice stands
					// ahead of the first line the queue takes, and it holds every line
					// dropped until then.
					if (free < 2) {
						this.dropped++;
						return;
					}
					super.append(notice);
				}
				this.dropped = 0;
			}
			super.append(event);
		}

		// Boot stops the log at exit and ahead of each initialisation that follows the
		// first. A gap is still open then where the server wrote its last line while the
		// queue was full, and the notice is offered here, because a line that would bring
		// it along does not follow. Beside a pipe that stays full the queue is full as
		// well, and the stop goes on without the notice.
		@Override
		public void stop() {
			this.offers.lock();
			try {
				if (isStarted() && this.dropped > 0 && getRemainingCapacity() > 0) {
					ILoggingEvent notice = notice(this.dropped);
					if (written(notice)) {
						super.append(notice);
					}
					this.dropped = 0;
				}
			}
			finally {
				this.offers.unlock();
			}
			super.stop();
		}

		@Override
		public void addStatus(Status status) {
			boolean printed = status.getLevel() >= Status.WARN;
			super.addStatus(
					printed ? new InfoStatus(status.getMessage(), status.getOrigin(), status.getThrowable()) : status);
		}

		private ILoggingEvent notice(long lines) {
			return new LoggingEvent(QuietQueue.class.getName(), this.noticeLogger, Level.WARN, droppedLines(lines),
					null, null);
		}

		// Logback asks the level of the logger and the filters of the appender ahead of
		// append, for a line a logger sends. The notice starts behind both, so they are
		// asked here.
		private boolean written(ILoggingEvent notice) {
			return this.noticeLogger.isEnabledFor(Level.WARN) && getFilterChainDecision(notice) != FilterReply.DENY;
		}

	}

	/**
	 * Keeps Boot's copy of a startup failure analysis out of stderr.
	 *
	 * <p>
	 * {@link McpStdioStartupFailureReporter} writes the analysis to stderr itself, so
	 * that it arrives whatever the queue holds when the process exits. Boot writes the
	 * same analysis through the log, at ERROR, from
	 * {@code LoggingFailureAnalysisReporter}, and with both on one stream the host would
	 * read it twice. The filter hangs on this appender alone, so a log file keeps Boot's
	 * copy. Turning that logger off would take the analysis out of the log file as well.
	 * The DEBUG line of the same logger, which carries the stack trace of the failure,
	 * passes for an operator who lowered the levels to read it.
	 */
	private static final class BootsFailureReport extends Filter<ILoggingEvent> {

		@Override
		public FilterReply decide(ILoggingEvent event) {
			boolean report = BOOTS_FAILURE_REPORTER.equals(event.getLoggerName())
					&& event.getLevel().isGreaterOrEqual(Level.ERROR);
			return report ? FilterReply.DENY : FilterReply.NEUTRAL;
		}

	}

}
