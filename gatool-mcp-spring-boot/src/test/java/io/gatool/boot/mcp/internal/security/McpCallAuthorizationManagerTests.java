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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import io.gatool.boot.mcp.internal.transport.McpComplianceFilter;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision for one request: what it needs, what the token held, and the two refusals
 * for reasons other than scopes.
 */
class McpCallAuthorizationManagerTests {

	private final McpScopes scopes = McpScopes.of(List.of("mcp:tools"),
			List.of(tool("topRatedMovies", List.of("movies:read")),
					tool("movieByLookup", List.of("movies:read", "movies:detail")), tool("searchMovies", List.of()),
					tool("moviesPage", null)),
			false);

	private final McpCallAuthorizationManager manager = new McpCallAuthorizationManager(this.scopes);

	@Test
	void authorize_anonymousCaller_shouldRefuseWhateverTheScopesSay() {
		Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

		AuthorizationResult result = this.manager.authorize(() -> anonymous, post("tools/list", null));

		assertThat(result).isInstanceOf(McpCallAuthorizationManager.RefusalAheadOfScopes.class);
		assertThat(result.isGranted()).isFalse();
	}

	@Test
	void authorize_postTheComplianceFilterDidNotRead_shouldRefuse() {
		// Without the marker the tool name is unknown, and the baseline alone would let
		// a scoped tool run, so the call is refused.
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");

		AuthorizationResult result = this.manager.authorize(() -> callerWith("mcp:tools", "movies:read"),
				new RequestAuthorizationContext(request));

		assertThat(result).isInstanceOf(McpCallAuthorizationManager.RefusalAheadOfScopes.class);
		assertThat(((McpCallAuthorizationManager.RefusalAheadOfScopes) result).reason()).contains("compliance filter");
	}

	@Test
	void authorize_getWithoutTheMarker_shouldNeedTheBaselineAlone() {
		// A GET opens the stateful stream and arrives without a body, so the marker is
		// absent by design and the baseline decides.
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mcp");

		AuthorizationResult result = this.manager.authorize(() -> callerWith("mcp:tools"),
				new RequestAuthorizationContext(request));

		assertThat(result.isGranted()).isTrue();
	}

	@Test
	void authorize_callOfAScopedToolWithItsScopes_shouldGrantAndNameWhatWasHeld() {
		AuthorizationResult result = this.manager.authorize(() -> callerWith("mcp:tools", "movies:read", "other:scope"),
				post("tools/call", "topRatedMovies"));

		McpCallAuthorizationManager.ScopeDecision decision = (McpCallAuthorizationManager.ScopeDecision) result;
		assertThat(decision.isGranted()).isTrue();
		assertThat(decision.requiredScopes()).containsExactly("mcp:tools", "movies:read");
		// The held list is this server's own scopes alone, so a claim outside what the
		// server defined stays out of every challenge.
		assertThat(decision.heldScopes()).containsExactly("mcp:tools", "movies:read");
	}

	@Test
	void authorize_callOfAScopedToolWithoutOneScope_shouldDenyNamingTheMissingOne() {
		AuthorizationResult result = this.manager.authorize(() -> callerWith("mcp:tools", "movies:read"),
				post("tools/call", "movieByLookup"));

		McpCallAuthorizationManager.ScopeDecision decision = (McpCallAuthorizationManager.ScopeDecision) result;
		assertThat(decision.isGranted()).isFalse();
		assertThat(decision.missingScopes()).containsExactly("movies:detail");
		assertThat(decision.toolName()).isEqualTo("movieByLookup");
	}

	@Test
	void authorize_callOfAnOpenToolAndOfAnUndeclaredOne_shouldNeedTheBaselineAlone() {
		assertThat(
				this.manager.authorize(() -> callerWith("mcp:tools"), post("tools/call", "searchMovies")).isGranted())
			.isTrue();
		assertThat(this.manager.authorize(() -> callerWith("mcp:tools"), post("tools/call", "moviesPage")).isGranted())
			.isTrue();
		assertThat(this.manager.authorize(() -> callerWith("mcp:tools"), post("tools/call", "unknownTool")).isGranted())
			.isTrue();
	}

	@Test
	void authorize_authenticatedPrincipalWithoutScopeAuthorities_shouldDenyNamingTheBaseline() {
		Authentication user = new TestingAuthenticationToken("ana", "n/a", "ROLE_USER");

		McpCallAuthorizationManager.ScopeDecision decision = (McpCallAuthorizationManager.ScopeDecision) this.manager
			.authorize(() -> user, post("tools/list", null));

		assertThat(decision.isGranted()).isFalse();
		assertThat(decision.missingScopes()).containsExactly("mcp:tools");
		assertThat(decision.heldScopes()).isEmpty();
	}

	@Test
	void authorize_toolNameAttributeOfAnotherType_shouldReadAsNoTool() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setAttribute(McpComplianceFilter.METHOD_ATTRIBUTE, "tools/call");
		request.setAttribute(McpComplianceFilter.TOOL_NAME_ATTRIBUTE, 42);

		assertThat(this.manager.authorize(() -> callerWith("mcp:tools"), new RequestAuthorizationContext(request))
			.isGranted()).isTrue();
	}

	private static RequestAuthorizationContext post(String method, String toolName) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setAttribute(McpComplianceFilter.METHOD_ATTRIBUTE, method);
		if (toolName != null) {
			request.setAttribute(McpComplianceFilter.TOOL_NAME_ATTRIBUTE, toolName);
		}
		return new RequestAuthorizationContext(request);
	}

	private static Authentication callerWith(String... scopes) {
		String[] authorities = new String[scopes.length];
		for (int index = 0; index < scopes.length; index++) {
			authorities[index] = "SCOPE_" + scopes[index];
		}
		return new TestingAuthenticationToken("agent", "n/a", authorities);
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
