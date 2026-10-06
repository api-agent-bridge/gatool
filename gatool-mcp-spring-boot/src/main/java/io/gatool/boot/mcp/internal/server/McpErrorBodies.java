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

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.EntityResponse;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.mcp.internal.transport.BufferedBodyRequest;

/**
 * Rewrites the error bodies Spring AI's MCP transports write, so they carry the JSON-RPC
 * error alone.
 *
 * <p>
 * Both WebMvc transports answer a session the server does not serve, a request they
 * cannot read and an internal failure with a {@code McpError} as the response body.
 * {@code McpError} is an exception, and Spring MVC's converter serialises an exception
 * with its stack trace, its cause and its suppressed list, so every such answer would
 * name this server's classes, files and line numbers to the caller. The filter below
 * keeps the status and the headers and writes the error in the shape every other refusal
 * on this endpoint has.
 *
 * <p>
 * The error carries the id of the call the request sent, where the request holds a
 * readable JSON-RPC message object, so a client matches the error to that call. The
 * transport has spent its own read of the body by then, and the compliance filter hands
 * the servlet a request that replays it, which is where the id is read from. The code
 * stays the SDK's own with one exception: a session the server does not serve, which the
 * stateful transport answers with 404 and -32603, is written with -32001, the code the
 * MCP SDKs use for a session not found and the code the session binding filter writes for
 * the same case on the secured path. A client then reads one code on both paths.
 *
 * @author Željko Kozina
 */
public final class McpErrorBodies {

	// The code MCP SDKs use for a session the server does not serve.
	private static final int SESSION_NOT_FOUND = -32001;

	private McpErrorBodies() {
	}

	/**
	 * Returns the routes with every {@code McpError} body rewritten.
	 * @param routes the transport's router function
	 * @param jsonMapper the mapper the transport writes with, which keeps a null id
	 * @return the filtered routes
	 */
	public static RouterFunction<ServerResponse> withoutStackTraces(RouterFunction<ServerResponse> routes,
			JsonMapper jsonMapper) {
		return routes.filter((request, next) -> {
			ServerResponse response = next.handle(request);
			if (response instanceof EntityResponse<?> entity && entity.entity() instanceof McpError error) {
				Map<String, @Nullable Object> body = new LinkedHashMap<>();
				body.put("jsonrpc", "2.0");
				body.put("id", idOf(request, jsonMapper));
				body.put("error", errorOf(error, response.statusCode()));
				return ServerResponse.status(response.statusCode())
					.headers((headers) -> headers.putAll(response.headers()))
					.contentType(MediaType.APPLICATION_JSON)
					.body(jsonMapper.writeValueAsString(body));
			}
			return response;
		});
	}

	// The only 404 the transports answer is a session they do not serve.
	private static McpSchema.JSONRPCResponse.JSONRPCError errorOf(McpError error, HttpStatusCode status) {
		McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = error.getJsonRpcError();
		if (status.value() != HttpStatus.NOT_FOUND.value()) {
			return jsonRpcError;
		}
		return new McpSchema.JSONRPCResponse.JSONRPCError(SESSION_NOT_FOUND, jsonRpcError.message(),
				jsonRpcError.data());
	}

	// Returns the id of the JSON-RPC message the request carried, or null where the
	// request lacks a message object with a readable id.
	// The request arrives wrapped by every filter between the compliance filter and the
	// servlet, Spring Security's among them, so the buffer is found by unwrapping. A
	// GET or a DELETE arrives without a buffer, and its error answers with a null id, the
	// way JSON-RPC asks for an error about a request whose id could not be read.
	private static @Nullable Object idOf(ServerRequest request, JsonMapper jsonMapper) {
		BufferedBodyRequest buffered = WebUtils.getNativeRequest(request.servletRequest(), BufferedBodyRequest.class);
		return (buffered != null) ? buffered.callId(jsonMapper) : null;
	}

}
