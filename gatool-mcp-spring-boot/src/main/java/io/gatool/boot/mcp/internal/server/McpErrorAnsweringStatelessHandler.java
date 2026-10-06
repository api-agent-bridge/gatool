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

import java.util.Set;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Answers a request that the MCP Java SDK leaves without a response.
 *
 * <p>
 * JSON-RPC 2.0 section 5 requires a Response object for every call, and reserves
 * {@code -32601} for a method that does not exist. MCP Java SDK 2.0.0 builds that
 * response for a method it registered and fails to for one it did not.
 * {@code DefaultMcpStatelessServerHandler.handleRequest} returns {@code Mono.error} for
 * an unknown method before the {@code onErrorResume} that turns an error into a response,
 * so the error leaves the handler. Spring AI's {@code WebMvcStatelessServerTransport}
 * then answers HTTP 500 with a serialized {@code McpError}, which does not carry a
 * {@code jsonrpc} member, an {@code id} or an {@code error} object. A client cannot match
 * the failure to its request, and a stack trace of internal class, file and line names
 * goes on the wire.
 *
 * <p>
 * Five methods reach that path on a GATool server, because GATool advertises the
 * capabilities its fixture serves and leaves resources, prompts and completions out,
 * which is what MCP asks of a server that does not serve them. The SDK then skips
 * registering those handlers. The stateful stdio server answers such a method with a
 * proper {@code -32601} of its own, so this makes the two transports agree.
 *
 * <p>
 * Only {@link McpError} is answered here. It is the SDK's own protocol error and it
 * carries the JSON-RPC error to send. Anything else escaping the handler is a failure of
 * this server, and it keeps the 500 that says so.
 *
 * <p>
 * The four notifications a client sends on its own are taken here as well, and answered
 * with an empty completion. The SDK builds the stateless handler with an empty
 * notification map, so every handshake would end in a WARN, "Missing handler for
 * notification type: notifications/initialized", once per client. A stateless server
 * lacks the session each of the four refers to: the lifecycle a client marks initialized,
 * the request in flight a client cancels, the server-initiated request a client reports
 * progress on, and the roots a client asks the server to reread. Any other notification
 * still reaches the SDK, which logs what it cannot handle.
 *
 * @author Željko Kozina
 */
public final class McpErrorAnsweringStatelessHandler implements McpStatelessServerHandler {

	// The SDK lacks a constant for the cancellation notification.
	private static final Set<String> STANDARD_CLIENT_NOTIFICATIONS = Set.of(McpSchema.METHOD_NOTIFICATION_INITIALIZED,
			"notifications/cancelled", McpSchema.METHOD_NOTIFICATION_PROGRESS,
			McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED);

	private final McpStatelessServerHandler delegate;

	/**
	 * Wraps one handler.
	 * @param delegate the handler the MCP Java SDK built
	 */
	public McpErrorAnsweringStatelessHandler(McpStatelessServerHandler delegate) {
		this.delegate = delegate;
	}

	@Override
	public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
			McpSchema.JSONRPCRequest request) {
		// An McpError is built from a JSONRPCError, so it always carries the error to
		// send, and the id comes from the request the client is waiting on.
		return this.delegate.handleRequest(transportContext, request)
			.onErrorResume(McpError.class,
					(error) -> Mono.just(McpSchema.JSONRPCResponse.error(request.id(), error.getJsonRpcError())));
	}

	@Override
	public Mono<Void> handleNotification(McpTransportContext transportContext,
			McpSchema.JSONRPCNotification notification) {
		// JSON-RPC 2.0 section 5 exempts a notification from the reply rule. The four a
		// stateless server cannot act on are taken here, so the log stays quiet, and the
		// SDK logs the rest.
		if (STANDARD_CLIENT_NOTIFICATIONS.contains(notification.method())) {
			return Mono.empty();
		}
		return this.delegate.handleNotification(transportContext, notification);
	}

}
