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

package io.gatool.boot.internal.schema;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import io.gatool.boot.internal.execution.BoundedResponseRequestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SchemaUrlReaderTests {

	// A schema written in a language other than English carries characters outside
	// ASCII, and the registry answers text/plain without a charset, as Hive's CDN does.
	private static final String SDL = "\"Retourne les films les mieux notés.\"\ntype Query { films: [String!]! }\n";

	private static final DataSize TEN_MEGABYTES = DataSize.ofMegabytes(10);

	private static final String ETAG = "\"v1\"";

	private HttpServer registry;

	// Another origin, as a registry's storage host is.
	private HttpServer storage;

	private final Map<String, Headers> requests = new ConcurrentHashMap<>();

	@BeforeEach
	void startRegistry() throws IOException {
		this.registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.registry.createContext("/sdl", this::serveSdl);
		this.registry.createContext("/sdl-with-etag", (exchange) -> {
			this.requests.put(exchange.getRequestURI().getPath(), exchange.getRequestHeaders());
			if (ETAG.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
				exchange.sendResponseHeaders(304, -1);
				exchange.close();
				return;
			}
			exchange.getResponseHeaders().add("ETag", ETAG);
			byte[] body = SDL.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		this.registry.createContext("/redirect", (exchange) -> redirect(exchange, "/sdl"));
		this.registry.createContext("/relative", (exchange) -> redirect(exchange, "sdl"));
		this.registry.createContext("/loop", (exchange) -> redirect(exchange, "/loop"));
		this.registry.createContext("/hop", (exchange) -> {
			int hop = Integer.parseInt(exchange.getRequestURI().getPath().substring("/hop/".length()));
			redirect(exchange, (hop < 6) ? "/hop/" + (hop + 1) : "/sdl");
		});
		this.registry.createContext("/without-location", (exchange) -> {
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		this.storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.storage.createContext("/blob", this::serveSdl);
		this.registry.createContext("/redirect-away", (exchange) -> redirect(exchange,
				"http://127.0.0.1:" + this.storage.getAddress().getPort() + "/blob?signature=short-lived"));
		this.registry.start();
		this.storage.start();
	}

	@AfterEach
	void stopRegistry() {
		this.registry.stop(0);
		this.storage.stop(0);
	}

	@Test
	void read_registryRedirectingOnTheSameOrigin_shouldFollowItAndResendTheKey() {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		String text = reader.read(url("/redirect"), "X-Hive-CDN-Key", "the-key").text();

		assertThat(text).isEqualTo(SDL);
		assertThat(this.requests.get("/sdl").getFirst("X-Hive-CDN-Key")).isEqualTo("the-key");
	}

	@Test
	void read_registryRedirectingToAnotherOrigin_shouldFollowItWithoutTheKey() {
		// Hive's CDN answers 302 with a pre-signed URL on its storage host, which serves
		// the SDL without the key, and a key sent there would reach whatever host an
		// open redirect named.
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		String text = reader.read(url("/redirect-away"), "X-Hive-CDN-Key", "the-key").text();

		assertThat(text).isEqualTo(SDL);
		assertThat(this.requests.get("/redirect-away").getFirst("X-Hive-CDN-Key")).isEqualTo("the-key");
		assertThat(this.requests.get("/blob").getFirst("X-Hive-CDN-Key")).isNull();
	}

	@Test
	void read_registryRedirectingWithARelativeLocation_shouldResolveItAgainstTheUrl() {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		assertThat(reader.read(url("/relative"), null, null).text()).isEqualTo(SDL);
	}

	@Test
	void read_registryRedirectingInALoop_shouldStopNamingTheUrl() {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		assertThatExceptionOfType(SchemaUrlReader.SchemaFetchException.class)
			.isThrownBy(() -> reader.read(url("/loop"), null, null))
			.withMessageContaining("/loop")
			.withMessageContaining("the redirects loop");
	}

	@Test
	void read_registryRedirectingSixTimes_shouldStopAfterFiveHops() {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		assertThatExceptionOfType(SchemaUrlReader.SchemaFetchException.class)
			.isThrownBy(() -> reader.read(url("/hop/1"), null, null))
			.withMessageContaining("/hop/1")
			.withMessageContaining("more than five");
	}

	@Test
	void read_registryAnswering302WithoutALocation_shouldStopNamingTheStatus() {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		assertThatExceptionOfType(SchemaUrlReader.SchemaFetchException.class)
			.isThrownBy(() -> reader.read(url("/without-location"), null, null))
			.withMessageContaining("answered 302")
			.withMessageContaining("arrives with 200");
	}

	private void serveSdl(HttpExchange exchange) throws IOException {
		this.requests.put(exchange.getRequestURI().getPath(), exchange.getRequestHeaders());
		byte[] body = SDL.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/plain");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private void redirect(HttpExchange exchange, String location) throws IOException {
		this.requests.put(exchange.getRequestURI().getPath(), exchange.getRequestHeaders());
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	@Test
	void read_plainRestClientBuilder_shouldDecodeTheBodyAsUtf8() {
		// RestClient.builder() is the client the reader gets when Boot's builder bean is
		// absent, and its String converter decodes text/plain without a charset as
		// ISO-8859-1. The reader takes bytes and decodes them itself.
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		assertThat(reader.read(url(), null, null).text()).isEqualTo(SDL);
	}

	@Test
	void read_withACacheDirectory_shouldLeaveTheCopyAndNoStagingFileBehind(@TempDir Path cache) throws IOException {
		// Two readers on one directory, as two applications on one host share it. The
		// staging file takes a name of its own, so neither moves the other's half-written
		// text into place, and a finished write leaves the copy alone.
		SchemaUrlReader first = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, cache);
		SchemaUrlReader second = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, cache);

		first.read(url(), null, null).commit();
		second.read(url(), null, null).commit();

		try (Stream<Path> files = Files.list(cache)) {
			assertThat(files.map((file) -> file.getFileName().toString())).singleElement()
				.asString()
				.endsWith(".graphqls");
		}
	}

	@Test
	void read_withACacheDirectory_shouldWriteTheEtagInsideTheCopyAndSendItNextTime(@TempDir Path cache)
			throws IOException {
		// The SDL and its ETag land in one file through one rename, so two instances
		// sharing a directory cannot leave one instance's SDL beside the other's ETag.
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, cache);

		SchemaUrlReader.FetchedSchema first = reader.read(url("/sdl-with-etag"), null, null);
		first.commit();
		assertThat(first.text()).isEqualTo(SDL);
		assertThat(reader.read(url("/sdl-with-etag"), null, null).text()).isEqualTo(SDL);

		try (Stream<Path> files = Files.list(cache)) {
			assertThat(files).singleElement().satisfies((file) -> {
				assertThat(file.getFileName().toString()).endsWith(".graphqls");
				assertThat(Files.readString(file)).startsWith("# GATool schema cache; ETag: " + ETAG + "\n" + SDL);
			});
		}
		assertThat(this.requests.get("/sdl-with-etag").getFirst("If-None-Match")).isEqualTo(ETAG);
	}

	@Test
	void read_copyWithoutTheCacheMarker_shouldReadItAsAMissAndRewriteIt(@TempDir Path cache) throws IOException {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, cache);
		reader.read(url("/sdl-with-etag"), null, null).commit();
		Path copy;
		try (Stream<Path> files = Files.list(cache)) {
			copy = files.findFirst().orElseThrow();
		}
		// A file at the copy's path that lacks GATool's first line.
		Files.writeString(copy, SDL);

		SchemaUrlReader.FetchedSchema refetched = reader.read(url("/sdl-with-etag"), null, null);
		refetched.commit();

		assertThat(refetched.text()).isEqualTo(SDL);
		assertThat(this.requests.get("/sdl-with-etag").getFirst("If-None-Match")).isNull();
		try (Stream<Path> files = Files.list(cache)) {
			assertThat(files).singleElement().satisfies((file) -> {
				assertThat(file).isEqualTo(copy);
				assertThat(Files.readString(file)).startsWith("# GATool schema cache; ETag: ");
			});
		}
	}

	@Test
	void read_withACacheDirectory_shouldWriteTheCopyOnCommitAlone(@TempDir Path cache) throws IOException {
		// The copy is what a startup with the registry down runs on, so it is written
		// once the caller has validated every operation file against the text. A
		// fetched schema that breaks an operation then leaves the last copy that
		// validated in place.
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, cache);

		SchemaUrlReader.FetchedSchema fetched = reader.read(url(), null, null);

		assertThat(fetched.text()).isEqualTo(SDL);
		try (Stream<Path> files = Files.list(cache)) {
			assertThat(files).as("the cache before the commit").isEmpty();
		}
		fetched.commit();
		try (Stream<Path> files = Files.list(cache)) {
			assertThat(files.map((file) -> file.getFileName().toString())).singleElement()
				.asString()
				.endsWith(".graphqls");
		}
	}

	@Test
	void read_urlTheUriParserRefuses_shouldStopBlamingTheUrlOrTheHeaderWithoutThePassword() {
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder().build(), TEN_MEGABYTES, null);

		assertThatExceptionOfType(SchemaUrlReader.SchemaFetchException.class)
			.isThrownBy(() -> reader.read("http://user:s3cr|et@127.0.0.1:1/schema?key=QUERYKEY", null, null))
			.withMessageContaining("http://127.0.0.1:1/schema")
			.withMessageContaining("the URL or the header")
			.withMessageNotContaining("s3cr|et")
			.withMessageNotContaining("QUERYKEY");
	}

	@Test
	void read_bodyAboveTheCap_shouldStopNamingThePropertyAndTheLimit() {
		DataSize cap = DataSize.ofBytes(16);
		SchemaUrlReader reader = new SchemaUrlReader(RestClient.builder()
			.requestFactory(new BoundedResponseRequestFactory(new JdkClientHttpRequestFactory(), cap))
			.build(), cap, null);

		assertThatExceptionOfType(SchemaUrlReader.SchemaFetchException.class)
			.isThrownBy(() -> reader.read(url(), null, null))
			.withMessageContaining("/sdl")
			.withMessageContaining("more than 16 bytes")
			.withMessageContaining("gatool.api.schema.max-size");
	}

	@Test
	void sameOrigin_shouldCompareSchemeHostAndPortWithTheSchemesOwnPortWhereOneIsLeftOut() {
		assertThat(SchemaUrlReader.sameOrigin(URI.create("https://cdn.example.com/sdl"),
				URI.create("HTTPS://CDN.example.com:443/blob")))
			.isTrue();
		assertThat(SchemaUrlReader.sameOrigin(URI.create("http://cdn.example.com/sdl"),
				URI.create("https://cdn.example.com/blob")))
			.isFalse();
		assertThat(SchemaUrlReader.sameOrigin(URI.create("http://cdn.example.com/sdl"),
				URI.create("http://cdn.example.com:8080/blob")))
			.isFalse();
		assertThat(SchemaUrlReader.sameOrigin(URI.create("http://cdn.example.com/sdl"),
				URI.create("http://storage.example.com/blob")))
			.isFalse();
	}

	@Test
	void printable_urlWithUserinfoAndQuery_shouldKeepTheSchemeHostPortAndPathAlone() {
		assertThat(SchemaUrlReader.printable("https://user:secret@registry.example.com:8443/v1/sdl?token=abc#frag"))
			.isEqualTo("https://registry.example.com:8443/v1/sdl");
		assertThat(SchemaUrlReader.printable("http://127.0.0.1/sdl")).isEqualTo("http://127.0.0.1/sdl");
	}

	@Test
	void printable_urlThatDoesNotParse_shouldStillCutTheQueryAndTheUserinfo() {
		assertThat(SchemaUrlReader.printable("http://registry.example.com/a b?token=abc"))
			.isEqualTo("http://registry.example.com/a b");
		assertThat(SchemaUrlReader.printable("https://user:s3cr|et@registry.example.com/schema?key=abc"))
			.isEqualTo("https://registry.example.com/schema");
		// The last @ ahead of the path ends the userinfo, so a password holding one
		// goes with it.
		assertThat(SchemaUrlReader.printable("https://user:p@ss|w@registry.example.com:8443/schema?key=abc"))
			.isEqualTo("https://registry.example.com:8443/schema");
	}

	@Test
	void printable_hostTheUriParserRefuses_shouldKeepTheHostAndDropTheUserinfo() {
		// java.net.URI parses a host with an underscore and answers null for getHost(),
		// with the whole authority, userinfo included, in getRawAuthority().
		assertThat(SchemaUrlReader.printable("https://user:pw@my_host.example.com/schema"))
			.isEqualTo("https://my_host.example.com/schema");
		assertThat(SchemaUrlReader.printable("https://my_host.example.com:8443/schema?key=abc"))
			.isEqualTo("https://my_host.example.com:8443/schema");
	}

	private String url() {
		return url("/sdl");
	}

	private String url(String path) {
		return "http://127.0.0.1:" + this.registry.getAddress().getPort() + path;
	}

}
