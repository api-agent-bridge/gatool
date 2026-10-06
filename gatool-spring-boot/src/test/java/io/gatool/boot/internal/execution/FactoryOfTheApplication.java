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

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * A request factory class of an application's own, with the two setters Spring Boot's
 * reflective builder looks for. The class is public, as an application's is, because that
 * builder calls the setters from its own package.
 */
public final class FactoryOfTheApplication implements ClientHttpRequestFactory {

	private final SimpleClientHttpRequestFactory delegate = new SimpleClientHttpRequestFactory();

	/**
	 * Sets how long a connection may take to open.
	 * @param millis the deadline in milliseconds
	 */
	public void setConnectTimeout(int millis) {
		this.delegate.setConnectTimeout(millis);
	}

	/**
	 * Sets how long a read may wait for data.
	 * @param millis the deadline in milliseconds
	 */
	public void setReadTimeout(int millis) {
		this.delegate.setReadTimeout(millis);
	}

	@Override
	public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
		return this.delegate.createRequest(uri, httpMethod);
	}

}
