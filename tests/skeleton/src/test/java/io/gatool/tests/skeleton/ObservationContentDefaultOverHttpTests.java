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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code gatool.observations.include-content} at its default: the observation of a real
 * call names the tool and the outcome and keeps the arguments and the result out, so an
 * application exports telemetry without the model's data.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class ObservationContentDefaultOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_includeContentAtItsDefault_shouldKeepTheArgumentsAndTheResultOffTheObservation() {
		RecordingApplication.RECORDED.clear();
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isFalse();
		}

		Observation.Context context = RecordingApplication.toolCall();
		assertThat(context.getLowCardinalityKeyValues()).contains(KeyValue.of("gatool.call.name", "topRatedMovies"),
				KeyValue.of("gatool.operation.type", "query"), KeyValue.of("gatool.call.outcome", "success"));
		assertThat(context.getHighCardinalityKeyValues()).extracting(KeyValue::getKey)
			.doesNotContain("gatool.call.arguments", "gatool.call.result");
	}

	private McpSyncClient client() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	@SpringBootApplication
	static class RecordingApplication {

		static final List<Observation.Context> RECORDED = new CopyOnWriteArrayList<>();

		static Observation.Context toolCall() {
			return RECORDED.stream()
				.filter((context) -> "gatool.call".equals(context.getName()))
				.findFirst()
				.orElseThrow();
		}

		@Bean
		ObservationRegistry observationRegistry() {
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

	}

}
