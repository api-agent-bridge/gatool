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

import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import reactor.core.publisher.Mono;

import io.gatool.boot.mcp.internal.transport.McpRevisions;

/**
 * The revision half of {@link RevisionLimitedStatelessTransport}, for stateful Streamable
 * HTTP.
 *
 * <p>
 * A stateful server reads its revisions from the transport provider, and the SDK's
 * default list carries 2025-03-26, which MCP Java SDK 2.0.0 cannot serve in full because
 * that revision requires JSON-RPC batches. The compliance filter refuses a request under
 * that revision, so a session that agreed to it during {@code initialize} would be stuck.
 * This wrapper hands the server the list the filter accepts.
 *
 * <p>
 * Spring AI's {@code WebMvcStreamableServerTransportProvider} is final, so this class
 * wraps the bean and is published as the primary
 * {@link McpStreamableServerTransportProvider}. The router function keeps the bean
 * itself, and the server reaches that same instance through this wrapper, so one
 * transport serves the endpoint and the revision list is GATool's. The sessions stay the
 * provider's own, capped and evicted by the settings GATool builds it with.
 *
 * @author Željko Kozina
 */
public final class RevisionLimitedStreamableServerTransportProvider implements McpStreamableServerTransportProvider {

	private final McpStreamableServerTransportProvider delegate;

	private final List<String> protocolVersions;

	/**
	 * Wraps one transport provider.
	 * @param delegate spring AI's provider, which serves the endpoint and owns the
	 * sessions
	 * @param allowSupersededRevisions the value of
	 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions}
	 */
	public RevisionLimitedStreamableServerTransportProvider(McpStreamableServerTransportProvider delegate,
			boolean allowSupersededRevisions) {
		this.delegate = delegate;
		this.protocolVersions = McpRevisions.accepted(allowSupersededRevisions);
	}

	@Override
	public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
		this.delegate.setSessionFactory(sessionFactory);
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		return this.delegate.notifyClients(method, params);
	}

	// The interface's default answers with an error, and the server calls this for a
	// resource update and an elicitation completion, so the delegate's own answer has
	// to reach it.
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
