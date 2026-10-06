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

import java.net.URI;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.graphql.GraphQlResponse;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.support.DefaultGraphQlRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.util.AlternativeJdkIdGenerator;
import org.springframework.util.IdGenerator;

import io.gatool.boot.execution.GraphQlExecutionRequest;
import io.gatool.boot.execution.GraphQlExecutor;

/**
 * Runs each document inside the application, on its own Spring for GraphQL beans, which
 * GATool calls embedded mode.
 *
 * <p>
 * The call goes through {@link WebGraphQlHandler} instead of straight to
 * {@code ExecutionGraphQlService}, because an application's {@code WebGraphQlInterceptor}
 * beans apply to a tool call in embedded mode, and because that handler captures the
 * Micrometer {@code ContextSnapshot} that carries the caller's {@code SecurityContext} to
 * the data fetchers. An application without a handler bean, such as one that serves MCP
 * over stdio alone, gets a handler built from its {@code ExecutionGraphQlService}, which
 * the auto-configuration assembles.
 *
 * <p>
 * A tool call arrives without an HTTP request, so the URI, the headers, the cookies and
 * the attributes are built here. They say what the call is: the URI names GATool as the
 * source, and the header set is empty. An interceptor that reads a header therefore finds
 * an empty set in embedded mode, and outbound credentials belong to remote mode, where a
 * header is the transport. Building a synthetic HTTP request from the MCP call would mean
 * inventing header values, which is worse than stating that the call arrived without
 * them.
 *
 * <p>
 * The id comes from {@link AlternativeJdkIdGenerator}, which is the generator Spring's
 * own WebMVC transport uses for the same field, so a log line from a tool call reads like
 * a log line from an HTTP request.
 *
 * @author Željko Kozina
 */
public final class EmbeddedGraphQlExecutor implements GraphQlExecutor {

	private static final URI TOOL_CALL_URI = URI.create("GATool:/tool-call");

	private final WebGraphQlHandler handler;

	private final IdGenerator idGenerator = new AlternativeJdkIdGenerator();

	/**
	 * Creates the executor.
	 * @param handler the application's handler, or one built from its
	 * {@code ExecutionGraphQlService}
	 */
	public EmbeddedGraphQlExecutor(WebGraphQlHandler handler) {
		this.handler = handler;
	}

	@Override
	public GraphQlResponse execute(GraphQlExecutionRequest request) {
		DefaultGraphQlRequest graphQlRequest = new DefaultGraphQlRequest(request.document(),
				request.operationName().isEmpty() ? null : request.operationName(), request.variables(), null);
		// The constructor that leaves the remote address out is deprecated and marked
		// for removal in 2.0.5, so this call passes every component. A tool call
		// arrives without cookies and without a remote address, so both reach the
		// request as null.
		WebGraphQlRequest webRequest = new WebGraphQlRequest(TOOL_CALL_URI, HttpHeaders.EMPTY, null, null, Map.of(),
				graphQlRequest, this.idGenerator.generateId().toString(), LocaleContextHolder.getLocale());
		// A tool call answers one caller and returns one result, so the chain is joined
		// here. Embedded mode runs the call inside the application, which leaves the wait
		// to the application's own data fetchers.
		//
		// The join runs without a duration, and that is deliberate. The fetchers waiting
		// carry their own timeouts, and a duration here would be a number GATool invented
		// and the caller cannot see. A data fetcher that hangs therefore holds this
		// thread, and the bound is whatever the application sets where the work happens.
		// A call deadline of GATool's own is missing from this release.
		WebGraphQlResponse response = this.handler.handleRequest(webRequest).block();
		if (response == null) {
			throw new IllegalStateException(
					"The GraphQL engine completed without a response for operation " + request.operationName());
		}
		return response;
	}

}
