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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema fetched from a URL, the way a schema registry serves it: a key in a header,
 * an ETag, and a copy beside the application for the startup where the registry is down.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchemaUrlTests {

	private static final String KEY = "cdn-key-that-stays-out-of-every-log";

	private static final String ETAG = "\"v1\"";

	// What the server does next: the SDL with an ETag, a 304 for a matching
	// If-None-Match, or a refusal.
	private static final AtomicInteger REFUSE_WITH = new AtomicInteger(0);

	// Further shapes a registry can answer with: no ETag at all, an empty body, a
	// 304 to a request without an ETag, or a schema holding characters outside ASCII.
	private static final AtomicReference<String> MODE = new AtomicReference<>("etag");

	// A description with characters outside ASCII, as a schema written in a language
	// other than English carries them. The test reads it back through a tool whose
	// operation file leaves the description to the schema.
	private static final String ACCENTED_DESCRIPTION = "Retourne les films les mieux notés, en tête d'abord.";

	// A schema of about two megabytes, which is the size GitLab's, PokeAPI's, GitHub's,
	// Linear's and Saleor's public schemas reach as SDL. The padding is GraphQL
	// comments, so the text stays a schema the operation files validate against.
	private static final String LARGE_SDL = MoviesSchema.sdl() + "\n" + ("# padding\n".repeat(200_000));

	private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>("");

	private static final AtomicReference<Headers> LAST_REQUEST = new AtomicReference<>(new Headers());

	private static final AtomicReference<String> LAST_PATH = new AtomicReference<>("");

	// What the storage host received, which is where a registry redirects a client to
	// for the SDL itself.
	private static final AtomicReference<Headers> STORAGE_REQUEST = new AtomicReference<>(new Headers());

	private static HttpServer server;

	private static HttpServer storage;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", "gatool.api.schema.header-name=X-Hive-CDN-Key",
				"gatool.api.schema.header-value=" + KEY);

	@BeforeAll
	static void startRegistry() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/sdl", (exchange) -> {
			LAST_REQUEST.set(exchange.getRequestHeaders());
			LAST_PATH.set(exchange.getRequestURI().getPath());
			LAST_QUERY.set(String.valueOf(exchange.getRequestURI().getQuery()));
			int refusal = REFUSE_WITH.get();
			if (refusal != 0) {
				exchange.sendResponseHeaders(refusal, -1);
				exchange.close();
				return;
			}
			String mode = MODE.get();
			if ("not-modified".equals(mode) || ETAG.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
				exchange.sendResponseHeaders(304, -1);
				exchange.close();
				return;
			}
			String sdl = switch (mode) {
				case "accented" ->
					MoviesSchema.sdl().replace("Returns the highest-rated movies, best first.", ACCENTED_DESCRIPTION);
				case "large" -> LARGE_SDL;
				default -> MoviesSchema.sdl();
			};
			byte[] body = "empty".equals(mode) ? new byte[0] : sdl.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			if (!"no-etag".equals(mode)) {
				exchange.getResponseHeaders().add("ETag", ETAG);
			}
			exchange.sendResponseHeaders(200, (body.length != 0) ? body.length : -1);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		// Hive's CDN answers with a redirect to its storage, so a redirecting path
		// stands in for it.
		server.createContext("/redirect", (exchange) -> {
			exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/sdl");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		// The storage is another origin, as Hive's is: its CDN answers 302 with a
		// pre-signed URL on its storage host, which serves the SDL without the key.
		storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		storage.createContext("/blob", (exchange) -> {
			STORAGE_REQUEST.set(exchange.getRequestHeaders());
			byte[] body = MoviesSchema.sdl().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		storage.start();
		server.createContext("/redirect-away", (exchange) -> {
			LAST_REQUEST.set(exchange.getRequestHeaders());
			exchange.getResponseHeaders()
				.add("Location", "http://127.0.0.1:" + storage.getAddress().getPort() + "/blob?signature=short-lived");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopRegistry() {
		server.stop(0);
		storage.stop(0);
	}

	@BeforeEach
	void serveTheSchema() {
		REFUSE_WITH.set(0);
		MODE.set("etag");
	}

	@Test
	void startup_registryRefusingTheKeyWithACopyOnDisk_shouldStillStop(@TempDir Path cache) {
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());

		// A 4xx is a setting to fix, and the copy on disk must not hide it.
		REFUSE_WITH.set(401);
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("answered 401");
		});
		assertThat(filesIn(cache)).anyMatch((name) -> name.endsWith(".graphqls"));
	}

	@Test
	void startup_registryRedirecting_shouldFollowItWithTheKey(@TempDir Path cache) {
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			// The JDK client follows the redirect with every header but the ones it
			// strips, and the registry key is one it keeps.
			assertThat(LAST_PATH.get()).isEqualTo("/sdl");
			assertThat(LAST_REQUEST.get().getFirst("X-Hive-CDN-Key")).isEqualTo(KEY);
		});
	}

	@Test
	void startup_registryRedirectingToAnotherOrigin_shouldFollowItWithoutTheKey(@TempDir Path cache) {
		// The JDK copies every header but Authorization, Cookie, Origin, Referer and Host
		// to a redirect target on another origin, so the registry key would travel to
		// whatever host the Location names. GATool follows the redirect itself and sends
		// the key to the configured origin alone.
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-away";
		STORAGE_REQUEST.set(new Headers());

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
			assertThat(LAST_REQUEST.get().getFirst("X-Hive-CDN-Key")).isEqualTo(KEY);
			assertThat(STORAGE_REQUEST.get().getFirst("X-Hive-CDN-Key")).isNull();
		});
	}

	@Test
	void startup_registryRedirectingWithAuthorizationAsTheKeyHeader_shouldResendTheKeyOnTheSameOrigin(
			@TempDir Path cache) {
		// GATool sets the header on each hop it follows, so the rule is its own whatever
		// the HTTP client strips on a redirect.
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", "gatool.api.schema.location=" + url,
					cacheIn(cache), "gatool.api.schema.header-name=Authorization",
					"gatool.api.schema.header-value=Bearer " + KEY)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(LAST_PATH.get()).isEqualTo("/sdl");
				assertThat(LAST_REQUEST.get().getFirst("Authorization")).isEqualTo("Bearer " + KEY);
			});
	}

	@Test
	void startup_registryRedirectingWhileTheApplicationsRedirectsAreOff_shouldStillFollowIt(@TempDir Path cache) {
		// The schema request follows redirects under GATool's own rule, up to five hops
		// with the key sent to the configured origin alone, so the application's
		// spring.http.clients.redirects setting leaves this request as it is.
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

		this.contextRunner
			.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache),
					"spring.http.clients.redirects=dont-follow")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(LAST_PATH.get()).isEqualTo("/sdl");
				assertThat(LAST_REQUEST.get().getFirst("X-Hive-CDN-Key")).isEqualTo(KEY);
			});
	}

	@Test
	void startup_schemaLargerThanTheSchemaBound_shouldStopNamingTheProperty(@TempDir Path cache) {
		// Every tool call reads under gatool.api.max-response-size, and
		// gatool.api.schema.max-size bounds this request, so a registry answering a page
		// of markup, or a schema larger than the bound, stops startup naming that
		// property.
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache), "gatool.api.schema.max-size=1KB")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("/sdl")
					.hasMessageContaining("gatool.api.schema.max-size")
					.hasMessageContaining("1KB");
			});
	}

	@Test
	void startup_schemaLargerThanTheResponseCap_shouldStillLoadUnderTheSchemaBound(@TempDir Path cache) {
		// The schema has a bound of its own, ten megabytes by default, so an application
		// whose schema is larger than the response cap keeps the cap for every call: this
		// two megabyte schema loads while the response cap stays at one.
		MODE.set("large");

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
		});
	}

	@Test
	void startup_schemaUrlWithoutDeadlines_shouldWarnNamingTheSchemaUrl(@TempDir Path cache, CapturedOutput output) {
		// The deadline warning runs whether or not gatool.api.url is set, so an
		// application in embedded mode fetching its schema from a registry reads about a
		// fetch that a silent registry holds open until the socket closes.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withBean(GraphQlExecutor.class, () -> (request) -> {
				throw new UnsupportedOperationException("this test reads the schema alone");
			})
			.withPropertyValues(schemaAt(server), cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("the schema at http://127.0.0.1:" + server.getAddress().getPort() + "/sdl")
			.contains("spring.http.clients.connect-timeout and spring.http.clients.read-timeout are unset");
	}

	@Test
	void startup_blankCacheDirectory_shouldFetchEveryTimeAndWriteNothing(@TempDir Path cache) {
		this.contextRunner.withPropertyValues(schemaAt(server), "gatool.api.schema.cache-directory=")
			.run((context) -> assertThat(context).hasNotFailed());
		assertThat(filesIn(cache)).isEmpty();

		REFUSE_WITH.set(503);
		this.contextRunner.withPropertyValues(schemaAt(server), "gatool.api.schema.cache-directory=").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("answered 503");
		});
	}

	@Test
	void startup_registryWithoutAnEtag_shouldCacheTheSdlAloneAndRefetchNextTime(@TempDir Path cache) {
		MODE.set("no-etag");

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());
		assertThat(filesIn(cache)).anyMatch((name) -> name.endsWith(".graphqls"))
			.noneMatch((name) -> name.endsWith(".etag"));

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(LAST_REQUEST.get().getFirst("If-None-Match")).isNull();
		});
	}

	@Test
	void startup_registryAnswering304WithoutACopy_shouldStopNamingTheMissingCopy(@TempDir Path cache) {
		MODE.set("not-modified");

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("without an ETag");
		});
	}

	@Test
	void startup_registryAnsweringAnEmptyBody_shouldStopNamingTheEmptyBody(@TempDir Path cache) {
		MODE.set("empty");

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("empty body");
		});
	}

	@Test
	void startup_headerValueWithoutAName_shouldStopNamingTheProperty(@TempDir Path cache) {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", schemaAt(server), cacheIn(cache),
					"gatool.api.schema.header-value=" + KEY)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("gatool.api.schema.header-name");
			});
	}

	@Test
	void startup_schemaAtAUrl_shouldFetchItWithTheHeaderAndCacheItWithItsEtag(@TempDir Path cache,
			CapturedOutput output) throws IOException {
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
			assertThat(LAST_REQUEST.get().getFirst("X-Hive-CDN-Key")).isEqualTo(KEY);
		});
		// The ETag travels inside the copy, on its first line, so the copy and its ETag
		// land through one rename.
		assertThat(filesIn(cache)).singleElement().satisfies((name) -> {
			assertThat(name).endsWith(".graphqls");
			assertThat(Files.readString(cache.resolve(name))).startsWith("# GATool schema cache; ETag: " + ETAG);
		});
		// The key is a credential, and the startup log is read by more people than hold
		// it.
		assertThat(output.getAll()).contains("validates every operation file against the schema at http://")
			.doesNotContain(KEY);
	}

	@Test
	void startup_secondTime_shouldSendIfNoneMatchAndReuseTheCopyOnA304(@TempDir Path cache, CapturedOutput output) {
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
			assertThat(LAST_REQUEST.get().getFirst("If-None-Match")).isEqualTo(ETAG);
		});
		assertThat(output.getAll()).contains("confirmed unchanged");
	}

	@Test
	void startup_registryAnswering503WithACopyOnDisk_shouldStartOnTheCopyAndWarn(@TempDir Path cache,
			CapturedOutput output) {
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());

		// A gateway in front of a registry that is down answers 503, which the copy is
		// for.
		REFUSE_WITH.set(503);
		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
		});
		assertThat(output.getAll()).contains("answered 503").contains("starts on the copy cached at");
	}

	@Test
	void startup_registryAnswering503WithoutACopy_shouldStopNamingTheStatus(@TempDir Path cache) {
		REFUSE_WITH.set(503);

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("answered 503");
		});
	}

	@Test
	void startup_connectionRefusedWithACopyOnDisk_shouldStartOnTheCopy(@TempDir Path cache, CapturedOutput output)
			throws Exception {
		// A copy for the URL that is about to be unreachable, written the way the
		// reader writes it, since the registry at that address is gone by then.
		HttpServer once = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		once.createContext("/sdl", (exchange) -> {
			byte[] body = MoviesSchema.sdl().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("ETag", ETAG);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		once.start();
		String url = "http://127.0.0.1:" + once.getAddress().getPort() + "/sdl";
		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());
		once.stop(0);

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
		});
		assertThat(output.getAll()).contains("could not reach the schema at " + url)
			.contains("starts on the copy cached at")
			.contains("less than a minute ago");
	}

	@Test
	void startup_connectionRefusedWithoutACopy_shouldStopNamingTheUrl(@TempDir Path cache) {
		String url = "http://127.0.0.1:1/sdl";

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			// The connection failure stays the root cause, and the property message sits
			// above it, so the chain is searched from the top.
			assertThat(context.getStartupFailure()).hasStackTraceContaining(url)
				.hasStackTraceContaining("Nothing is cached yet");
		});
	}

	@Test
	void startup_registryRefusingTheKey_shouldStopNamingTheUrlAndTheStatus(@TempDir Path cache) {
		REFUSE_WITH.set(401);

		this.contextRunner.withPropertyValues(schemaAt(server), cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("/sdl")
				.hasMessageContaining("answered 401");
		});
	}

	@Test
	void startup_headerNameWithoutAValue_shouldStopNamingTheProperty(@TempDir Path cache) {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", schemaAt(server), cacheIn(cache),
					"gatool.api.schema.header-name=X-Hive-CDN-Key")
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("gatool.api.schema.header-value");
			});
	}

	@Test
	void startup_headerValueEndingInALineBreak_shouldStopNamingThePropertyAndKeepTheValueOut(@TempDir Path cache,
			CapturedOutput output) {
		// TestPropertyValues trims a value, so the line break arrives through a property
		// source of its own, the way a secret file's content reaches an environment.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", schemaAt(server), cacheIn(cache),
					"gatool.api.schema.header-name=X-Hive-CDN-Key")
			.withInitializer((context) -> context.getEnvironment()
				.getPropertySources()
				.addFirst(new MapPropertySource("secret", Map.of("gatool.api.schema.header-value", KEY + "\n"))))
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("gatool.api.schema.header-value")
					.hasMessageContaining("line break");
			});
		// The JDK would have named the whole value in its own message.
		assertThat(output.getAll()).doesNotContain(KEY);
	}

	@Test
	void startup_schemaOutsideAsciiWithoutBootsRestClientBuilder_shouldDecodeItAsUtf8(@TempDir Path cache) {
		// Without Boot's builder bean the reader falls back to RestClient.builder(),
		// whose String converter decodes text/plain without a charset as ISO-8859-1. A
		// GraphQL document is UTF-8, so the body is read as bytes and decoded as such,
		// and the description reaches the tool as the schema wrote it. The application's
		// own executor bean stands in for the remote one, which needs Boot's builder
		// itself.
		MODE.set("accented");

		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withBean(GraphQlExecutor.class, () -> (request) -> {
				throw new UnsupportedOperationException("this test reads the schema alone");
			})
			.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", schemaAt(server), cacheIn(cache))
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).singleElement()
					.satisfies((tool) -> assertThat(tool.description()).isEqualTo(ACCENTED_DESCRIPTION));
			});
	}

	@Test
	void startup_urlCarryingAKeyInItsQuery_shouldKeepTheQueryOutOfEveryLogLine(@TempDir Path cache,
			CapturedOutput output) {
		// A registry key can travel in the query as well as in a header, so every line
		// prints the scheme, the host, the port and the path alone.
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sdl?token=query-key-that-stays-out";

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache)).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(LAST_QUERY.get()).isEqualTo("token=query-key-that-stays-out");
		});
		// The second start confirms the copy through a 304, which is the other line that
		// names the URL.
		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("validates every operation file against the schema at http://127.0.0.1:"
					+ server.getAddress().getPort() + "/sdl.")
			.contains("confirmed unchanged")
			.doesNotContain("query-key-that-stays-out");
	}

	@Test
	void startup_urlCarryingAKeyInItsQueryAndRefused_shouldKeepTheQueryOutOfTheFailure(@TempDir Path cache,
			CapturedOutput output) {
		REFUSE_WITH.set(401);
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/sdl?token=query-key-that-stays-out";

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + url, cacheIn(cache)).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("/sdl")
				.hasMessageContaining("answered 401")
				.hasMessageNotContaining("query-key-that-stays-out");
		});
		assertThat(output.getAll()).doesNotContain("query-key-that-stays-out");
	}

	@Test
	void startup_headerBesideAClasspathLocation_shouldWarnThatItGoesUnsent(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("the header goes unsent");
	}

	private static String schemaAt(HttpServer registry) {
		return "gatool.api.schema.location=http://127.0.0.1:" + registry.getAddress().getPort() + "/sdl";
	}

	private static String cacheIn(Path cache) {
		return "gatool.api.schema.cache-directory=" + cache.toAbsolutePath();
	}

	private static Stream<String> filesIn(Path cache) {
		try (Stream<Path> files = Files.list(cache)) {
			return files.map((file) -> file.getFileName().toString()).toList().stream();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
