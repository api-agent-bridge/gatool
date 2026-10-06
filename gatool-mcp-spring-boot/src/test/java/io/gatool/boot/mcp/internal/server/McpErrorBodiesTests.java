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

package io.gatool.boot.mcp.internal.server;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.mcp.internal.transport.BufferedBodyRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code McpError} body leaves the transport as the JSON-RPC error alone, with its
 * status and headers kept, and with the id of the call the request sent where the request
 * holds one.
 */
class McpErrorBodiesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void withoutInternals_mcpErrorBody_shouldCarryTheJsonRpcErrorAlone() throws Exception {
		// The body the transports answer a message they cannot read with, whose code
		// stays the SDK's own.
		McpError error = McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
			.message("Invalid message format")
			.build();
		RouterFunction<ServerResponse> routes = RouterFunctions.route()
			.POST("/mcp",
					(request) -> ServerResponse.status(HttpStatus.BAD_REQUEST).header("X-Kept", "yes").body(error))
			.build();

		ServerResponse response = McpErrorBodies.withoutStackTraces(routes, JSON)
			.route(request("/mcp"))
			.orElseThrow()
			.handle(request("/mcp"));

		assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.headers().getFirst("X-Kept")).isEqualTo("yes");
		String body = (String) ((EntityResponse<?>) response).entity();
		assertThat(body)
			.isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
					+ "\"message\":\"Invalid message format\"}}")
			.doesNotContain("stackTrace");
	}

	@Test
	void withoutInternals_sessionNotFoundUnderA404_shouldCarryTheSessionNotFoundCode() throws Exception {
		// The stateful transport answers a session it no longer serves with 404 and the
		// SDK's -32603, while the binding filter on the secured path answers the same
		// case with -32001, the code the MCP SDKs use for it. A client reads one code on
		// both paths, with the id of the call it sent.
		McpError error = McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
			.message("Session not found: abc")
			.build();
		RouterFunction<ServerResponse> routes = RouterFunctions.route()
			.POST("/mcp", (request) -> ServerResponse.status(HttpStatus.NOT_FOUND).body(error))
			.build();
		ServerRequest request = bufferedRequest("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

		ServerResponse response = McpErrorBodies.withoutStackTraces(routes, JSON)
			.route(request)
			.orElseThrow()
			.handle(request);

		assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat((String) ((EntityResponse<?>) response).entity()).isEqualTo(
				"{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32001,\"message\":\"Session not found: abc\"}}");
	}

	@Test
	void withoutInternals_requestBufferedByTheComplianceFilter_shouldAnswerWithTheCallsId() throws Exception {
		// The compliance filter hands the servlet a request that replays the body, so the
		// id is readable here after the transport spent its own read, and a client
		// matches the error to the call it sent.
		ServerRequest request = bufferedRequest("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}");

		String body = (String) ((EntityResponse<?>) McpErrorBodies.withoutStackTraces(errorRoutes(), JSON)
			.route(request)
			.orElseThrow()
			.handle(request)).entity();

		assertThat(body).startsWith("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":");
	}

	@Test
	void withoutInternals_requestWithAStringId_shouldAnswerWithThatId() throws Exception {
		ServerRequest request = bufferedRequest("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"method\":\"ping\"}");

		String body = (String) ((EntityResponse<?>) McpErrorBodies.withoutStackTraces(errorRoutes(), JSON)
			.route(request)
			.orElseThrow()
			.handle(request)).entity();

		assertThat(body).startsWith("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\",\"error\":");
	}

	@Test
	void withoutInternals_requestWhoseBodyIsNotAMessage_shouldAnswerWithANullId() throws Exception {
		ServerRequest request = bufferedRequest("not json");

		String body = (String) ((EntityResponse<?>) McpErrorBodies.withoutStackTraces(errorRoutes(), JSON)
			.route(request)
			.orElseThrow()
			.handle(request)).entity();

		assertThat(body).startsWith("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":");
	}

	private static RouterFunction<ServerResponse> errorRoutes() {
		McpError error = McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message("Method not found").build();
		return RouterFunctions.route()
			.POST("/mcp", (request) -> ServerResponse.status(HttpStatus.OK).body(error))
			.build();
	}

	private static ServerRequest bufferedRequest(String body) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setRequestURI("/mcp");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return ServerRequest.create(new BufferedBodyRequest(request, 1024), List.of());
	}

	@Test
	void withoutInternals_otherBody_shouldPassUnchanged() throws Exception {
		RouterFunction<ServerResponse> routes = RouterFunctions.route()
			.POST("/mcp", (request) -> ServerResponse.ok().body("plain"))
			.build();

		ServerResponse response = McpErrorBodies.withoutStackTraces(routes, JSON)
			.route(request("/mcp"))
			.orElseThrow()
			.handle(request("/mcp"));

		assertThat(((EntityResponse<?>) response).entity()).isEqualTo("plain");
	}

	private static ServerRequest request(String path) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setRequestURI(path);
		return ServerRequest.create(request, List.of());
	}

}
