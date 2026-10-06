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

import java.util.List;

import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.publisher.Mono;

import io.gatool.boot.mcp.internal.transport.McpRevisions;

/**
 * GATool builds the same stdio transport Spring AI builds and wraps it in one small
 * class, so a stdio client cannot agree to the superseded MCP revision 2025-03-26, which
 * the SDK serves in part. Over HTTP the compliance filter refuses a request naming that
 * revision, and stdio runs without a filter. Spring AI's own bean cannot be wrapped
 * through a condition, because GATool's configuration runs first, so GATool builds the
 * provider itself and Spring AI backs off. The wrapper passes every message through
 * unchanged, and the thread each answer leaves on is set on the server bean by
 * {@code gaToolStdioMcpSyncServerCustomizer}.
 *
 * <p>
 * This is the revision half of {@link RevisionLimitedStatelessTransport}.
 *
 * <p>
 * Only that half: a stateful session sends a JSON-RPC {@code -32601} of its own for a
 * method it did not register, which is the repair
 * {@link McpErrorAnsweringStatelessHandler} makes on the stateless side. Over stdio that
 * event is the whole answer, because the messages travel on the process's standard
 * output, which stays open for the next message. Over stateful Streamable HTTP the same
 * code in MCP Java SDK 2.0.0 sends the event and leaves the response stream open:
 * {@code McpStreamableServerSession.responseStream} closes the transport after a handler
 * it found and returns after the method-not-found event without closing it. GATool
 * documents that as an inherited deviation and pins it in a test until the SDK closes the
 * stream.
 *
 * <p>
 * A stateful server reads its revisions from the transport provider, and the SDK's
 * default list carries 2025-03-26, which MCP Java SDK 2.0.0 cannot serve in full because
 * that revision requires JSON-RPC batches. Over HTTP the compliance filter refuses such a
 * session; stdio lacks that filter, so without this wrapper a stdio client could
 * negotiate a revision GATool says it does not serve.
 *
 * <p>
 * Spring AI publishes {@code stdioServerTransport} as
 * {@code McpServerTransportProviderBase} and casts it to
 * {@link McpServerTransportProvider} when it builds the server, so this wrapper
 * implements the narrower type and forwards every call.
 *
 * <p>
 * GATool builds the provider this wraps, because Spring AI's bean is registered after
 * GATool's own definitions and a condition on it cannot match. See
 * {@code GAToolMcpAutoConfiguration.Stateful.gaToolStdioServerTransportProvider}.
 *
 * @author Željko Kozina
 */
public final class RevisionLimitedServerTransportProvider implements McpServerTransportProvider {

	private final McpServerTransportProvider delegate;

	private final List<String> protocolVersions;

	/**
	 * Wraps one transport provider.
	 * @param delegate the SDK's provider, which owns the streams
	 * @param allowSupersededRevisions the value of
	 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions}
	 */
	public RevisionLimitedServerTransportProvider(McpServerTransportProvider delegate,
			boolean allowSupersededRevisions) {
		this.delegate = delegate;
		this.protocolVersions = McpRevisions.accepted(allowSupersededRevisions);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.delegate.setSessionFactory(sessionFactory);
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		return this.delegate.notifyClients(method, params);
	}

	@Override
	public Mono<Void> notifyClient(String sessionId, String method, Object params) {
		return this.delegate.notifyClient(sessionId, method, params);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public List<String> protocolVersions() {
		return this.protocolVersions;
	}

}
