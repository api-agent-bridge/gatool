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

package io.gatool.boot.mcp.internal.security;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bytes of each challenge: the scopes a 403 names, the plain denial, and a
 * description an application's own code wrote outside the characters a header may carry.
 */
class McpBearerChallengesTests {

	private final McpScopes scopes = McpScopes.of(List.of("mcp:tools"),
			List.of(tool("topRatedMovies", List.of("movies:read")), tool("reviews", List.of("reviews:moderate"))),
			false);

	private final McpBearerChallenges challenges = new McpBearerChallenges(McpEndpointPaths.of(new MockEnvironment()),
			this.scopes, null, SecurityContextHolder.getContextHolderStrategy());

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void deny_tokenWithoutAScopeThisServerDefines_shouldSayThatItDoesNotHoldAnyOfThem() throws Exception {
		signIn();
		MockHttpServletRequest request = call("reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		McpCallAuthorizationManager.ScopeDecision decision = new McpCallAuthorizationManager.ScopeDecision("reviews",
				List.of("mcp:tools", "reviews:moderate"), List.of(), List.of("mcp:tools", "reviews:moderate"));

		this.challenges.accessDeniedHandler()
			.handle(request, response, new AuthorizationDeniedException("Access Denied", decision));

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
			.contains("error_description=\"reviews needs mcp:tools reviews:moderate. The token does not hold "
					+ "any of them.\"");
	}

	@Test
	void deny_scopeDecision_shouldNameRequiredAndHeldScopesOnceEach() throws Exception {
		signIn();
		// A token that holds movies:read from an earlier step-up calls reviews: the
		// challenge asks for what the call needs and keeps what the token had, so a
		// client signing in again keeps everything it had.
		MockHttpServletRequest request = call("reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		McpCallAuthorizationManager.ScopeDecision decision = new McpCallAuthorizationManager.ScopeDecision("reviews",
				List.of("mcp:tools", "reviews:moderate"), List.of("mcp:tools", "movies:read"),
				List.of("reviews:moderate"));

		this.challenges.accessDeniedHandler()
			.handle(request, response, new AuthorizationDeniedException("Access Denied", decision));

		String challenge = response.getHeader(HttpHeaders.WWW_AUTHENTICATE);
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(challenge).startsWith("Bearer error=\"insufficient_scope\"")
			.contains("scope=\"mcp:tools reviews:moderate movies:read\"")
			.contains("error_description=\"reviews needs mcp:tools reviews:moderate. The token holds mcp:tools "
					+ "movies:read and lacks reviews:moderate.\"")
			.doesNotContain("Correlation id")
			.contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");
	}

