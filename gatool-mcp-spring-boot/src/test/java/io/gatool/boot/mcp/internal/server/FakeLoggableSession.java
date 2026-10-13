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

package io.gatool.boot.mcp.internal.server;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpLoggableSession;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * A session whose every request is answered by one supplier, so a test builds a real
 * {@code McpAsyncServerExchange} over it and reads what the server sent.
 */
final class FakeLoggableSession implements McpLoggableSession {

	private final Supplier<Mono<?>> answer;

	final AtomicInteger requests = new AtomicInteger();

	final AtomicReference<@Nullable Object> lastParams = new AtomicReference<>();

	FakeLoggableSession(Supplier<Mono<?>> answer) {
		this.answer = answer;
	}

	static FakeLoggableSession answering(McpSchema.ElicitResult.Action action) {
		return new FakeLoggableSession(() -> Mono.just(new McpSchema.ElicitResult(action, Map.of(), null)));
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
		this.requests.incrementAndGet();
		this.lastParams.set(requestParams);
		return (Mono<T>) this.answer.get();
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		return Mono.empty();
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@Override
	public void close() {
	}

	@Override
	public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
	}

	@Override
	public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
		return true;
	}

}
