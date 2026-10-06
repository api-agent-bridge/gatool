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

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * What a {@link ToolRateLimiter} decided about one call.
 *
 * <p>
 * A refusal carries the wait and the limit in words, because both belong in the message
 * the model reads, such as "Rate limit reached for movieDetails: 60 calls per minute.
 * Retry after 12 seconds." An agent in a loop acts on those numbers, where a bare refusal
 * leaves it guessing.
 *
 * <p>
 * The limit is a phrase instead of a rate, so a limiter with another shape, such as a
 * daily quota or a shared store, describes itself in its own words.
 *
 * @param allowed whether the call may run
 * @param retryAfter how long until the next call succeeds, which is {@link Duration#ZERO}
 * for an allowed call
 * @param limit the limit in words, such as "60 calls per minute", or {@code null} for an
 * allowed call
 * @author Željko Kozina
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter, @Nullable String limit) {

	private static final RateLimitDecision ALLOW = new RateLimitDecision(true, Duration.ZERO, null);

	/**
	 * Returns the decision that lets a call run.
	 *
	 * <p>
	 * The two factories read as verbs, because the component {@code allowed} already
	 * gives this record an accessor of that name, and a static method sharing it fails to
	 * compile.
	 * @return the decision that allows the call
	 */
	public static RateLimitDecision allow() {
		return ALLOW;
	}

	/**
	 * Returns a refusal that carries the wait and the limit.
	 * @param retryAfter how long until the next call succeeds
	 * @param limit the limit in words, such as "60 calls per minute"
	 * @return the decision that refuses the call
	 */
	public static RateLimitDecision refuse(Duration retryAfter, String limit) {
		return new RateLimitDecision(false, retryAfter, limit);
	}
}
