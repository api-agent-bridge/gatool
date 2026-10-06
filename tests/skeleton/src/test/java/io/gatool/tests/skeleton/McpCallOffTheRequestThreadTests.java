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

package io.gatool.tests.skeleton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.gatool.tests.hosts.OffThreadCallHostApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What GATool does when a tool call arrives off the request thread.
 *
 * <p>
 * GATool reads the caller of a call from the security context of the thread the call runs
 * on: the rate limiter counts per caller, and token exchange takes the caller's token
 * from there. Spring AI runs a call on the request thread in a servlet application, where
 * Spring Security's filter has bound that context, and
 * {@code McpCallOnTheRequestThreadTests} holds that half. The host here replaces the
 * customizer that asks for it, so the MCP SDK hands each call to a worker thread. Two
 * tests pin the stop that state meets at startup, and four pin the refusal a call meets
 * in an application that got past the stop with the server rebuilt and its tools left
 * converted.
 *
 * <p>
 * Each host runs in a JVM of its own, because the strategy, Reactor's hook and the
 * scheduler's worker threads belong to the JVM, and a test that set them here would set
 * them for every test after it.
 *
 * <p>
 * The issuer and the movie API run in this JVM, and the host reaches both over HTTP. Each
 * refusal test reads their call counts before and after its host, and both counts have to
 * match, which says that each server was left alone while the host ran.
 */
class McpCallOffTheRequestThreadTests {

	private static final String AUDIENCE = "gatool-skeleton";

	private static final List<String> SCOPES = List.of("mcp:tools", "movies:read");

	private static final List<String> HOST = List.of("--server.port=0", "--spring.main.banner-mode=off",
			"--spring.ai.mcp.server.protocol=STREAMABLE",
			"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
			"--gatool.mcp.operations.locations=classpath:gatool/scoped/");

	// The shape whose server bean reads immediate execution as true while its tools were
	// converted without it, so the host gets past the startup stop and its calls still
	// reach a worker thread. It stands for a transport of the application's own that
	// hands a call to another thread, which startup cannot see either.
	private static final String CONVERTED = "--host.shape=converted-without-immediate-execution";

	@Test
	void startup_underAPrimaryCustomizerWithoutImmediateExecution_shouldStopNamingTheCustomizerAndTheFix()
			throws Exception {
		HostProcess.Run stopped = run(List.of(), "stopped-ana", "stopped-ben");

		String written = stopped.stdout() + stopped.stderr();
		assertThat(stopped.exitCode()).as(written).isNotZero();
		assertThat(written).contains("The MCP server bean 'mcpSyncServer' was built without immediateExecution(true)")
			.contains("'servletMcpSyncServerCustomizer'")
			.contains("a second bean of that type, 'ownServerCustomizer', which the server took")
			.contains("Add immediateExecution(true) to that customizer");
	}

	@Test
	void startup_underACustomizerNamedLikeSpringAisParameter_shouldStopNamingThatCustomizerAndTheFix()
			throws Exception {
		HostProcess.Run stopped = run(List.of("--host.customizer=named"), "stopped-ana", "stopped-ben");

		String written = stopped.stdout() + stopped.stderr();
		assertThat(stopped.exitCode()).as(written).isNotZero();
		assertThat(written).contains("The MCP server bean 'mcpSyncServer' was built without immediateExecution(true)")
			.contains("a second bean of that type, 'mcpSyncServerCustomizer', which the server took")
			.contains("Add immediateExecution(true) to that customizer");
	}

	@Test
	void callTool_underTheThreadLocalStrategy_shouldRefuseBothCallsAheadOfTheRateLimiter() throws Exception {
		int tokenRequests = LocalIssuer.get().tokenRequests();
		int graphQlCalls = MoviesApiServer.graphQlCalls();

		HostProcess.Run run = run(List.of(CONVERTED), "thread-local-ana", "thread-local-ben");

		// The security context and the servlet request both stay on the request thread
		// here, so a rate limiter asked on the worker thread would count both callers
		// under the stand-in stdio uses.
		assertRefusedBothCalls(run, tokenRequests, graphQlCalls);
	}

