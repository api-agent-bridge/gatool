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

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mark stays on the thread it was put on.
 *
 * <p>
 * The refusal of a call off the request thread rests on that: a pooled thread and a
 * thread a request started both have to read the mark as absent, whatever Spring
 * Security's context holder strategy does.
 */
class RequestThreadTests {

	@AfterEach
	void unbind() {
		RequestThread.unbind();
	}

	@Test
	void isBound_onTheThreadThatBoundIt_shouldBeTrue() {
		RequestThread.bind();

		assertThat(RequestThread.isBound()).isTrue();
	}

	@Test
	void isBound_afterUnbind_shouldBeFalse() {
		RequestThread.bind();
		RequestThread.unbind();

		assertThat(RequestThread.isBound()).isFalse();
	}

	@Test
	void isBound_onAChildThread_shouldBeFalse() throws Exception {
		// A plain ThreadLocal is what an inheritable one is compared with here: a thread
		// started while the mark is on reads it as absent, which is what tells a pooled
		// thread from the thread that served the request.
		RequestThread.bind();
		AtomicBoolean boundOnTheChild = new AtomicBoolean(true);

		Thread child = new Thread(() -> boundOnTheChild.set(RequestThread.isBound()));
		child.start();
		child.join(10_000);

		assertThat(child.isAlive()).isFalse();
		assertThat(boundOnTheChild).isFalse();
	}

}
