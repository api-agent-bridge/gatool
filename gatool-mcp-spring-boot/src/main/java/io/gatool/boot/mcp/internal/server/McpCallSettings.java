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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;

import io.gatool.boot.mcp.limit.ToolRateLimiter;

/**
 * What every MCP tool call passes through, beside the tool itself.
 *
 * <p>
 * These settings travel together, so the specification builders take them as one
 * argument. Each one belongs to MCP alone: the rate limit and the observation cover MCP
 * tool calls, and in-process tools keep Spring AI's own limiter-free path and its
 * {@code spring.ai.tool} observation.
 *
 * @param rateLimiter decides whether the caller may call the tool now
 * @param caller names the caller that the limit counts against
 * @param observationRegistry records one observation per call, and is
 * {@link ObservationRegistry#NOOP} in an application without one
 * @param includeContent whether the arguments and the result join the observation, which
 * stays off by default
 * @param grantedScopes the scopes the process that started a stdio server holds, from
 * {@code gatool.mcp.stdio.granted-scopes}, which a call needs to cover the tool's own
 * scopes, or {@code null} where Spring Security decides the call before it arrives
 * @param toolResponseMimeTypes the media type each tool answers with, read from
 * {@code spring.ai.mcp.server.tool-response-mime-type}, so a tool built from an operation
 * file honours the same setting as a tool an application writes itself
 * @param requiresTheRequestThread whether a call has to arrive on the thread that served
 * its HTTP request, which holds for a servlet application serving the MCP endpoint and is
 * off over stdio, where one process is the only caller
 * @author Željko Kozina
 */
public record McpCallSettings(ToolRateLimiter rateLimiter, Supplier<String> caller,
		ObservationRegistry observationRegistry, boolean includeContent, Map<String, String> toolResponseMimeTypes,
		@Nullable Set<String> grantedScopes, boolean requiresTheRequestThread) {

	public McpCallSettings {
		toolResponseMimeTypes = Map.copyOf(toolResponseMimeTypes);
		grantedScopes = (grantedScopes != null) ? Collections.unmodifiableSet(new LinkedHashSet<>(grantedScopes))
				: null;
	}

	/**
	 * Creates the policy of a server whose scope check runs elsewhere, which is every
	 * server over HTTP, where Spring Security decides before the call.
	 */
	public McpCallSettings(ToolRateLimiter rateLimiter, Supplier<String> caller,
			ObservationRegistry observationRegistry, boolean includeContent,
			Map<String, String> toolResponseMimeTypes) {
		this(rateLimiter, caller, observationRegistry, includeContent, toolResponseMimeTypes, null, false);
	}

	/**
	 * Returns this policy with the scopes a stdio process holds.
	 * @param grantedScopes the scopes, or {@code null} where Spring Security decides
	 * @return the policy
	 */
	public McpCallSettings withGrantedScopes(@Nullable Set<String> grantedScopes) {
		return new McpCallSettings(this.rateLimiter, this.caller, this.observationRegistry, this.includeContent,
				this.toolResponseMimeTypes, grantedScopes, this.requiresTheRequestThread);
	}

	/**
	 * Returns this policy with the rule that a call arrives on the request thread.
	 * @param requiresTheRequestThread whether the rule applies
	 * @return the policy
	 */
	public McpCallSettings requiringTheRequestThread(boolean requiresTheRequestThread) {
		return new McpCallSettings(this.rateLimiter, this.caller, this.observationRegistry, this.includeContent,
				this.toolResponseMimeTypes, this.grantedScopes, requiresTheRequestThread);
	}
}
