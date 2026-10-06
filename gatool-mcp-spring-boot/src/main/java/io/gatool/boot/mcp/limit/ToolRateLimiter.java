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

package io.gatool.boot.mcp.limit;

/**
 * Decides whether one caller may call one tool now. The MCP server asks the limiter
 * before it runs a tool call.
 *
 * <p>
 * An application replaces the built-in limiter by declaring a bean of this type. A
 * deployment behind a load balancer wants that, because a count held in one instance
 * grows more permissive with every instance added, so a shared store belongs in a limiter
 * of the application's own.
 *
 * <p>
 * The caller is a string the adapter supplies: the authenticated principal,
 * {@code Authentication.getName()}, once MCP security is on, and the client address of
 * the request under the unsafe switch, each behind a prefix that tells the two apart.
 * Over stdio every call shares one caller.
 *
 * @author Željko Kozina
 */
@FunctionalInterface
public interface ToolRateLimiter {

	/**
	 * Decides one call.
	 * @param caller the caller the adapter identified
	 * @param toolName the tool being called
	 * @return the decision, which carries the wait when the call is refused
	 */
	RateLimitDecision decide(String caller, String toolName);

}
