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

/**
 * Marks the thread that is serving a request to the MCP endpoint.
 *
 * <p>
 * GATool reads the caller of a tool call from the thread the call runs on: the rate
 * limiter counts per caller there, and token exchange and the forwarded token take the
 * caller's token from there. That holds while the call runs on the thread Spring
 * Security's filter bound the caller to, and a call handed to a pooled thread reads
 * whatever that thread kept from an earlier request.
 *
 * <p>
 * The marker is a plain {@link ThreadLocal}, which is what makes it a reliable answer. A
 * child thread does not inherit it, and Reactor's automatic context propagation carries
 * the thread-locals whose {@code ThreadLocalAccessor} is registered, which this one
 * leaves unregistered, so a pool and Reactor both leave it behind. The compliance filter
 * binds it around the filter chain, and the tool handler refuses a call that arrives
 * without it.
 *
 * @author Željko Kozina
 */
public final class RequestThread {

	private static final ThreadLocal<Boolean> BOUND = new ThreadLocal<>();

	private RequestThread() {
	}

	/**
	 * Marks this thread as one serving a request to the MCP endpoint.
	 */
	public static void bind() {
		BOUND.set(Boolean.TRUE);
	}

	/**
	 * Takes the mark off this thread, which the filter does in a {@code finally} where it
	 * found the thread unmarked.
	 */
	public static void unbind() {
		BOUND.remove();
	}

	/**
	 * Returns whether this thread is serving a request to the MCP endpoint.
	 * @return whether the mark is on this thread
	 */
	public static boolean isBound() {
		return Boolean.TRUE.equals(BOUND.get());
	}

}
