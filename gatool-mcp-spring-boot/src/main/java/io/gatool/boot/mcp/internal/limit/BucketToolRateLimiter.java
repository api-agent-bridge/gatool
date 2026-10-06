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

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.boot.mcp.limit.ToolRateLimiter;

/**
 * Counts calls per caller and tool in memory, with one Bucket4j token bucket behind each
 * pair.
 *
 * <p>
 * Bucket4j's {@code Bucket.tryConsumeAndReturnRemaining(long)} returns a
 * {@link ConsumptionProbe} carrying {@code getNanosToWaitForRefill()}. GATool puts that
 * wait in the message the model reads, and a refused call leaves the token count as it
 * was.
 *
 * <p>
 * Bucket4j leaves the registry to its caller, so the buckets live in a Caffeine cache
 * that {@code maximumSize} bounds and {@code expireAfterAccess} frees. A bucket refills
 * fully within the window, so a caller whose entry left the cache starts their next call
 * with the tokens a fresh bucket carries. Caffeine batches the work that enforces the
 * bound, so the cache sits near that number, and a burst of new callers holds it above
 * the bound until the next batch runs.
 *
 * <p>
 * The bound is {@code gatool.mcp.rate-limit.max-tracked-pairs}, 100,000 by default. A
 * bucket is a few hundred bytes, so the default costs about 30 MB when every pair is
 * live, and {@code expireAfterAccess} keeps the live set at the number of distinct pairs
 * seen within a minute. Above the bound Caffeine evicts a live bucket for each newcomer,
 * and the evicted caller reads a fresh allowance on the next call: the limiter fails
 * open, one caller at a time. Each eviction of a bucket still inside its window, one
 * whose tokens are below the allowance, counts into the Micrometer counter
 * {@code gatool.rate-limit.evictions} while a registry is available, and the log carries
 * one warning a minute naming the property.
 *
 * <p>
 * Caffeine compares an arriving entry against the one it would evict and keeps the entry
 * already there unless the newcomer is busier. A flood of fresh callers therefore leaves
 * an established caller's bucket in place while the frequency sketch has seen it; the
 * sketch is sized as the cache fills, so a caller that spent its allowance while the
 * cache was nearly empty looks as cold as a newcomer.
 *
 * <p>
 * Caffeine covers what a registry written here would need: an idle sweep, a
 * least-recently-used pass, a timestamp on every entry and a guard so that one thread
 * sweeps at a time. Spring Boot manages the version, and the MCP starter brings the
 * library as it brings Bucket4j.
 *
 * <p>
 * The count lives in one application instance. A deployment behind a load balancer
 * therefore allows the rate once per instance, and such a deployment supplies a
 * {@link ToolRateLimiter} bean with a shared store.
 *
 * @author Željko Kozina
 */
public final class BucketToolRateLimiter implements ToolRateLimiter {

	/**
	 * The default of {@code gatool.mcp.rate-limit.max-tracked-pairs}.
	 */
	public static final int DEFAULT_MAX_TRACKED_PAIRS = 100_000;

	/**
	 * The name of the counter that counts buckets evicted inside their window.
	 */
	public static final String EVICTIONS_METER = "gatool.rate-limit.evictions";

	static final String MAX_TRACKED_PAIRS_PROPERTY = "gatool.mcp.rate-limit.max-tracked-pairs";

	private static final Log logger = LogFactory.getLog(BucketToolRateLimiter.class);

	private static final Duration WINDOW = Duration.ofMinutes(1);

	private final int callsPerMinute;

	private final int maxTrackedPairs;

	private final Cache<Key, Bucket> buckets;

	private final @Nullable Runnable evictionCounter;

	// When the next warning may be written, in System.nanoTime() terms. It starts at
	// construction time, so the first eviction warns.
	private final AtomicLong nextWarningNanos = new AtomicLong(System.nanoTime());

	/**
	 * Creates the limiter with the default bound and without a counter.
	 * @param callsPerMinute the value of {@code gatool.mcp.rate-limit.calls-per-minute}
	 */
	public BucketToolRateLimiter(int callsPerMinute) {
		this(callsPerMinute, DEFAULT_MAX_TRACKED_PAIRS, null);
	}

	// Package-private, so that the eviction tests set a small bound instead of
	// building a hundred thousand buckets.
	BucketToolRateLimiter(int callsPerMinute, int maxTrackedPairs) {
		this(callsPerMinute, maxTrackedPairs, null);
	}

