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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpCookieHandling;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.internal.execution.RemoteHttpClients;
import io.gatool.core.model.GATool;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * and whether a GATool application left at its defaults bounds the wait for a GraphQL
 * API, and for the schema registry it fetches the schema from.
 *
 * <p>
 * Each test points GATool at a local server that accepts the connection and leaves the
 * request unanswered, which is the shape of an API that has stopped responding. The work
 * runs on a thread of its own and the test waits five seconds for it, so a run without a
 * deadline ends at the bound and the build carries on. A test that ends at the bound
 * reports the deadline as missing.
 */
class ApiAndSchemaFetchDeadlineTests {

	private static final String CLASSPATH_SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	// How long a test waits for a call or a startup that should have given up by
	// itself. It stays well under the backstops below, so a test that ends at this
	// bound has observed an unbounded wait.
	private static final long BOUND_SECONDS = 5;

	// Released when the class finishes, so a parked handler cannot hold the build
	// open. The backstop bounds a handler the latch somehow misses.
	private static final CountDownLatch RELEASED = new CountDownLatch(1);

	private static final long BACKSTOP_SECONDS = 12;

	// The registry serves the schema once, so the first startup writes the copy on
	// disk that is about, and goes silent for every request after it.
	private static final AtomicBoolean SCHEMA_SERVED = new AtomicBoolean();

	private static HttpServer server;

	private static ExecutorService handlers;

