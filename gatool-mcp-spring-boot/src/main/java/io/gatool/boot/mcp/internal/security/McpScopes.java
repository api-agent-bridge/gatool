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

package io.gatool.boot.mcp.internal.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.gatool.core.model.GATool;

/**
 * The scopes the MCP endpoint checks: the baseline every call needs, and what each tool
 * needs on top of it.
 *
 * <p>
 * Every MCP call needs all of the baseline scopes, which the protected resource metadata
 * and every 401 publish so that a client asks for them at sign-in. A tool that lists
 * scopes needs all of those as well, and a 401 or 403 that answers a {@code tools/call}
 * names them, so a client can step up. With
 * {@code gatool.mcp.security.unsafe.request-every-scope-at-sign-in} on, the metadata and
 * every 401 publish every scope this server knows, for clients whose step-up fails.
 *
 * @author Željko Kozina
 */
public final class McpScopes {

	private final List<String> baseline;

	private final Map<String, List<String>> toolScopes;

	private final List<String> all;

	private final boolean publishEveryScopeAtSignIn;

	private McpScopes(List<String> baseline, Map<String, List<String>> toolScopes, boolean publishEveryScopeAtSignIn) {
		this.baseline = List.copyOf(baseline);
		this.toolScopes = Map.copyOf(toolScopes);
		Set<String> everyScope = new LinkedHashSet<>(baseline);
		toolScopes.values().forEach(everyScope::addAll);
		this.all = List.copyOf(everyScope);
		this.publishEveryScopeAtSignIn = publishEveryScopeAtSignIn;
	}

	/**
	 * Reads the scopes of one server.
	 * @param baseline the value of {@code gatool.mcp.security.baseline-scopes}
	 * @param tools the MCP tools, whose declared scopes join the baseline
	 * @param publishEveryScopeAtSignIn the value of
	 * {@code gatool.mcp.security.unsafe.request-every-scope-at-sign-in}
	 * @return the scopes
	 */
	public static McpScopes of(List<String> baseline, Collection<GATool> tools, boolean publishEveryScopeAtSignIn) {
		Map<String, List<String>> toolScopes = new LinkedHashMap<>();
		for (GATool tool : tools) {
			List<String> scopes = tool.scopes();
			if (scopes != null) {
				toolScopes.put(tool.name(), List.copyOf(scopes));
			}
		}
		return new McpScopes(baseline, toolScopes, publishEveryScopeAtSignIn);
	}

	/**
	 * Returns the scopes every call needs.
	 * @return the baseline scopes
	 */
	public List<String> baseline() {
		return this.baseline;
	}

	/**
	 * Returns the scopes one tool lists, on top of the baseline.
	 * @param toolName the tool
	 * @return its own scopes, empty for a tool without a list or an unknown one
	 */
	public List<String> toolScopes(String toolName) {
		return this.toolScopes.getOrDefault(toolName, List.of());
	}

	/**
	 * Returns whether one tool is known here, with a scope list of its own.
	 * @param toolName the tool
	 * @return {@code true} for a tool whose operation file lists scopes, an empty list
	 * included
	 */
	public boolean hasScopeListFor(String toolName) {
		return this.toolScopes.containsKey(toolName);
	}

	/**
	 * Returns every scope a call needs: the baseline, plus the named tool's own.
	 * @param toolName the tool a {@code tools/call} names, or {@code null} for every
	 * other call
	 * @return the scopes, in the order the baseline and the tool list them
	 */
	public List<String> requiredFor(@Nullable String toolName) {
		if (toolName == null) {
			return this.baseline;
		}
		Set<String> required = new LinkedHashSet<>(this.baseline);
		required.addAll(toolScopes(toolName));
		return List.copyOf(required);
	}

	/**
	 * Returns the scopes a client is told to ask for at sign-in.
	 * @param toolName the tool a {@code tools/call} names, or {@code null} for every
	 * other call
	 * @return the baseline and the tool's own, or every scope this server knows while the
	 * unsafe switch is on
	 */
	public List<String> publishedAtSignIn(@Nullable String toolName) {
		return this.publishEveryScopeAtSignIn ? this.all : requiredFor(toolName);
	}

	/**
	 * Returns every scope this server knows: the baseline and each tool's own.
	 * @return the scopes, the baseline first
	 */
	public List<String> all() {
		return this.all;
	}

	/**
	 * Returns the part of one scope list this server knows.
	 * @param held the scopes a token carries
	 * @return those of them in {@link #all()}, in this server's order
	 */
	public List<String> knownAmong(Collection<String> held) {
		List<String> known = new ArrayList<>();
		for (String scope : this.all) {
			if (held.contains(scope)) {
				known.add(scope);
			}
		}
		return known;
	}

	/**
	 * Returns whether every scope is published at sign-in.
	 * @return the unsafe switch
	 */
	public boolean publishEveryScopeAtSignIn() {
		return this.publishEveryScopeAtSignIn;
	}

}
