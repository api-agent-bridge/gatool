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

package io.gatool.boot.mcp.internal.limit;

import org.junit.jupiter.api.Test;

import io.gatool.boot.mcp.limit.RateLimitDecision;

import static org.assertj.core.api.Assertions.assertThat;

class BucketToolRateLimiterTests {

	@Test
	void check_callsWithinTheRate_shouldAllowEveryOne() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(3);

		for (int call = 0; call < 3; call++) {
			assertThat(limiter.decide("10.0.0.1", "topRatedMovies").allowed()).isTrue();
		}
	}

	@Test
	void check_callAboveTheRate_shouldRefuseItAndCarryTheWaitAndTheLimit() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(2);
		limiter.decide("10.0.0.1", "topRatedMovies");
		limiter.decide("10.0.0.1", "topRatedMovies");

		RateLimitDecision decision = limiter.decide("10.0.0.1", "topRatedMovies");

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.limit()).isEqualTo("2 calls per minute");
		// Bucket4j refills greedily, so the wait is a fraction of the window, and it
		// stays above zero.
		assertThat(decision.retryAfter()).isPositive();
	}

	@Test
	void check_secondCaller_shouldCountSeparately() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(1);
		limiter.decide("10.0.0.1", "topRatedMovies");

		assertThat(limiter.decide("10.0.0.1", "topRatedMovies").allowed()).isFalse();
		assertThat(limiter.decide("10.0.0.2", "topRatedMovies").allowed()).isTrue();
	}

	@Test
	void check_secondTool_shouldCountSeparately() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(1);
		limiter.decide("10.0.0.1", "topRatedMovies");

		assertThat(limiter.decide("10.0.0.1", "topRatedMovies").allowed()).isFalse();
		assertThat(limiter.decide("10.0.0.1", "movieDetails").allowed()).isTrue();
	}

	@Test
	void check_moreActiveCallersThanTheBound_shouldKeepTheMapAtTheBound() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(10, 4);

		for (int caller = 0; caller < 8; caller++) {
			limiter.decide("10.0.0." + caller, "topRatedMovies");
		}

		// Every bucket here was used inside the window, so all eight stay live and the
		// bound alone does the work. Caffeine holds the cache at its maximum, which is
		// what keeps memory flat as callers arrive.
		assertThat(limiter.size()).isLessThanOrEqualTo(4);
	}

	@Test
	void check_floodOfNewCallers_shouldKeepLimitingTheEstablishedCaller() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(1, 2);
		limiter.decide("10.0.0.1", "topRatedMovies");

		for (int caller = 2; caller < 14; caller++) {
			limiter.decide("10.0.0." + caller, "topRatedMovies");
		}
		limiter.runCacheMaintenance();

		// Caffeine compares an arriving entry against the one it would evict and keeps
		// the entry already there unless the newcomer is busier. The caller who arrived
		// first therefore keeps their bucket through the flood, and their limit holds.
		// A registry in least-recently-used order would drop that caller first and hand
		// them a fresh allowance.
		assertThat(limiter.decide("10.0.0.1", "topRatedMovies").allowed()).isFalse();
	}

}
