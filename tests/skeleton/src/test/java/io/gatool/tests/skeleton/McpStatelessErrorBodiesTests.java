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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error bodies of the default transport, stateless Streamable HTTP.
 *
 * <p>
 * Spring AI's transport answers a body the SDK refuses with an {@code McpError} as the
 * response body, which Spring MVC serialises with its stack trace. GATool rewrites those
 * bodies through its own router function, which has to register ahead of Spring AI's
 * under the name the two share. A JSON-RPC response posted to the endpoint is a body the
 * SDK refuses, so its answer shows which router serves the endpoint.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpStatelessErrorBodiesTests {

	private static final String ROUTER_BEAN = "webMvcStatelessServerRouterFunction";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	private int port;

	@Autowired
	private ConfigurableApplicationContext context;

	@Test
	void post_jsonRpcResponseBody_shouldBeRefusedAsAJsonRpcErrorWithoutAStackTrace() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", "2025-11-25")
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"))
			.build();

		HttpResponse<String> response;
		try (HttpClient client = HttpClient.newHttpClient()) {
			response = client.send(request, HttpResponse.BodyHandlers.ofString());
		}
		String body = response.body();

		assertThat(body).as("status " + response.statusCode()).doesNotContain("stackTrace");
		JsonNode json = JSON.readTree(body);
		assertThat(json.isObject()).as("the body is a JSON object").isTrue();
		assertThat(json.path("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(json.path("error").isObject()).as("the error member is an object").isTrue();
	}

	@Test
	void routerFunctionBean_inStatelessMode_shouldBeSuppliedByGAToolsStatelessConfiguration() {
		BeanDefinition definition = this.context.getBeanFactory().getBeanDefinition(ROUTER_BEAN);

		assertThat(definition.getFactoryBeanName()).as("the factory bean of " + ROUTER_BEAN)
			.contains("GAToolMcpAutoConfiguration");
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
