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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Sends what Log4j2 reports about itself to stderr while the server runs over stdio.
 *
 * <p>
 * Log4j2 reports its own trouble through its status logger: a log file it cannot create,
 * an appender a configuration names and leaves out, a rollover that failed, a collector
 * that refused the connection. Spring Boot 4.1.1 registers a console listener for those
 * reports at WARN and above when it starts the log, built with the constructor of Log4j2
 * that writes to {@code System.out}. Under stdio that stream carries the MCP messages,
 * and a server whose log file cannot be created would write such reports ahead of its
 * first answer.
 *
 * <p>
 * Boot creates the listener and loads the configuration in one call, so the reports of a
 * configuration that fails to load are written before any code behind that call runs.
 * {@link #watch} therefore registers a listener of GATool's own ahead of Boot. Log4j2
 * hands a report to its listeners in the order they were registered, so this one receives
 * each report first, and it points the console listeners registered since the watch began
 * at stderr before they write. The writing stays with those listeners.
 * {@link #sendToStderr} does the same once Boot has started the log, for a start without
 * a report, and ends the watch, so the listener Boot registered is the one that writes
 * from then on.
 *
 * <p>
 * A listener that was registered ahead of the watch stays as it is: the application
 * registered it, and it may write to a file, which Log4j2 closes when a listener is
 * handed another stream. Log4j2 keeps to itself where a console listener writes, so the
 * time of the registration is what tells Boot's listener from the application's.
 *
 * <p>
 * The Log4j API is read through reflection. Its class files carry OSGi annotations whose
 * classes it leaves to the build that compiles against it, javac warns about each one it
 * cannot find, and this build stops on a warning. The methods read here have been part of
 * the API since Log4j2 2.23. Where one of them is missing, or the API itself, the reports
 * stay where Log4j2 and Boot send them, and the start goes on.
 *
 * @author Željko Kozina
 */
final class Log4j2StatusLog implements InvocationHandler {

	private static final String STATUS_LOGGER = "org.apache.logging.log4j.status.StatusLogger";

	private static final String STATUS_LISTENER = "org.apache.logging.log4j.status.StatusListener";

	private static final String CONSOLE_LISTENER = "org.apache.logging.log4j.status.StatusConsoleListener";

	private static final String LEVEL = "org.apache.logging.log4j.Level";

	private final Api api;

	// Compared by identity, because a listener is the object that was registered,
	// whatever its equals method says.
	private final Set<Object> registeredAhead;

	private Log4j2StatusLog(Api api, Set<Object> registeredAhead) {
		this.api = api;
		this.registeredAhead = registeredAhead;
	}

	/**
	 * Begins the watch, ahead of the log Boot starts. A watch that an earlier start left
	 * behind, because that start failed, ends here.
	 * @param classLoader the class loader of the application, which holds Log4j2
	 */
	static void watch(ClassLoader classLoader) {
		try {
			Api api = Api.in(classLoader);
			Set<Object> registeredAhead = Collections.newSetFromMap(new IdentityHashMap<>());
			for (Object listener : api.listeners()) {
				if (watchOf(listener) != null) {
					api.remove(listener);
				}
				else {
					registeredAhead.add(listener);
				}
			}
			api.register(Proxy.newProxyInstance(classLoader, new Class<?>[] { api.listener },
					new Log4j2StatusLog(api, registeredAhead)));
		}
		catch (ReflectiveOperationException | LinkageError | IllegalArgumentException ex) {
			// The reports stay where Log4j2 and Boot send them.
		}
	}

	/**
	 * Points the console listeners registered since the watch began at stderr and ends
	 * the watch, once Boot has started the log.
	 * @param classLoader the class loader of the application, which holds Log4j2
	 */
	static void sendToStderr(ClassLoader classLoader) {
		try {
			Api api = Api.in(classLoader);
			for (Object listener : api.listeners()) {
				Log4j2StatusLog watch = watchOf(listener);
				if (watch != null) {
					watch.pointTheConsoleListenersAtStderr();
					api.remove(listener);
				}
			}
		}
		catch (ReflectiveOperationException | LinkageError | IllegalArgumentException ex) {
			// The reports stay where Log4j2 and Boot send them.
		}
	}

	// The listener Log4j2 calls, which is a proxy for the interface of the Log4j API.
	// A report points the console listeners at stderr, and the level is the lowest, so
	// this listener receives whatever a console listener receives, at whichever level
	// that one was registered. The watch lasts for the start of the log. Two listeners
	// are equal where they are the same object, which is how Log4j2 finds the one it
	// is asked to remove.
	@Override
	@SuppressWarnings("ReferenceEquality")
	public @Nullable Object invoke(Object proxy, Method method, @Nullable Object @Nullable [] arguments)
			throws Throwable {
		return switch (method.getName()) {
			case "log" -> {
				pointTheConsoleListenersAtStderrAndGoOn();
				yield null;
			}
			case "getStatusLevel" -> this.api.everyLevel;
			case "close" -> null;
			case "equals" -> arguments != null && proxy == arguments[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> "GATool's watch of the Log4j2 status logger";
			default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, arguments) : null;
		};
	}

	// Log4j2 calls its listeners on the thread that reports, so a failure here would
	// reach the code that logged.
	private void pointTheConsoleListenersAtStderrAndGoOn() {
		try {
			pointTheConsoleListenersAtStderr();
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			// The report goes where its listener writes.
		}
	}

	// Log4j2 leaves the stream of a listener as it is where the listener already
	// writes to the stream it is handed, so pointing it at stderr again costs a
	// comparison.
	private void pointTheConsoleListenersAtStderr() throws ReflectiveOperationException {
		for (Object listener : this.api.listeners()) {
			if (this.api.consoleListener.isInstance(listener) && !this.registeredAhead.contains(listener)) {
				this.api.setStream.invoke(listener, System.err);
			}
		}
	}

	private static @Nullable Log4j2StatusLog watchOf(Object listener) {
		if (Proxy.isProxyClass(listener.getClass())
				&& Proxy.getInvocationHandler(listener) instanceof Log4j2StatusLog watch) {
			return watch;
		}
		return null;
	}

	/**
	 * What this class reads of the Log4j API: the status logger, three of its methods,
	 * the two listener types, and the level below every other.
	 */
	// A class with one constructor that looks everything up, where a record would have
	// eight components filled by position, three of them methods of one type.
	private static final class Api {

		private final Object statusLogger;

		private final Method getListeners;

		private final Method registerListener;

		private final Method removeListener;

		private final Class<?> listener;

		private final Class<?> consoleListener;

		private final Method setStream;

		private final Object everyLevel;

		private Api(ClassLoader classLoader) throws ReflectiveOperationException {
			Class<?> statusLoggerType = ClassUtils.forName(STATUS_LOGGER, classLoader);
			this.listener = ClassUtils.forName(STATUS_LISTENER, classLoader);
			this.consoleListener = ClassUtils.forName(CONSOLE_LISTENER, classLoader);
			Object level = ClassUtils.forName(LEVEL, classLoader).getField("ALL").get(null);
			Object logger = statusLoggerType.getMethod("getLogger").invoke(null);
			if (logger == null || level == null) {
				throw new NoSuchFieldException("The Log4j API answered without its status logger or its levels");
			}
			this.statusLogger = logger;
			this.everyLevel = level;
			this.getListeners = statusLoggerType.getMethod("getListeners");
			this.registerListener = statusLoggerType.getMethod("registerListener", this.listener);
			this.removeListener = statusLoggerType.getMethod("removeListener", this.listener);
			this.setStream = this.consoleListener.getMethod("setStream", PrintStream.class);
		}

		static Api in(ClassLoader classLoader) throws ReflectiveOperationException {
			return new Api(classLoader);
		}

		// A copy, because the status logger hands out a view of its list, and removing
		// a listener while reading the view would change the list under the reader.
		List<Object> listeners() throws ReflectiveOperationException {
			List<Object> listeners = new ArrayList<>();
			if (this.getListeners.invoke(this.statusLogger) instanceof Iterable<?> registered) {
				registered.forEach(listeners::add);
			}
			return listeners;
		}

		void register(Object listener) throws ReflectiveOperationException {
			this.registerListener.invoke(this.statusLogger, listener);
		}

		void remove(Object listener) throws ReflectiveOperationException {
			this.removeListener.invoke(this.statusLogger, listener);
		}

	}

}
