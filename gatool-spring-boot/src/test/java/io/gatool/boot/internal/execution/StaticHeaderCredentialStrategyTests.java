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

package io.gatool.boot.internal.execution;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static header strategy sends its header on every call and keeps the value out of
 * its own string form.
 */
class StaticHeaderCredentialStrategyTests {

	@Test
	void toString_shouldNameTheHeaderAndHideTheValue() {
		// A record prints every component, and the value is a credential a bean dump
		// or a log line would otherwise print.
		StaticHeaderCredentialStrategy strategy = new StaticHeaderCredentialStrategy("X-API-Key", "s3cret-value");

		assertThat(strategy.toString()).contains("X-API-Key").contains("<redacted>").doesNotContain("s3cret-value");
	}

	@Test
	void intercept_shouldSetTheHeaderOnTheRequest() throws IOException {
		StaticHeaderCredentialStrategy strategy = new StaticHeaderCredentialStrategy("X-API-Key", "s3cret-value");
		MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://api/graphql"));

		strategy.intercept(request, "{}".getBytes(StandardCharsets.UTF_8), (sent, body) -> {
			assertThat(sent.getHeaders().getFirst("X-API-Key")).isEqualTo("s3cret-value");
			return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
		});

		assertThat(request.getHeaders().getFirst("X-API-Key")).isEqualTo("s3cret-value");
	}

}
