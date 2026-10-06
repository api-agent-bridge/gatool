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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code gatool.call.outcome} tag of a call the API refused, read from the
 * observation of the MCP tool specification GATool publishes.
 *
 * <p>
 * The outcome carries its kind and the tag reads it, so a dashboard tells a 401, a 5xx
 * and a result the size cap held back apart from {@code graphql-errors}.
 */
class McpObservationOutcomeTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final ResolvableType STATELESS_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpStatelessServerFeatures.SyncToolSpecification.class);

	private static final AtomicInteger STATUS = new AtomicInteger(401);

	private static final AtomicReference<String> BODY = new AtomicReference<>("{\"message\":\"invalid token\"}");

	private static final List<Observation.Context> RECORDED = new CopyOnWriteArrayList<>();

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withBean(ObservationRegistry.class, McpObservationOutcomeTests::recordingRegistry)
		.withPropertyValues(SCHEMA, "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/");

	@BeforeAll
	static void startRefusingApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			byte[] body = BODY.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(STATUS.get(), (body.length != 0) ? body.length : -1);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopRefusingApi() {
		server.stop(0);
	}

	@Test
	void callTool_apiAnswers401_shouldRecordTheApiRefusedOutcome() {
		STATUS.set(401);
		BODY.set("{\"message\":\"invalid token\"}");

		McpSchema.CallToolResult result = callTool();

		assertThat(result.isError()).isTrue();
		assertThat(tags(toolCall())).contains(KeyValue.of("gatool.call.outcome", "api-refused"));
	}

	@Test
	void callTool_apiAnswersWithGraphQlErrors_shouldStillRecordTheGraphQlErrorsOutcome() {
		STATUS.set(200);
		BODY.set("{\"data\":null,\"errors\":[{\"message\":\"Validation error\"}]}");

		McpSchema.CallToolResult result = callTool();

		assertThat(result.isError()).isTrue();
		assertThat(tags(toolCall())).contains(KeyValue.of("gatool.call.outcome", "graphql-errors"));
	}

	@Test
	void callTool_resultAboveMaxCharacters_shouldRecordTheResultTooLargeOutcome() {
		STATUS.set(200);
		BODY.set("{\"data\":{\"topRatedMovies\":[{\"id\":\"m1\",\"title\":\"Signal from Kepler\"}]}}");

		McpSchema.CallToolResult result = callTool("gatool.results.max-characters=10");

		assertThat(result.isError()).isTrue();
		assertThat(tags(toolCall())).contains(KeyValue.of("gatool.call.outcome", "result-too-large"));
	}

	private McpSchema.CallToolResult callTool(String... properties) {
		RECORDED.clear();
		AtomicReference<McpSchema.CallToolResult> result = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty()).withPropertyValues(properties).run((context) -> {
			assertThat(context).hasNotFailed();
			String name = context.getBeanNamesForType(STATELESS_SPECIFICATIONS)[0];
			@SuppressWarnings("unchecked")
			List<McpStatelessServerFeatures.SyncToolSpecification> specifications = (List<McpStatelessServerFeatures.SyncToolSpecification>) context
				.getBean(name);
			result.set(specifications.getFirst()
				.callHandler()
				.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build()));
		});
		return result.get();
	}

	private static Observation.Context toolCall() {
		return RECORDED.stream()
			.filter((context) -> "gatool.call".equals(context.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the call left the gatool.call observation unrecorded"));
	}

	private static List<KeyValue> tags(Observation.Context context) {
		return context.getLowCardinalityKeyValues().stream().toList();
	}

	private static ObservationRegistry recordingRegistry() {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {

			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				RECORDED.add(context);
			}
		});
		return registry;
	}

	private static String apiUrlProperty() {
		return "gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

}
