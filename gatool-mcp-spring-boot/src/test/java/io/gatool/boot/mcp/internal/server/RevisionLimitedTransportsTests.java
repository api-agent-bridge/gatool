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
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import io.gatool.boot.mcp.internal.transport.McpRevisions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two wrappers hand the MCP Java SDK the revisions GATool serves, and leave every
 * other call to the transport Spring AI published.
 */
class RevisionLimitedTransportsTests {

	@Test
	void protocolVersions_statelessTransport_shouldCarryTheRevisionsTheFilterAccepts() {
		McpStatelessServerTransport transport = new RevisionLimitedStatelessTransport(new StatelessStub(), false);

		// Ascending, because the SDK answers a handshake with the last entry.
		assertThat(transport.protocolVersions()).containsExactly("2025-06-18", "2025-11-25")
			.isEqualTo(McpRevisions.accepted(false));
		assertThat(transport.protocolVersions().getLast()).isEqualTo(McpRevisions.newestSupported());
	}

	@Test
	void protocolVersions_supersededRevisionsAllowed_shouldWidenWithTheFilter() {
		McpStatelessServerTransport transport = new RevisionLimitedStatelessTransport(new StatelessStub(), true);

		// The unsafe switch widens the filter, so it widens the handshake with it,
		// which is what keeps a negotiated revision usable afterwards.
		assertThat(transport.protocolVersions()).containsExactly("2024-11-05", "2025-03-26", "2025-06-18",
				"2025-11-25");
	}

	@Test
	void setMcpHandler_statelessTransport_shouldReachTheWrappedTransport() {
		StatelessStub wrapped = new StatelessStub();
		McpStatelessServerTransport transport = new RevisionLimitedStatelessTransport(wrapped, false);

		transport.setMcpHandler(new StatelessHandlerStub());
		transport.closeGracefully().block();

		// The router function keeps Spring AI's own instance, so the server has to
		// reach that instance through the wrapper.
		assertThat(wrapped.handlerSet).isTrue();
		assertThat(wrapped.closed).isTrue();
	}

	@Test
	void protocolVersions_stdioTransportProvider_shouldCarryTheSameRevisions() {
		McpServerTransportProvider provider = new RevisionLimitedServerTransportProvider(new ProviderStub(), false);

		assertThat(provider.protocolVersions()).containsExactly("2025-06-18", "2025-11-25");
	}

	@Test
	void setSessionFactory_stdioTransportProvider_shouldReachTheWrappedProvider() {
		ProviderStub wrapped = new ProviderStub();
		McpServerTransportProvider provider = new RevisionLimitedServerTransportProvider(wrapped, false);

		provider.setSessionFactory((transport) -> {
			throw new UnsupportedOperationException("the stub does not build a session");
		});
		provider.notifyClients("notifications/tools/list_changed", null).block();

		assertThat(wrapped.sessionFactorySet).isTrue();
		assertThat(wrapped.notified).isTrue();
	}

	@Test
	void protocolVersions_theSdkDefault_shouldStillCarryTheRevisionGAToolRefuses() {
		// The reason both wrappers exist: the SDK's own default list holds 2025-03-26,
		// which the compliance filter refuses on every request after the handshake.
		assertThat(new StatelessStub().protocolVersions()).contains("2025-03-26");
		assertThat(McpRevisions.accepted(false)).doesNotContain("2025-03-26");
	}

	private static final class StatelessStub implements McpStatelessServerTransport {

		private boolean handlerSet;

		private boolean closed;

		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
			this.handlerSet = true;
		}

		@Override
		public Mono<Void> closeGracefully() {
			this.closed = true;
			return Mono.empty();
		}

	}

	private static final class ProviderStub implements McpServerTransportProvider {

		private boolean sessionFactorySet;

		private boolean notified;

		@Override
		public void setSessionFactory(McpServerSession.Factory sessionFactory) {
			this.sessionFactorySet = true;
		}

		@Override
		public Mono<Void> notifyClients(String method, Object params) {
			this.notified = true;
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

	private static final class StatelessHandlerStub implements McpStatelessServerHandler {

		@Override
		public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
				McpSchema.JSONRPCRequest request) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> handleNotification(McpTransportContext transportContext,
				McpSchema.JSONRPCNotification notification) {
			return Mono.empty();
		}

	}

}
