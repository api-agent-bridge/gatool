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
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A password in the userinfo of {@code gatool.api.schema.location} stays out of the
 * startup failure and out of the cached-copy warning, on both paths a registry that
 * cannot be reached takes. Spring's {@code ResourceAccessException} names the URL it
 * tried, userinfo included, and the reader scrubs that reason before it reaches either
 * message. The query stays out as well, as the README promises, because a registry key
 * can travel there.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchemaUrlCredentialLeakTests {

	private static final String PASSWORD = "S3cretPassw0rd";

	private static final String QUERY_KEY = "QUERYKEY";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql");

	@Test
	void startup_registryUnreachableAndNothingCached_shouldKeepThePasswordAndTheQueryOutOfTheFailure(
			@TempDir Path cache) {
		String location = "http://registryuser:" + PASSWORD + "@127.0.0.1:1/sdl?key=" + QUERY_KEY;

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + location, cacheIn(cache))
			.run((context) -> {
				assertThat(context).hasFailed();
				String messages = messagesOf(context.getStartupFailure());

				assertThat(messages).contains("could not be fetched")
					.doesNotContain(QUERY_KEY)
					.doesNotContain(PASSWORD);
			});
	}

	@Test
	void startup_registryUnreachableWithACachedCopy_shouldKeepThePasswordAndTheQueryOutOfTheWarning(@TempDir Path cache,
			CapturedOutput output) throws IOException {
		HttpServer registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		registry.createContext("/sdl", (exchange) -> {
			byte[] body = MoviesSchema.sdl().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/plain");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		registry.start();
		String location = "http://registryuser:" + PASSWORD + "@127.0.0.1:" + registry.getAddress().getPort()
				+ "/sdl?key=" + QUERY_KEY;
		try {
			this.contextRunner.withPropertyValues("gatool.api.schema.location=" + location, cacheIn(cache))
				.run((context) -> assertThat(context).hasNotFailed());
		}
		finally {
			registry.stop(0);
		}

		this.contextRunner.withPropertyValues("gatool.api.schema.location=" + location, cacheIn(cache))
			.run((context) -> assertThat(context).hasNotFailed());

		String warning = output.getAll()
			.lines()
			.filter((line) -> line.contains("starts on the copy cached at"))
			.findFirst()
			.orElse("");
		assertThat(warning).contains("WARN").doesNotContain(QUERY_KEY).doesNotContain(PASSWORD);
	}

	private static String messagesOf(Throwable failure) {
		List<String> messages = new ArrayList<>();
		for (Throwable cause = failure; cause != null && messages.size() < 10; cause = cause.getCause()) {
			messages.add(cause.getClass().getSimpleName() + ": " + cause.getMessage());
		}
		return String.join(" <- ", messages);
	}

	private static String cacheIn(Path cache) {
		return "gatool.api.schema.cache-directory=" + cache.toAbsolutePath();
	}

}
