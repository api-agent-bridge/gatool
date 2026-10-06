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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the GraphQL API receives when the model sends an explicit {@code null}.
 *
 * <p>
 * GraphQL section 6.1.2 separates a variable set to {@code null} from a variable left
 * out: the first clears a value and the second applies the default the operation
 * declares. {@code NullArguments} keeps that separation, and the outbound JSON is where
 * it can be lost, because Boot applies {@code spring.jackson.default-property-inclusion}
 * to content inclusion as well as value inclusion and content inclusion governs a null
 * map value.
 *
 * <p>
 * Jackson keeps a null map value by default, so both cases below run with the property
 * set, which is the only way an application meets this.
 */
class ExplicitNullVariableTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final AtomicReference<String> BODY = new AtomicReference<>("");

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		// HttpMessageConvertersAutoConfiguration is what puts Boot's JsonMapper into
		// the converter the RestClient writes with, so the property below reaches the
		// outbound body the way it does in an application.
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
				HttpMessageConvertersAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
		.withPropertyValues(SCHEMA, "gatool.mcp.operations.locations=classpath*:gatool/inputs/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/");

	@BeforeAll
	static void startRecordingApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			try (InputStream in = exchange.getRequestBody()) {
				BODY.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			byte[] answer = "{\"data\":{\"movies\":{\"edges\":[]}}}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/graphql-response+json");
			exchange.sendResponseHeaders(200, answer.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(answer);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopRecordingApi() {
		server.stop(0);
	}

	@Test
	void call_nullVariableWithJacksonSetToNonNull_shouldStillSendTheNullToTheApi() {
		callWithNullFilter();

		// Without GATool's own converter the body arrives as "variables":{}, and the API
		// reads the same call as one that asked for every movie.
		assertThat(BODY.get()).contains("\"filter\":null");
	}

	@Test
	void call_nullForAVariableWithoutADefault_shouldStillReadTheApisAnswer() {
		// The converter GATool puts in place of Boot's carries the media types of the one
		// it replaces, so application/graphql-response+json still parses.
		assertThat(callWithNullFilter()).contains("\"data\"").doesNotContain("could not reach");
	}

	private String callWithNullFilter() {
		AtomicReference<String> text = new AtomicReference<>("");
		this.contextRunner
			.withPropertyValues("spring.jackson.default-property-inclusion=non_null",
					"gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
				text.set(tool.call(Collections.singletonMap("filter", null)).text());
			});
		return text.get();
	}

}