	@Test
	void callTool_withoutAuthenticationAndWithAutomaticContextPropagation_shouldRefuseBothCalls() throws Exception {
		int tokenRequests = LocalIssuer.get().tokenRequests();
		int graphQlCalls = MoviesApiServer.graphQlCalls();
		List<String> arguments = new ArrayList<>(HOST);
		arguments.addAll(List.of("--gatool.api.url=" + MoviesApiServer.graphQlUrl(),
				"--gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"--spring.reactor.context-propagation=auto", "--host.tokens=anonymous,anonymous", CONVERTED));

		HostProcess.Run run = HostProcess.run(OffThreadCallHostApplication.class, System.getProperty("java.class.path"),
				arguments, Duration.ofSeconds(120));

		// Under the switch the caller is the client address, read from the servlet
		// request bound to the thread, and the request stays on the request thread while
		// Reactor carries the contexts whose accessors are registered.
		assertRefusedBothCalls(run, tokenRequests, graphQlCalls);
	}

	@Test
	void callTool_underTheInheritableStrategy_shouldRefuseBothCallsSoTheSecondCallerCannotRunAsTheFirst()
			throws Exception {
		int tokenRequests = LocalIssuer.get().tokenRequests();
		int graphQlCalls = MoviesApiServer.graphQlCalls();
		String ana = LocalIssuer.get().tokenFor("inheritable-ana", AUDIENCE, SCOPES);
		String ben = LocalIssuer.get().tokenFor("inheritable-ben", AUDIENCE, SCOPES);

		HostProcess.Run run = run(List.of("--host.strategy=MODE_INHERITABLETHREADLOCAL", CONVERTED), List.of(ana, ben));

		// The first call makes the worker thread, which inherits its context and keeps
		// it. The second caller's call then lands on that thread, where a call let
		// through would be counted as the first caller and reach the API with the first
		// caller's token.
		assertRefusedBothCalls(run, tokenRequests, graphQlCalls);
	}

	@Test
	void callTool_withAutomaticContextPropagation_shouldRefuseBothCallsThoughEachCallerIsInReach() throws Exception {
		int tokenRequests = LocalIssuer.get().tokenRequests();
		int graphQlCalls = MoviesApiServer.graphQlCalls();
		String ana = LocalIssuer.get().tokenFor("propagated-ana", AUDIENCE, SCOPES);
		String ben = LocalIssuer.get().tokenFor("propagated-ben", AUDIENCE, SCOPES);

		HostProcess.Run run = run(List.of("--spring.reactor.context-propagation=auto", CONVERTED), List.of(ana, ben));

		// Reactor carries each caller's security context to the worker thread here. The
		// check is unconditional because GATool cannot see whether a deployment's own
		// rate limiter or credential strategy reads the thread, and this is the case that
		// gives up.
		assertRefusedBothCalls(run, tokenRequests, graphQlCalls);
	}

	/**
	 * Asserts that both calls read the refusal, that the host's rate limiter was left
	 * unasked, and that the issuer and the movie API each end with the count they started
	 * with.
	 */
	private static void assertRefusedBothCalls(HostProcess.Run run, int tokenRequests, int graphQlCalls) {
		assertThat(run.exitCode()).as(run.line(OffThreadCallHostApplication.LINE + "seen=")).isZero();
		for (String call : List.of("call=1", "call=2")) {
			assertThat(run.line(OffThreadCallHostApplication.LINE + call)).contains("status=200")
				.contains("The tool topRatedMovies was not called.")
				.contains("a thread that does not carry the caller")
				.contains("\"isError\":true");
		}
		// The refusal runs ahead of the rate limiter, so the host's own limiter recorded
		// nothing, and ahead of the tool, so the issuer and the API were left alone.
		assertThat(run.line(OffThreadCallHostApplication.LINE + "seen=")).endsWith("seen=[]");
		assertThat(LocalIssuer.get().tokenRequests()).isEqualTo(tokenRequests);
		assertThat(MoviesApiServer.graphQlCalls()).isEqualTo(graphQlCalls);
	}

	private static HostProcess.Run run(List<String> extraArguments, String firstSubject, String secondSubject)
			throws Exception {
		return run(extraArguments, List.of(LocalIssuer.get().tokenFor(firstSubject, AUDIENCE, SCOPES),
				LocalIssuer.get().tokenFor(secondSubject, AUDIENCE, SCOPES)));
	}

	private static HostProcess.Run run(List<String> extraArguments, List<String> tokens) throws Exception {
		List<String> arguments = new ArrayList<>(HOST);
		arguments.addAll(List.of("--gatool.api.url=" + MoviesApiServer.graphQlUrl(),
				"--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + LocalIssuer.get().url(),
				"--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE,
				"--gatool.mcp.security.baseline-scopes=mcp:tools", "--gatool.api.credentials.strategy=token-exchange",
				"--gatool.api.credentials.client-registration-id=movies",
				"--gatool.api.credentials.audience=movies-api",
				"--spring.security.oauth2.client.registration.movies.client-id=gatool-server",
				"--spring.security.oauth2.client.registration.movies.client-secret=secret",
				"--spring.security.oauth2.client.registration.movies.authorization-grant-type="
						+ "urn:ietf:params:oauth:grant-type:token-exchange",
				"--spring.security.oauth2.client.registration.movies.provider=local",
				"--spring.security.oauth2.client.provider.local.token-uri=" + LocalIssuer.get().tokenUrl(),
				"--host.tokens=" + String.join(",", tokens)));
		arguments.addAll(extraArguments);
		return HostProcess.run(OffThreadCallHostApplication.class, System.getProperty("java.class.path"), arguments,
				Duration.ofSeconds(120));
	}

}
