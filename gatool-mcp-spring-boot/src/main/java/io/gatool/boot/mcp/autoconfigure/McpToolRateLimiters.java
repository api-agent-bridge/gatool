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

package io.gatool.boot.mcp.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.ClassUtils;

import io.gatool.boot.autoconfigure.GAToolProperties;
import io.gatool.boot.mcp.internal.limit.BucketToolRateLimiter;
import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.boot.mcp.limit.ToolRateLimiter;

/**
 * Builds the limiter {@link GAToolMcpAutoConfiguration#gaToolRateLimiter} publishes.
 *
 * <p>
 * The class holds the building so that the auto-configuration keeps its bean methods and
 * little else.
 *
 * @author Željko Kozina
 */
final class McpToolRateLimiters {

	private static final String MAX_TRACKED_PAIRS_PROPERTY = "gatool.mcp.rate-limit.max-tracked-pairs";

	private static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";

	private McpToolRateLimiters() {
	}

	/**
	 * Builds the limiter from the rate limit properties, or one that allows every call
	 * under {@code gatool.mcp.security.unsafe.allow-unlimited-tool-calls}.
	 * @param properties the GATool properties
	 * @param beanFactory the bean factory that may hold a meter registry
	 * @return the limiter that every MCP tool call passes
	 */
	static ToolRateLimiter build(GAToolProperties properties, ConfigurableListableBeanFactory beanFactory) {
		if (properties.getMcp().getSecurity().getUnsafe().isAllowUnlimitedToolCalls()) {
			return (caller, toolName) -> RateLimitDecision.allow();
		}
		int callsPerMinute = properties.getMcp().getRateLimit().getCallsPerMinute();
		// Bucket4j refuses a capacity below one when it builds the bucket, which happens
		// on the first tool call, so the caller would read its message as a JSON-RPC
		// error. Checking here reports the property at startup instead.
		if (callsPerMinute < 1) {
			throw new InvalidConfigurationPropertyValueException("gatool.mcp.rate-limit.calls-per-minute",
					callsPerMinute,
					"One caller makes at least one call a minute to one tool. Set a positive "
							+ "number, or turn the limiter off with "
							+ "gatool.mcp.security.unsafe.allow-unlimited-tool-calls.");
		}
		int maxTrackedPairs = properties.getMcp().getRateLimit().getMaxTrackedPairs();
		// Caffeine refuses a negative bound when the cache is built, and a bound of zero
		// would drop every bucket at once, so the limiter would allow every call.
		if (maxTrackedPairs < 1) {
			throw new InvalidConfigurationPropertyValueException(MAX_TRACKED_PAIRS_PROPERTY, maxTrackedPairs,
					"The limiter keeps one bucket per caller and tool pair, and a bound below one would drop every "
							+ "bucket at once. Set it above the number of pairs active within a minute; the "
							+ "default is " + BucketToolRateLimiter.DEFAULT_MAX_TRACKED_PAIRS + ".");
		}
		return new BucketToolRateLimiter(callsPerMinute, maxTrackedPairs, (MeterRegistry) meterRegistry(beanFactory));
	}

	// The registry by class name, so that an application without micrometer-core, which
	// this module declares optional, builds the limiter without a linkage error. The
	// method returns an Object for the same reason: a return type of MeterRegistry would
	// sit in this class's own method table, and Spring Boot reads every declared method
	// of a class to deduce the bean type of a condition, so the class would fail to link.
	// The caller's cast runs on a registry that exists, and then the class exists too.
	private static @Nullable Object meterRegistry(ConfigurableListableBeanFactory beanFactory) {
		if (!ClassUtils.isPresent(METER_REGISTRY, beanFactory.getBeanClassLoader())) {
			return null;
		}
		return beanFactory
			.getBeanProvider(ClassUtils.resolveClassName(METER_REGISTRY, beanFactory.getBeanClassLoader()))
			.getIfAvailable();
	}

}