	@TempDir
	static Path cacheDirectory;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@BeforeAll
	static void startSilentServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// A handler of its own per request, because the default executor runs them one
		// after another and a parked handler would then hold up the next test's request.
		handlers = Executors.newCachedThreadPool((runnable) -> {
			Thread thread = new Thread(runnable, "silent-stub-api");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(handlers);
		server.createContext("/graphql", (exchange) -> park());
		server.createContext("/sdl", (exchange) -> {
			if (!SCHEMA_SERVED.compareAndSet(false, true)) {
				park();
				return;
			}
			byte[] body = MoviesSchema.sdl().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopSilentServer() {
		RELEASED.countDown();
		server.stop(0);
		handlers.shutdownNow();
	}

	private static void park() {
		try {
			RELEASED.await(BACKSTOP_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void deadlines_withNeitherSpringPropertySet_shouldBeGAToolsOwn() {
		// Spring Boot does not declare a default for either property, and an unanswered
		// call would then hold its thread until the socket closes. GATool fills both.
		this.contextRunner.withPropertyValues(apiUrlProperty(), CLASSPATH_SCHEMA).run((context) -> {
			RemoteHttpClients clients = context.getBean(RemoteHttpClients.class);

			assertThat(clients.appliedConnectTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_CONNECT_TIMEOUT);
			assertThat(clients.appliedReadTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_READ_TIMEOUT);
		});
	}

	@Test
	void deadlines_withTheSpringPropertiesSet_shouldBeTheApplications() {
		// A value on either Spring property decides, so GATool's own deadline applies
		// only to a property the application left unset.
		this.contextRunner
			.withPropertyValues(apiUrlProperty(), CLASSPATH_SCHEMA, "spring.http.clients.connect-timeout=7s",
					"spring.http.clients.read-timeout=11s")
			.run((context) -> {
				RemoteHttpClients clients = context.getBean(RemoteHttpClients.class);

				assertThat(clients.appliedConnectTimeout()).isEqualTo(Duration.ofSeconds(7));
				assertThat(clients.appliedReadTimeout()).isEqualTo(Duration.ofSeconds(11));
			});
	}

	@Test
	void deadlines_withOneSpringPropertySet_shouldMixTheApplicationsValueWithGAToolsOwn() {
		this.contextRunner
			.withPropertyValues(apiUrlProperty(), CLASSPATH_SCHEMA, "spring.http.clients.read-timeout=11s")
			.run((context) -> {
				RemoteHttpClients clients = context.getBean(RemoteHttpClients.class);

				assertThat(clients.appliedConnectTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_CONNECT_TIMEOUT);
				assertThat(clients.appliedReadTimeout()).isEqualTo(Duration.ofSeconds(11));
			});
	}

	@Test
	void deadlinedSettings_withAnApplicationThatSetOtherFields_shouldLeaveThoseFieldsAlone() {
		// The merge fills the two deadlines alone, so a cookie policy or an SSL bundle
		// the application configured survives. Building from
		// HttpClientSettings.defaults() would cost an application its mutual TLS here.
		this.contextRunner
			.withPropertyValues(apiUrlProperty(), CLASSPATH_SCHEMA, "spring.http.clients.cookie-handling=enable")
			.run((context) -> {
				HttpClientSettings applied = context.getBean(RemoteHttpClients.class).deadlinedSettings();

				assertThat(applied.cookieHandling()).isEqualTo(HttpCookieHandling.ENABLE);
				assertThat(applied.connectTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_CONNECT_TIMEOUT);
			});
	}

	@Test
	void call_apiThatStopsAnsweringAndAReadTimeoutConfigured_shouldGiveUpWithinTheBound() {
		// The control. Spring's read timeout does fire against this server, which rules
		// the stub out as the reason the test above waits.
		assertThat(callEndedWithinTheBound(apiUrlProperty(), "spring.http.clients.read-timeout=1s"))
			.as("a tool call ends within " + BOUND_SECONDS + " seconds when the read timeout is one second")
			.isTrue();
	}

	@Test
	void call_apiThatStopsAnsweringAQuery_shouldNameTheReadTimeoutAndAskForLess() {
		// The API received the request and was still working when GATool stopped waiting,
		// so the same call is likely to take as long again.
		AtomicReference<Throwable> failure = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty(), CLASSPATH_SCHEMA, "spring.http.clients.read-timeout=1s")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
				failure.set(catchThrowable(() -> tool.call(Map.of())));
			});

		assertThat(failure.get()).isInstanceOf(ToolCallFailedException.class)
			.hasMessage("The tool topRatedMovies called the GraphQL API, and the API took longer to answer than "
					+ "the read timeout of 1 second, which spring.http.clients.read-timeout sets. The same call "
					+ "is likely to take as long again, so ask for less: fewer fields, or a smaller page with "
					+ "first.");
	}

	@Test
	void startup_schemaRegistryThatGoesSilentWithACopyOnDisk_shouldStartOnTheCopyWithinTheBound()
			throws InterruptedException {
		String schemaUrl = "gatool.api.schema.location=http://127.0.0.1:" + server.getAddress().getPort() + "/sdl";
		String cache = "gatool.api.schema.cache-directory=" + cacheDirectory.toAbsolutePath();

		// The first startup fetches the schema and writes the copy the second one
		// falls back to.
		this.contextRunner.withPropertyValues(apiUrlProperty(), schemaUrl, cache)
			.run((context) -> assertThat(context).hasNotFailed());

		CountDownLatch ended = new CountDownLatch(1);
		Thread starter = new Thread(() -> {
			try {
				this.contextRunner
					.withPropertyValues(apiUrlProperty(), schemaUrl, cache, "spring.http.clients.read-timeout=1s")
					.run((context) -> {
					});
			}
			finally {
				ended.countDown();
			}
		});
		starter.setDaemon(true);
		starter.start();

		assertThat(ended.await(BOUND_SECONDS, TimeUnit.SECONDS))
			.as("startup against a registry that goes silent ends within " + BOUND_SECONDS
					+ " seconds, on the copy cached by the startup before it")
			.isTrue();
	}

	private boolean callEndedWithinTheBound(String... properties) {
		CountDownLatch ended = new CountDownLatch(1);
		AtomicBoolean endedInTime = new AtomicBoolean();
		this.contextRunner.withPropertyValues(CLASSPATH_SCHEMA).withPropertyValues(properties).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			Thread caller = new Thread(() -> {
				try {
					tool.call(Map.of());
				}
				catch (RuntimeException ex) {
					// A call that fails has ended, which is what the latch reports.
				}
				finally {
					ended.countDown();
				}
			});
			caller.setDaemon(true);
			caller.start();
			// The context stays open while the caller runs, so the wait happens here.
			endedInTime.set(ended.await(BOUND_SECONDS, TimeUnit.SECONDS));
		});
		return endedInTime.get();
	}

	private static String apiUrlProperty() {
		return "gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

}
