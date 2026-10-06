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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GATool builds its client from Boot's {@code RestClient.Builder}, so the headers and
 * interceptors a {@code RestClientCustomizer} sets reach every request it sends, for the
 * tool calls and for the schema fetch alike. A request factory the customizer installs
 * stays off GATool's client, because GATool builds its own factory from the
 * {@code ClientHttpRequestFactoryBuilder} bean and {@code spring.http.clients.*}, and
 * startup says so in one INFO line naming the customizer beans.
 *
 * <p>
 * The customizer's factory records every request it creates. The control is a client the
 * application builds from the same Boot builder, which goes through it.
 */
@ExtendWith(OutputCaptureExtension.class)
class RestClientCustomizerTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final List<String> API_HEADERS = new CopyOnWriteArrayList<>();

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withUserConfiguration(CorpClientConfiguration.class)
		.withPropertyValues(SCHEMA);

	@BeforeAll
	static void startApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			API_HEADERS.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Corp-Client")));
			byte[] body = "{\"data\":{\"topRatedMovies\":[]}}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/sdl", (exchange) -> {
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
	static void stopApi() {
		server.stop(0);
	}

	@Test
	void restClient_builtByTheApplicationFromBootsBuilder_shouldGoThroughTheCustomizersFactory() {
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			RecordingRequestFactory factory = context.getBean(RecordingRequestFactory.class);
			factory.requests.clear();
			RestClient client = context.getBean(RestClient.Builder.class).build();

			client.post()
				.uri(apiUrl())
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"query\":\"{ __typename }\"}")
				.retrieve()
				.toBodilessEntity();

			assertThat(factory.requests).contains("POST " + apiUrl());
		});
	}

	@Test
	void call_gatoolsClient_shouldKeepTheCustomizersHeaderBypassItsFactoryAndSaySoAtStartup(CapturedOutput output) {
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			RecordingRequestFactory factory = context.getBean(RecordingRequestFactory.class);
			factory.requests.clear();
			API_HEADERS.clear();
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

			ToolCallOutcome outcome = tool.call(Map.of());

			String line = output.getAll()
				.lines()
				.filter((candidate) -> candidate.contains("RestClientCustomizer"))
				.findFirst()
				.orElse("");
			assertThat(outcome.isError()).isFalse();
			assertThat(API_HEADERS).as("the customizer's default header still reaches the API").contains("yes");
			assertThat(factory.requests).as("requests GATool's client sent through the customizer's factory").isEmpty();
			assertThat(line).as("the startup line naming the customizer beans")
				.contains("INFO")
				.contains("corpCustomizer")
				.contains("request factory")
				.contains("ClientHttpRequestFactoryBuilder");
		});
	}

	@Test
	void startup_schemaFetch_shouldBypassTheCustomizersFactoryAsWell() {
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql", "gatool.api.schema.location=" + sdlUrl(),
					"gatool.api.schema.cache-directory=")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				RecordingRequestFactory factory = context.getBean(RecordingRequestFactory.class);

				assertThat(factory.requests).noneMatch((request) -> request.endsWith("/sdl"));
			});
	}

	private static String apiUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

	private static String sdlUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/sdl";
	}

	private static String apiUrlProperty() {
		return "gatool.api.url=" + apiUrl();
	}

	/**
	 * A request factory that records what it is asked for, standing in for a proxy, an
	 * mTLS context or a pooled client an enterprise installs this way.
	 */
	static final class RecordingRequestFactory implements ClientHttpRequestFactory {

		final List<String> requests = new CopyOnWriteArrayList<>();

		private final ClientHttpRequestFactory delegate = new SimpleClientHttpRequestFactory();

		@Override
		public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
			this.requests.add(httpMethod + " " + uri);
			return this.delegate.createRequest(uri, httpMethod);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CorpClientConfiguration {

		@Bean
		RecordingRequestFactory corpRequestFactory() {
			return new RecordingRequestFactory();
		}

		@Bean
		RestClientCustomizer corpCustomizer(RecordingRequestFactory factory) {
			return (builder) -> builder.requestFactory(factory).defaultHeader("X-Corp-Client", "yes");
		}

	}

}
