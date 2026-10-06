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

package io.gatool.boot.internal.credentials;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The check both per-caller credential strategies run before they read the caller off the
 * thread.
 *
 * <p>
 * A per-caller credential is only as good as the answer to "whose thread is this?". Under
 * Spring Security's default strategy a thread holds a security context because a filter
 * bound it, because context propagation restored it for the running task, or because the
 * application's own code set it. A token found there was therefore put there for the work
 * the thread is doing. Two other strategies break that:
 * {@code MODE_INHERITABLETHREADLOCAL} gives a new thread the context of the thread that
 * created it and lets it keep that context for life, and {@code MODE_GLOBAL} shares one
 * context across the whole JVM.
 *
 * <p>
 * A servlet request bound to the thread is the signal that the thread is serving this
 * call. Under one of those two strategies, a thread without one gets the call refused,
 * because GATool cannot tell a context that belongs to this call from one a pooled thread
 * kept.
 *
 * <p>
 * Two limits the README states. A {@code SecurityContextHolderStrategy} of an
 * application's own class, and a {@code ListeningSecurityContextHolderStrategy} wrapped
 * around another strategy, are unclassifiable here, and GATool treats them as it treats
 * the default. An application that made {@code RequestContextHolder} inheritable, with
 * {@code setThreadContextInheritable(true)} on its {@code RequestContextFilter} or
 * {@code DispatcherServlet}, gives a child thread a bound request and gets past this
 * check.
 *
 * @author Željko Kozina
 */
final class InheritedSecurityContexts {

	// Both classes are package-private in spring-security-core, so the comparison is by
	// the simple name. The value of each entry finishes the sentence "this call runs on
	// thread X under ".
	private static final Map<String, String> SHARED_MODES = Map.of(
			"InheritableThreadLocalSecurityContextHolderStrategy",
			"MODE_INHERITABLETHREADLOCAL, where a pooled thread keeps the security context of the request that "
					+ "created it",
			"GlobalSecurityContextHolderStrategy",
			"MODE_GLOBAL, where one security context is shared by every thread of this JVM");

	private InheritedSecurityContexts() {
	}

	/**
	 * Refuses the call where the strategy in force lets a thread hold another caller's
	 * context and this thread is not serving a request.
	 * @param contextHolderStrategy the strategy the credential reads the caller through
	 * @param strategyName the credential strategy, which the tool error names
	 * @param whatSelectedIt the property and value that chose the credential strategy,
	 * which opens the message
	 */
	static void requireAThreadWhoseContextIsItsOwn(SecurityContextHolderStrategy contextHolderStrategy,
			String strategyName, String whatSelectedIt) {
		@Nullable String mode = SHARED_MODES.get(contextHolderStrategy.getClass().getSimpleName());
		if (mode == null || RequestContextHolder.getRequestAttributes() != null) {
			return;
		}
		throw new CredentialUnavailableException(strategyName, whatSelectedIt + ", and this call runs on thread "
				+ Thread.currentThread().getName() + " under " + mode + ". GATool cannot tell whether the token on "
				+ "this thread belongs to this caller, so the call was not sent. Spring Security's default strategy "
				+ "with spring.reactor.context-propagation=auto carries each caller to the thread its call runs on.",
				null);
	}

}