	@Test
	void deny_refusal_shouldAnswer500AsAJsonRpcErrorWithoutAChallenge() throws Exception {
		// A refusal is this server's own misconfiguration. A 403 insufficient_scope would
		// send the client to sign in for a scope that cannot help, and the shape is the
		// one every other refusal on this endpoint has.
		signIn();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.challenges.accessDeniedHandler()
			.handle(new MockHttpServletRequest("POST", "/mcp"), response,
					new AuthorizationDeniedException("Access Denied",
							new McpCallAuthorizationManager.RefusalAheadOfScopes(
									"The request was not read by the MCP compliance " + "filter.")));

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
		assertThat(response.getContentType()).startsWith("application/json");
		assertThat(response.getContentAsString()).isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":"
				+ "-32603,\"message\":\"The request was not read by the MCP compliance filter.\"}}");
	}

	@Test
	void deny_callerLeftUnauthenticated_shouldAnswer401LikeTheEntryPoint() throws Exception {
		TestingAuthenticationToken caller = new TestingAuthenticationToken("agent", "n/a");
		caller.setAuthenticated(false);
		SecurityContextHolder.getContext().setAuthentication(caller);
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.challenges.accessDeniedHandler()
			.handle(call("topRatedMovies"), response, new AuthorizationDeniedException("Access Denied",
					new McpCallAuthorizationManager.RefusalAheadOfScopes("The call needs a bearer token.")));

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	void commence_errorUriWithASpace_shouldDropIt() throws Exception {
		// A description may carry a space; a URI parameter may not.
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2Error error = new OAuth2Error("invalid_token", "expired", "https://x y");

		this.challenges.entryPoint()
			.commence(new MockHttpServletRequest("POST", "/mcp"), response, new OAuth2AuthenticationException(error));

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error_description=\"expired\"")
			.doesNotContain("error_uri");
	}

	@Test
	void commence_behindAContextPath_shouldNameTheMetadataUnderIt() throws Exception {
		// Spring's metadata filter matches within the application, so the document sits
		// under the context path, and the challenge has to send the client there.
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/mcp");
		request.setContextPath("/app");
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.challenges.entryPoint()
			.commence(request, response, new InsufficientAuthenticationException("Full authentication is required"));

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
			.contains("resource_metadata=\"http://localhost/app/.well-known/oauth-protected-resource/mcp\"");
	}

	@Test
	void deny_withoutAnAuthentication_shouldAnswer401LikeTheEntryPoint() throws Exception {
		// A chain with anonymous authentication off hands a caller without a token to
		// the denied handler, and the answer has to be the sign-in challenge.
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.challenges.accessDeniedHandler()
			.handle(call("topRatedMovies"), response, new AccessDeniedException("Access Denied"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
			.startsWith("Bearer scope=\"mcp:tools movies:read\"")
			.doesNotContain("insufficient_scope");
	}

	@Test
	void deny_decisionNamingAToolThisServerLacks_shouldLeaveTheNameOutOfTheHeader() throws Exception {
		// The manager names the tool a call asked for. Where the tool is undeclared here
		// the name is the caller's own text, so the challenge describes the endpoint.
		signIn();
		MockHttpServletResponse response = new MockHttpServletResponse();
		McpCallAuthorizationManager.ScopeDecision decision = new McpCallAuthorizationManager.ScopeDecision("madeUpTool",
				List.of("mcp:tools"), List.of(), List.of("mcp:tools"));

		this.challenges.accessDeniedHandler()
			.handle(call("madeUpTool"), response, new AuthorizationDeniedException("Access Denied", decision));

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
			.contains("error_description=\"This endpoint needs mcp:tools.")
			.doesNotContain("madeUpTool");
	}

	@Test
	void deny_plainAccessDenied_shouldNameTheBaselineAndAFixedDescription() throws Exception {
		signIn();
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.challenges.accessDeniedHandler()
			.handle(call("topRatedMovies"), response, new AccessDeniedException("elsewhere"));

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("scope=\"mcp:tools movies:read\"")
			.contains("error_description=\"The token lacks a scope this endpoint requires.\"");
	}

	@Test
	void commence_withoutAToken_shouldNameTheScopesToAskForAndTheMetadata() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		this.challenges.entryPoint()
			.commence(call("topRatedMovies"), response,
					new InsufficientAuthenticationException("Full authentication is required"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer ")
			.contains("scope=\"mcp:tools movies:read\"")
			.contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"")
			.doesNotContain("error=");
	}

	@Test
	void commence_errorDescriptionOutsideTheHeaderCharacters_shouldReplaceItAndDropTheUri() throws Exception {
		// An error an application's own code built, with a quote and a line break in
		// the description and a URI with a backslash: neither may reach the header as
		// it is, so the description becomes a fixed sentence and the URI stays out.
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2Error error = new OAuth2Error("invalid_token", "bad \"quote\"\nand line", "https://x\\y");

		this.challenges.entryPoint()
			.commence(new MockHttpServletRequest("POST", "/mcp"), response, new OAuth2AuthenticationException(error));

		assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"")
			.contains("error_description=\"The token was refused.\"")
			.doesNotContain("error_uri")
			.doesNotContain("\n");
	}

	private static void signIn() {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken("agent", "n/a", "SCOPE_mcp:tools"));
	}

	private static MockHttpServletRequest call(String toolName) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setAttribute(McpComplianceFilter.METHOD_ATTRIBUTE, "tools/call");
		request.setAttribute(McpComplianceFilter.TOOL_NAME_ATTRIBUTE, toolName);
		return request;
	}

	private static GATool tool(String name, List<String> scopes) {
		return GATool.builder()
			.name(name)
			.description("A tool.")
			.inputSchema("{}")
			.readOnly(true)
			.callHandler((Map<String, Object> arguments) -> new ToolCallOutcome("{}", false))
			.scopes(scopes)
			.build();
	}

}
