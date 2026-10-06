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

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import io.gatool.boot.mcp.internal.transport.McpRevisions;

/**
 * Repairs the two places where Spring AI's stateless transport departs from what GATool
 * serves, and leaves everything else to that transport.
 *
 * <p>
 * The first is the revision list, below. The second is the response to a method the
 * server did not register, which {@link McpErrorAnsweringStatelessHandler} answers. This
 * class installs that handler, because {@code setMcpHandler} is where the SDK hands its
 * handler over and a transport is the only thing that sees it.
 *
 * <p>
 * {@code McpStatelessServerTransport.protocolVersions()} is what the SDK reads when it
 * answers {@code initialize}: it agrees to the revision a client asks for when the list
 * holds it, and otherwise answers with the last entry. The default list carries
 * 2025-03-26, so a client asking for that revision would get a successful handshake, and
 * then every request it made under the agreed revision would meet a 400 from
 * {@link io.gatool.boot.mcp.internal.transport.McpComplianceFilter}.
 *
 * <p>
 * Spring AI's {@code WebMvcStatelessServerTransport} is final, so this class wraps the
 * bean instead of extending it, and the wrapper is published as the primary
 * {@code McpStatelessServerTransport}. The router function keeps the bean itself, and the
 * server reaches that same instance through this wrapper, so one transport serves the
 * endpoint and the revision list is GATool's.
 *
 * @author Željko Kozina
 */
public final class RevisionLimitedStatelessTransport implements McpStatelessServerTransport {

	private final McpStatelessServerTransport delegate;

	private final List<String> protocolVersions;

	/**
	 * Wraps one transport.
	 * @param delegate spring AI's transport, which serves the endpoint
	 * @param allowSupersededRevisions the value of
	 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions}, so that widening
	 * the filter widens the handshake with it
	 */
	public RevisionLimitedStatelessTransport(McpStatelessServerTransport delegate, boolean allowSupersededRevisions) {
		this.delegate = delegate;
		this.protocolVersions = McpRevisions.accepted(allowSupersededRevisions);
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.delegate.setMcpHandler(new McpErrorAnsweringStatelessHandler(mcpHandler));
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
