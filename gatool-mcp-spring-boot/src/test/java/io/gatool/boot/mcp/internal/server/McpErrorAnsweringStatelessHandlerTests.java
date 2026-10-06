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

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Every call gets a response, which is what JSON-RPC 2.0 section 5 requires.
 *
 * <p>
 * MCP Java SDK 2.0.0 builds that response for a method it registered and fails an
 * unregistered one with an error that leaves the handler, and Spring AI's transport then
 * sends HTTP 500 with a serialized Java exception. The tests below drive the wrapper with
 * the error the SDK raises, so the answer a client reads is asserted at the one place
 * GATool can shape it.
 */
class McpErrorAnsweringStatelessHandlerTests {

	private static final McpSchema.JSONRPCRequest RESOURCES_LIST = new McpSchema.JSONRPCRequest("resources/list", 7,
			null);

	@Test
	void handleRequest_methodTheServerDidNotRegister_shouldAnswerMethodNotFound() {
		// The SDK raises this exact error for a method without a handler, and a GATool
		// server has five of them, because it advertises tools alone.
		McpStatelessServerHandler handler = new McpErrorAnsweringStatelessHandler(
				failing(McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
					.message("Missing handler for request type: resources/list")
					.build()));

		McpSchema.JSONRPCResponse response = handler.handleRequest(McpTransportContext.EMPTY, RESOURCES_LIST).block();

		assertThat(response).isNotNull();
		assertThat(response.jsonrpc()).isEqualTo(McpSchema.JSONRPC_VERSION);
		// Without the id a client cannot match the failure to the request it sent.
		assertThat(response.id()).isEqualTo(7);
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		assertThat(response.error().message()).contains("resources/list");
	}

	@Test
	void handleRequest_anErrorCarryingData_shouldKeepEveryMemberTheSdkPutInIt() {
		McpStatelessServerHandler handler = new McpErrorAnsweringStatelessHandler(
				failing(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("uri is required")
					.data("resources/read")
					.build()));

		McpSchema.JSONRPCResponse response = handler.handleRequest(McpTransportContext.EMPTY, RESOURCES_LIST).block();

		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS);
		assertThat(response.error().data()).isEqualTo("resources/read");
	}

	@Test
	void handleRequest_aResponseTheSdkBuilt_shouldPassItThroughUntouched() {
		McpSchema.JSONRPCResponse built = McpSchema.JSONRPCResponse.result(7, "the tools");
		McpStatelessServerHandler handler = new McpErrorAnsweringStatelessHandler(answering(built));

		assertThat(handler.handleRequest(McpTransportContext.EMPTY, RESOURCES_LIST).block()).isSameAs(built);
	}

	@Test
	void handleRequest_aFailureThatIsNotAnMcpError_shouldStayAFailure() {
		// An error of any other kind is this server breaking, and the 500 that says so is
		// the honest answer. A 200 carrying a JSON-RPC error would tell the client that
		// the server handled the call.
		McpStatelessServerHandler handler = new McpErrorAnsweringStatelessHandler(
				failing(new IllegalStateException("the bean is gone")));

		Mono<McpSchema.JSONRPCResponse> answer = handler.handleRequest(McpTransportContext.EMPTY, RESOURCES_LIST);

		assertThatIllegalStateException().isThrownBy(answer::block);
	}

	@Test
	void handleNotification_aNotificationOutsideTheStandardClientSet_shouldReachTheWrappedHandler() {
		// JSON-RPC exempts a notification from the reply rule, so this side forwards
		// what it cannot answer itself.
		Notifications wrapped = new Notifications();
		McpStatelessServerHandler handler = new McpErrorAnsweringStatelessHandler(wrapped);

		handler
			.handleNotification(McpTransportContext.EMPTY,
					new McpSchema.JSONRPCNotification("notifications/whatever", null))
			.block();

		assertThat(wrapped.seen).isEqualTo("notifications/whatever");
	}

	@ParameterizedTest
	@ValueSource(strings = { "notifications/initialized", "notifications/cancelled", "notifications/progress",
			"notifications/roots/list_changed" })
	void handleNotification_aStandardClientNotification_shouldBeAnsweredWithoutTheWrappedHandler(String method) {
		// The SDK builds the stateless handler with an empty notification map, so every
		// handshake would end in a WARN, "Missing handler for notification type:
		// notifications/initialized". A stateless server lacks the session each of the
		// four refers to, so the wrapper takes them and the log stays quiet.
		Notifications wrapped = new Notifications();
		McpStatelessServerHandler handler = new McpErrorAnsweringStatelessHandler(wrapped);

		handler.handleNotification(McpTransportContext.EMPTY, new McpSchema.JSONRPCNotification(method, null)).block();

		assertThat(wrapped.seen).as("the notification the wrapped handler saw").isNull();
	}

	private static Requests failing(RuntimeException error) {
		return (context, request) -> Mono.error(error);
	}

	private static Requests answering(McpSchema.JSONRPCResponse response) {
		return (context, request) -> Mono.just(response);
	}

	// The request half of the interface, so a test that only answers requests writes one
	// lambda. The notification half has a stub below, because one test reads what reached
	// it.
	@FunctionalInterface
	private interface Requests extends McpStatelessServerHandler {

		@Override
		default Mono<Void> handleNotification(McpTransportContext transportContext,
				McpSchema.JSONRPCNotification notification) {
			return Mono.empty();
		}

	}

	private static final class Notifications implements McpStatelessServerHandler {

		private String seen;

		@Override
		public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
				McpSchema.JSONRPCRequest request) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> handleNotification(McpTransportContext transportContext,
				McpSchema.JSONRPCNotification notification) {
			this.seen = notification.method();
			return Mono.empty();
		}

	}

}
