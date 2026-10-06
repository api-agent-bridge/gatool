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

package io.gatool.boot.mcp.internal.transport;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The MCP revisions this release serves, in one place.
 *
 * <p>
 * Two parts of GATool read this set, and they have to agree. The compliance filter
 * refuses a request whose {@code MCP-Protocol-Version} sits outside it, and the transport
 * hands the same list to the MCP Java SDK, which answers {@code initialize} from it. If
 * the two disagreed, the SDK could agree to a revision during the handshake that the
 * filter then refuses on every request after it, which would leave the client with a
 * session it cannot use.
 *
 * <p>
 * MCP names each revision by its date, so sorting the strings orders them and the newest
 * one sits last. The SDK reads the last entry as the revision it answers with, so the
 * list this class returns is ascending.
 *
 * @author Željko Kozina
 */
public final class McpRevisions {

	/**
	 * Revision 2025-11-25 and the one before it.
	 */
	private static final Set<String> SUPPORTED = Set.of("2025-11-25", "2025-06-18");

	/**
	 * The revisions Spring AI's transports also list, which
	 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions} adds back. MCP
	 * Java SDK 2.0.0 leaves the JSON-RPC batches that 2025-03-26 requires unparsed, so
	 * they stay out by default.
	 */
	private static final Set<String> SUPERSEDED = Set.of("2025-03-26", "2024-11-05");

	private McpRevisions() {
	}

	/**
	 * Returns the revisions the server accepts, oldest first.
	 * @param allowSuperseded the value of
	 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions}
	 * @return the accepted revisions, in the order the SDK reads them
	 */
	public static List<String> accepted(boolean allowSuperseded) {
		Set<String> revisions = new LinkedHashSet<>(SUPPORTED);
		if (allowSuperseded) {
			revisions.addAll(SUPERSEDED);
		}
		return revisions.stream().sorted().toList();
	}

	/**
	 * Returns the newest revision this release serves.
	 * @return the revision the server answers a handshake with
	 */
	public static String newestSupported() {
		return SUPPORTED.stream().sorted().toList().getLast();
	}

}
