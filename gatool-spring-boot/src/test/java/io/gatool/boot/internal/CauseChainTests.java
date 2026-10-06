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

package io.gatool.boot.internal;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CauseChainTests {

	@Test
	void first_causeOfTheTypeBelowTwoWrappers_shouldReturnThatCause() {
		IOException dropped = new IOException("Connection reset");
		Throwable failure = new IllegalStateException("outer", new RuntimeException("middle", dropped));

		assertThat(CauseChain.first(failure, IOException.class)).isSameAs(dropped);
	}

	@Test
	void first_failureItselfOfTheType_shouldReturnTheFailure() {
		IOException failure = new IOException("Connection reset");

		assertThat(CauseChain.first(failure, IOException.class)).isSameAs(failure);
	}

	@Test
	void first_chainWithoutTheType_shouldReturnNull() {
		Throwable failure = new IllegalStateException("outer", new RuntimeException("inner"));

		assertThat(CauseChain.first(failure, IOException.class)).isNull();
	}

	@Test
	void any_chainThatPointsBackAtItself_shouldEndWithoutAMatch() {
		// getCause() can be overridden, so a chain can be a loop. The walk is bounded
		// for that reason, and this test would hang without the bound.
		Throwable loop = new RuntimeException("loop") {

			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		assertThat(CauseChain.any(loop, IOException.class::isInstance)).isFalse();
	}

	@Test
	void any_causeBelowTheDepthTheWalkReaches_shouldStayUnfound() {
		// The walk reads the failure and nine causes below it.
		Throwable failure = new IOException("deepest");
		for (int wrappers = 0; wrappers < CauseChain.MAX_DEPTH; wrappers++) {
			failure = new RuntimeException("wrapper " + wrappers, failure);
		}

		assertThat(CauseChain.any(failure, IOException.class::isInstance)).isFalse();
		assertThat(CauseChain.any(failure.getCause(), IOException.class::isInstance)).isTrue();
	}

}
