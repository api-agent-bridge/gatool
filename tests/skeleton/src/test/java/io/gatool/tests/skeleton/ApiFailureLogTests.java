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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.ResolvableType;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An answer the operator has to act on reaches the log at WARN: a 5xx, and a 2xx whose
 * body is a sign-in page, empty, or something other than a GraphQL response. The line is
 * written once per tool per minute, because an API that stays down fails every call, and
 * the next line carries the count of the answers left out. A 4xx stays at DEBUG, because
 * the model reads it and can correct the call. The {@code gatool.call} observation tags
 * every one of them {@code api-refused}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiFailureLogTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final ResolvableType STATELESS_SPECIFICATIONS = ResolvableType.forClassWithGenerics(List.class,
			McpStatelessServerFeatures.SyncToolSpecification.class);

	private static final AtomicInteger STATUS = new AtomicInteger(503);

	private static final AtomicReference<String> BODY = new AtomicReference<>("{\"message\":\"upstream unavailable\"}");

	private static final AtomicReference<String> CONTENT_TYPE = new AtomicReference<>("application/json");

	private static final List<Observation.Context> RECORDED = new CopyOnWriteArrayList<>();

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues(SCHEMA);

	private final ApplicationContextRunner mcpContextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withBean(ObservationRegistry.class, ApiFailureLogTests::recordingRegistry)
		.withPropertyValues(SCHEMA, "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/");

	@BeforeAll
	static void startFailingApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			byte[] body = BODY.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE.get());
			exchange.sendResponseHeaders(STATUS.get(), body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopFailingApi() {
		server.stop(0);
	}

	@Test
	void call_apiAnswers503ThreeTimes_shouldReachTheModelAsApiRefusedAndWarnOnce(CapturedOutput output) {
		STATUS.set(503);
		CONTENT_TYPE.set("application/json");
		BODY.set("{\"message\":\"upstream unavailable\"}");

		ToolCallOutcome outcome = callThreeTimes();

		List<String> warnings = runnerWarnings(output);
		assertThat(outcome.kind()).isEqualTo(ToolCallOutcome.Kind.API_REFUSED);
		assertThat(outcome.text()).contains("503").contains("A later call may succeed");
		assertThat(warnings).as("WARN lines from ToolCallRunner for three 503 answers").hasSize(1);
		assertThat(warnings.getFirst()).contains("topRatedMovies").contains("503").contains("upstream unavailable");
	}

	@Test
	void call_proxyAnswers200SignInPageThreeTimes_shouldWarnOnce(CapturedOutput output) {
		STATUS.set(200);
		CONTENT_TYPE.set("text/html");
		BODY.set("<html><body>Sign in to continue</body></html>");

		ToolCallOutcome outcome = callThreeTimes();

		List<String> warnings = runnerWarnings(output);
		assertThat(outcome.kind()).isEqualTo(ToolCallOutcome.Kind.API_REFUSED);
		assertThat(warnings).as("WARN lines from ToolCallRunner for three sign-in pages").hasSize(1);
		assertThat(warnings.getFirst()).contains("topRatedMovies").contains("200").contains("text/html");
	}

	@Test
	void call_apiAnswers404ThreeTimes_shouldLeaveTheLogBelowWarn(CapturedOutput output) {
		STATUS.set(404);
		CONTENT_TYPE.set("application/json");
		BODY.set("{\"message\":\"no such route\"}");

		ToolCallOutcome outcome = callThreeTimes();

		assertThat(outcome.kind()).isEqualTo(ToolCallOutcome.Kind.API_REFUSED);
		assertThat(runnerWarnings(output)).as("WARN lines from ToolCallRunner for a 404").isEmpty();
	}

	@Test
	void callTool_apiAnswers503_overMcp_shouldTagTheObservationApiRefused() {
		STATUS.set(503);
		CONTENT_TYPE.set("application/json");
		BODY.set("{\"message\":\"upstream unavailable\"}");
		RECORDED.clear();

		AtomicReference<McpSchema.CallToolResult> result = new AtomicReference<>();
		this.mcpContextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			assertThat(context).hasNotFailed();
			String name = context.getBeanNamesForType(STATELESS_SPECIFICATIONS)[0];
			@SuppressWarnings("unchecked")
			List<McpStatelessServerFeatures.SyncToolSpecification> specifications = (List<McpStatelessServerFeatures.SyncToolSpecification>) context
				.getBean(name);
			result.set(specifications.getFirst()
				.callHandler()
				.apply(null, McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of()).build()));
		});

		Observation.Context call = RECORDED.stream()
			.filter((context) -> "gatool.call".equals(context.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("the call left the gatool.call observation unrecorded"));
		List<KeyValue> tags = call.getLowCardinalityKeyValues().stream().toList();
		assertThat(result.get().isError()).isTrue();
		assertThat(tags).contains(KeyValue.of("gatool.call.outcome", "api-refused"));
		assertThat(tags).doesNotContain(KeyValue.of("gatool.call.outcome", "graphql-errors"));
	}

	private ToolCallOutcome callThreeTimes() {
		AtomicReference<ToolCallOutcome> outcome = new AtomicReference<>();
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();
			for (int call = 0; call < 3; call++) {
				outcome.set(tool.call(Map.of()));
			}
		});
		return outcome.get();
	}

	private static List<String> runnerWarnings(CapturedOutput output) {
		return output.getAll()
			.lines()
			.filter((line) -> line.contains("WARN") && line.contains("ToolCallRunner"))
			.toList();
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