	/**
	 * Creates the limiter.
	 * @param callsPerMinute the value of {@code gatool.mcp.rate-limit.calls-per-minute}
	 * @param maxTrackedPairs the value of {@code gatool.mcp.rate-limit.max-tracked-pairs}
	 * @param meterRegistry the registry that receives
	 * {@code gatool.rate-limit.evictions}, or null to count evictions in the log alone
	 */
	public BucketToolRateLimiter(int callsPerMinute, int maxTrackedPairs, @Nullable MeterRegistry meterRegistry) {
		this.callsPerMinute = callsPerMinute;
		this.maxTrackedPairs = maxTrackedPairs;
		this.evictionCounter = (meterRegistry != null) ? MicrometerEvictions.register(meterRegistry) : null;
		// Runnable::run keeps Caffeine's maintenance on the calling thread. The default
		// runs it on the common ForkJoinPool, and a starter that takes a thread from the
		// application's pool to bound its own cache spends something the application
		// budgeted for its own work. The eviction listener runs inside that maintenance.
		this.buckets = Caffeine.newBuilder()
			.maximumSize(maxTrackedPairs)
			.expireAfterAccess(WINDOW)
			.executor(Runnable::run)
			.evictionListener(
					(@Nullable Key key, @Nullable Bucket bucket, RemovalCause cause) -> onEviction(bucket, cause))
			.build();
	}

	@Override
	public RateLimitDecision decide(String caller, String toolName) {
		// Caffeine declares this method nullable, because a mapping function is allowed
		// to return null. This one answers every key with a bucket.
		Bucket bucket = Objects.requireNonNull(this.buckets.get(new Key(caller, toolName), (key) -> newBucket()));
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return RateLimitDecision.allow();
		}
		return RateLimitDecision.refuse(Duration.ofNanos(probe.getNanosToWaitForRefill()),
				this.callsPerMinute + " calls per minute");
	}

	/**
	 * Returns how many buckets the limiter holds, which the eviction tests read.
	 * @return the number of caller and tool pairs with a bucket
	 */
	long size() {
		runCacheMaintenance();
		return this.buckets.estimatedSize();
	}

	// Caffeine batches its maintenance, so a cache that has just passed its bound holds
	// the extra entries until the next batch. The eviction tests ask for that work
	// directly, where an application reaches it through the calls that follow.
	void runCacheMaintenance() {
		this.buckets.cleanUp();
	}

	private Bucket newBucket() {
		return Bucket.builder()
			.addLimit((limit) -> limit.capacity(this.callsPerMinute).refillGreedy(this.callsPerMinute, WINDOW))
			.build();
	}

	// A bucket that expired sat idle for the whole window, so it reads full again and its
	// caller stands where a fresh bucket would put them. A bucket evicted for the bound
	// with tokens missing belongs to a caller inside its window, who gets a fresh
	// allowance on the next call, and that is the case worth counting.
	private void onEviction(@Nullable Bucket bucket, RemovalCause cause) {
		if (bucket == null || cause != RemovalCause.SIZE || bucket.getAvailableTokens() >= this.callsPerMinute) {
			return;
		}
		if (this.evictionCounter != null) {
			this.evictionCounter.run();
		}
		warnOncePerMinute();
	}

	private void warnOncePerMinute() {
		long now = System.nanoTime();
		long next = this.nextWarningNanos.get();
		if (now - next < 0) {
			return;
		}
		if (this.nextWarningNanos.compareAndSet(next, now + WINDOW.toNanos())) {
			logger.warn("The rate limiter dropped the bucket of a caller still inside its window, because more than "
					+ this.maxTrackedPairs + " caller and tool pairs were active within a minute, and that caller "
					+ "starts the next call with a fresh allowance. Raise " + MAX_TRACKED_PAIRS_PROPERTY
					+ " above the number of pairs active in a minute; the counter " + EVICTIONS_METER
					+ " counts these drops.");
		}
	}

	private record Key(String caller, String toolName) {
	}

	// Micrometer stays inside this class, so that the limiter loads on a classpath
	// without micrometer-core, which this module declares optional.
	private static final class MicrometerEvictions {

		private MicrometerEvictions() {
		}

		static Runnable register(MeterRegistry meterRegistry) {
			Counter counter = Counter.builder(EVICTIONS_METER)
				.description("Buckets of callers inside their window that the rate limiter dropped at "
						+ MAX_TRACKED_PAIRS_PROPERTY)
				.register(meterRegistry);
			return counter::increment;
		}

	}

}
