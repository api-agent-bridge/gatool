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

import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestAttributes;

/**
 * A request bound to the thread, for the tests of the two per-caller credential
 * strategies.
 *
 * <p>
 * Those strategies read {@code RequestContextHolder.getRequestAttributes()} to tell a
 * thread that is serving a request from a pooled thread. What the attributes hold does
 * not matter to them, and {@code jakarta.servlet} is off this module's test classpath, so
 * this stands in for the servlet attributes Spring's filter binds.
 */
final class BoundRequest implements RequestAttributes {

	@Override
	public @Nullable Object getAttribute(String name, int scope) {
		return null;
	}

	@Override
	public void setAttribute(String name, Object value, int scope) {
		// The strategies under test leave the attributes unread.
	}

	@Override
	public void removeAttribute(String name, int scope) {
		// The strategies under test leave the attributes unread.
	}

	@Override
	public String[] getAttributeNames(int scope) {
		return new String[0];
	}

	@Override
	public void registerDestructionCallback(String name, Runnable callback, int scope) {
		// The strategies under test leave the attributes unread.
	}

	@Override
	public @Nullable Object resolveReference(String key) {
		return null;
	}

	@Override
	public String getSessionId() {
		return "test-session";
	}

	@Override
	public Object getSessionMutex() {
		return this;
	}

}
