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

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import io.gatool.boot.internal.credentials.BuiltInCredentialStrategy;

/**
 * The header that authenticates every remote call, which GATool calls the static header
 * strategy.
 *
 * <p>
 * The two components are both {@code String}, so a record names each one at the call site
 * and a swapped pair stops being silent. The value belongs in the environment instead of
 * in a file a repository holds, which the property documentation says as well.
 *
 * @param name the header name, such as {@code Authorization} or {@code X-API-Key}
 * @param value the header value, such as a bearer token or an API key
 * @author Željko Kozina
 */
public record StaticHeaderCredentialStrategy(String name, String value) implements BuiltInCredentialStrategy {

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		request.getHeaders().set(this.name, this.value);
		return execution.execute(request, body);
	}

	// A record prints every component, and the value is a credential, so a bean
	// dump or a log line that prints the strategy would print the key. The name
	// says which header is sent, and a fixed placeholder stands for the value.
	@Override
	public String toString() {
		return "StaticHeaderCredentialStrategy[name=" + this.name + ", value=<redacted>]";
	}
}
