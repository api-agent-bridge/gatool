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
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the default transport answers to a request the SDK would otherwise have to judge:
 * a JSON-RPC message that parses and is still invalid, an {@code Accept} header the SDK
 * reads too literally, and an HTTP method the router leaves out.
 *
 * <p>
 * GATool answers each case ahead of Spring AI's transport. Left to that transport, a
 * message without {@code jsonrpc} answers 500 with -32603, {@code "jsonrpc":"1.0"} is
 * served, {@code tools/call} with arguments that are not an object answers 500, and
 * {@code initialize} with params that are not an object answers 200 with -32603 and the
 * SDK's class name in the message. An {@code Accept} header of any media type, or one
 * with q-values, answers 400 with an empty body. A {@code DELETE} or a {@code PUT}
 * reaches the router, which answers through the container's error dispatch: a bare 401
 * from the security chain, and Boot's error page without it.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpMalformedMessageOverHttpTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, String> ACCEPT_BOTH = Map.of("Accept", "application/json, text/event-stream");

	private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void post_messageWithoutAJsonrpcMember_shouldAnswer400WithInvalidRequestAndTheCallsId() throws Exception {
		HttpResponse<String> response = send("POST", "{\"id\":1,\"method\":\"ping\"}", ACCEPT_BOTH);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.path("id").asInt()).isEqualTo(1);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
	}

	@Test
	void post_jsonrpcVersionOtherThan20_shouldAnswer400WithInvalidRequest() throws Exception {
		HttpResponse<String> response = send("POST", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}", ACCEPT_BOTH);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		assertThat(JSON.readTree(response.body()).path("error").path("code").asInt()).isEqualTo(-32600);
	}

	@Test
	void post_toolsCallWithArgumentsThatAreNotAnObject_shouldAnswer200WithInvalidParams() throws Exception {
		HttpResponse<String> response = send("POST", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"topRatedMovies\",\"arguments\":\"bad\"}}", ACCEPT_BOTH);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.path("id").asInt()).isEqualTo(5);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
		assertThat(error.path("error").path("message").asString()).contains("params.arguments");
	}

	@Test
	void post_initializeWithParamsThatIsNotAnObject_shouldAnswer400WithoutNamingTheSdksClasses() throws Exception {
		HttpResponse<String> response = send("POST",
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":\"bad\"}", ACCEPT_BOTH);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(response.body()).doesNotContain("io.modelcontextprotocol").doesNotContain("McpSchema");
	}

	@Test
	void post_acceptAnyMediaType_shouldBeServed() throws Exception {
		HttpResponse<String> response = send("POST", PING, Map.of("Accept", "*/*"));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).path("result").isObject()).isTrue();
	}

	@Test
	void post_acceptWithQValues_shouldBeServed() throws Exception {
		HttpResponse<String> response = send("POST", PING,
				Map.of("Accept", "application/json;q=0.9, text/event-stream;q=0.8"));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).path("result").isObject()).isTrue();
	}

	@Test
	void post_withoutAnAcceptHeader_shouldBeServed() throws Exception {
		HttpResponse<String> response = send("POST", PING, Map.of());

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).path("result").isObject()).isTrue();
	}

	@Test
	void post_acceptNamingNeitherMediaType_shouldKeepTheSdksRefusal() throws Exception {
		HttpResponse<String> response = send("POST", PING, Map.of("Accept", "text/plain"));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
	}

	@Test
	void delete_onTheStatelessTransport_shouldAnswer405WithAllowAndAJsonRpcError() throws Exception {
		HttpResponse<String> response = send("DELETE", null, Map.of("Mcp-Session-Id", "abc"));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(405);
		assertThat(response.headers().firstValue("Allow")).contains("GET, POST");
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.path("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(error.path("id").isNull()).isTrue();
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
	}

	@Test
	void put_shouldAnswer405WithAllowAndAJsonRpcError() throws Exception {
		HttpResponse<String> response = send("PUT", PING, ACCEPT_BOTH);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(405);
		assertThat(response.headers().firstValue("Allow")).contains("GET, POST");
		assertThat(JSON.readTree(response.body()).path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
	}

	@Test
	void notification_initializedAfterTheHandshake_shouldLeaveTheLogWithoutAMissingHandlerWarning(CapturedOutput output)
			throws Exception {
		// The SDK builds the stateless handler with an empty notification map, so a
		// handshake that reaches it ends in "Missing handler for notification type:
		// notifications/initialized" at WARN, once per client.
		assertThat(send("POST", INITIALIZE, ACCEPT_BOTH).statusCode()).isEqualTo(200);

		HttpResponse<String> response = send("POST", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
				ACCEPT_BOTH);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		assertThat(output.getAll()).doesNotContain("Missing handler for notification type");
	}

	private HttpResponse<String> send(String method, String body, Map<String, String> headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.timeout(Duration.ofSeconds(20));
		if (body != null) {
			request.header("Content-Type", "application/json");
			request.method(method, HttpRequest.BodyPublishers.ofString(body));
		}
		else {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		headers.forEach(request::header);
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
