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

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * Forwards the token the MCP caller sent to the GraphQL API, which
 * {@code gatool.api.credentials.unsafe.forward-client-tokens} switches on.
 *
 * <p>
 * MCP says a server must not pass through the token it received, because the API then
 * cannot tell the MCP server from the caller and a token minted for one audience reaches
 * another, the confused deputy. The switch exists for an API that is written to take
 * exactly that token, and startup warns while it is on.
 *
 * <p>
 * The token comes from the security context of the thread that runs the tool call, so
 * whose thread it is decides whose token is forwarded. On the MCP surface GATool holds
 * the call to the request thread: it stops startup for an MCP server built without
 * {@code immediateExecution(true)} and refuses a call that arrives on another thread.
 * Here, which the in-process surface reaches as well, two checks run. A call under a
 * security context holder strategy that threads inherit or share, on a thread that is not
 * serving a request, fails with a {@link CredentialUnavailableException}, because the
 * token there can belong to an earlier caller. A call on a thread without the caller's
 * token fails the same way, so the API is called only where a token was there to forward.
 * Startup already refuses this switch over stdio and beside
 * {@code allow-mcp-calls-without-authentication}, so these failures cover a call that
 * arrived some other way. A server with this switch on is outside the MCP specification.
 *
 * @author Željko Kozina
 */
public final class ForwardedTokenCredentialStrategy implements BuiltInCredentialStrategy {

	private final SecurityContextHolderStrategy contextHolderStrategy;

	/**
	 * Creates the strategy.
	 * @param contextHolderStrategy where the chain keeps the caller, which is the
	 * strategy bean of an application that declares one
	 */
	public ForwardedTokenCredentialStrategy(SecurityContextHolderStrategy contextHolderStrategy) {
		this.contextHolderStrategy = contextHolderStrategy;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		// Under a strategy that threads inherit or share, the token on this thread can
		// belong to an earlier caller, and forwarding it would send that caller's token
		// to the API in this caller's name.
		InheritedSecurityContexts.requireAThreadWhoseContextIsItsOwn(this.contextHolderStrategy,
				"forward-client-tokens", "gatool.api.credentials.unsafe.forward-client-tokens is on");
		Authentication authentication = this.contextHolderStrategy.getContext().getAuthentication();
		if (!(authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> bearer)) {
			// A call without a token to forward fails here, ahead of a bare call the API
			// would refuse in its own words.
			throw new CredentialUnavailableException("forward-client-tokens",
					"gatool.api.credentials.unsafe.forward-client-tokens is on, and this call runs on a thread "
							+ "without the caller's token to forward. The switch serves calls that arrive through "
							+ "the secured MCP endpoint; a call from elsewhere needs a strategy of its own.",
					null);
		}
		request.getHeaders().setBearerAuth(bearer.getToken().getTokenValue());
		return execution.execute(request, body);
	}

}
