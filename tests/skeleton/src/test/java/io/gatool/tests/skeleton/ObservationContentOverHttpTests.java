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
 * {@code gatool.observations.include-content=true}, read from the observation of a real
 * call: the arguments and the result join the observation an application exports.
 *
 * <p>
 * The registry is the one an application declares, so the starter finds it the way it
 * finds Boot's. A recording handler on it reads what each observation carried.
 * {@link ObservationContentDefaultOverHttpTests} is the same call with the setting left
 * at its default, where both values stay out.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.observations.include-content=true" })
class ObservationContentOverHttpTests {

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_includeContentOn_shouldCarryTheArgumentsAndTheResultOnTheObservation() {
		RecordingApplication.RECORDED.clear();
		try (McpSyncClient client = client()) {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(McpSchema.CallToolRequest.builder("topRatedMovies").arguments(Map.of("first", 1)).build());

			assertThat(result.isError()).isFalse();
		}

		Observation.Context context = RecordingApplication.toolCall();
		assertThat(context.getLowCardinalityKeyValues()).contains(KeyValue.of("gatool.call.name", "topRatedMovies"),
				KeyValue.of("gatool.call.outcome", "success"));
		// The values are what the model sent and what it read back.
		assertThat(context.getHighCardinalityKeyValue("gatool.call.arguments")).isNotNull()
			.extracting(KeyValue::getValue)
			.isEqualTo("{first=1}");
		assertThat(context.getHighCardinalityKeyValue("gatool.call.result")).isNotNull()
			.extracting(KeyValue::getValue)
			.asString()
			.contains("Signal from Kepler");
	}

	private McpSyncClient client() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).endpoint("/mcp").build())
			.requestTimeout(Duration.ofSeconds(20))
			.build();
	}

	@SpringBootApplication
	static class RecordingApplication {

		// The outbound GraphQL request is observed on the same registry, so the tool
		// call is picked out by name.
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
