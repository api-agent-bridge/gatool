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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the bound on caller and tool pairs does to a caller inside its window.
 *
 * <p>
 * Every pair holds a Bucket4j bucket in a Caffeine cache that {@code maximumSize} bounds,
 * and a bucket evicted inside its window comes back fresh, so the caller gets a whole
 * allowance again. The default bound is 100,000,
 * {@code gatool.mcp.rate-limit.max-tracked-pairs} sets it, and an eviction inside the
 * window counts into {@code gatool.rate-limit.evictions}.
 *
 * <p>
 * Each caller spends its whole allowance, then asks for a second one. A kept bucket
 * admits the tokens Bucket4j refilled meanwhile, one a second at 60 calls a minute, so a
 * second round of 60 calls tells a kept bucket (a few allowed) from a fresh one (all 60).
 */
class BucketToolRateLimiterBoundTests {

	private static final int CALLS_PER_MINUTE = 60;

	private static final String TOOL = "topRatedMovies";

	@Test
	void decide_tenThousandAndOneCallersEachAtTheirLimit_shouldKeepEveryBucketUnderTheDefaultBound() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(CALLS_PER_MINUTE);
		int callers = 10_001;
		for (int caller = 0; caller < callers; caller++) {
			spend(limiter, "caller-" + caller);
		}
		limiter.runCacheMaintenance();

		assertThat(limiter.size()).isEqualTo(callers);
		int freshAllowances = 0;
		for (int caller = 0; caller < callers; caller++) {
			// Both rounds take a few seconds, and a kept bucket refills one token a
			// second, so a caller allowed more than half of a fresh allowance was handed
			// a fresh bucket.
			if (spend(limiter, "caller-" + caller) > CALLS_PER_MINUTE / 2) {
				freshAllowances++;
			}
		}
		assertThat(freshAllowances).as("callers handed a fresh allowance inside their window").isZero();
	}

	@Test
	void decide_moreLivePairsThanTheBound_shouldCountEachEvictionInsideTheWindow() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(CALLS_PER_MINUTE, 100, registry);
		int callers = 300;
		for (int caller = 0; caller < callers; caller++) {
			spend(limiter, "caller-" + caller);
		}
		limiter.runCacheMaintenance();

		long size = limiter.size();
		assertThat(size).isLessThanOrEqualTo(100);
		// Every bucket here spent its allowance moments ago, so every eviction dropped a
		// caller inside its window. Caffeine may refuse a newcomer straight away, whose
		// caller then gets a fresh bucket on the next call, so the count can exceed the
		// difference.
		assertThat(registry.get("gatool.rate-limit.evictions").counter().count())
			.isGreaterThanOrEqualTo(callers - size);
	}

	@Test
	void decide_bucketsUnderTheBound_shouldLeaveTheCounterAtZero() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(CALLS_PER_MINUTE, 100, registry);
		for (int caller = 0; caller < 50; caller++) {
			spend(limiter, "caller-" + caller);
		}
		limiter.runCacheMaintenance();

		assertThat(registry.get("gatool.rate-limit.evictions").counter().count()).isZero();
	}

	@Test
	void decide_withoutAMeterRegistry_shouldEvictAtTheBoundAllTheSame() {
		BucketToolRateLimiter limiter = new BucketToolRateLimiter(CALLS_PER_MINUTE, 100, null);
		for (int caller = 0; caller < 300; caller++) {
			spend(limiter, "caller-" + caller);
		}
		limiter.runCacheMaintenance();

		assertThat(limiter.size()).isLessThanOrEqualTo(100);
	}

	private static int spend(BucketToolRateLimiter limiter, String caller) {
		int allowed = 0;
		for (int call = 0; call < CALLS_PER_MINUTE; call++) {
			if (limiter.decide(caller, TOOL).allowed()) {
				allowed++;
			}
		}
		return allowed;
	}

}
