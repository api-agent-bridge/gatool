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

import java.text.ParseException;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.GAToolCredentialsAutoConfiguration;
import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose token an in-process call sends to the API under token exchange.
 *
 * <p>
 * {@link io.gatool.boot.internal.credentials.OAuth2ApiCredentialStrategy} reads the
 * caller's token from the security context of the thread that runs the tool call, and the
 * in-process path runs a call on the thread that calls it. A caller whose authentication
 * sits on that thread reaches the API as itself. This test pins README.md's sentence that
 * the caller of an in-process tool is the user on the thread under {@code token-exchange}
 * and under the forwarded token.
 */
class InProcessTokenExchangeCallerTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
				RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolCredentialsAutoConfiguration.class, OAuth2ClientAutoConfiguration.class,
				GAToolInProcessAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=" + MoviesApiServer.graphQlUrl(),
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.in-process.operations.locations=classpath:gatool/scoped/",
				"gatool.mcp.operations.locations=optional:classpath*:gatool/none/",
				"gatool.api.credentials.strategy=token-exchange",
				"gatool.api.credentials.client-registration-id=movies", "gatool.api.credentials.audience=movies-api",
				"gatool.api.credentials.resource=https://movies.example.org/graphql",
				"spring.security.oauth2.client.registration.movies.client-id=gatool-server",
				"spring.security.oauth2.client.registration.movies.client-secret=secret",
				"spring.security.oauth2.client.registration.movies.authorization-grant-type="
						+ "urn:ietf:params:oauth:grant-type:token-exchange",
				"spring.security.oauth2.client.registration.movies.provider=local",
				"spring.security.oauth2.client.provider.local.token-uri=" + LocalIssuer.get().tokenUrl());

	@AfterEach
	void clearTheSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void call_inProcessUnderTokenExchangeWithACallerOnTheThread_shouldReachTheApiAsThatCaller() throws ParseException {
		String callerToken = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));
		Jwt jwt = Jwt.withTokenValue(callerToken)
			.header("alg", "RS256")
			.subject("agent")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300))
			.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

		this.contextRunner.run((context) -> callbackFor(context, "searchMovies").call("{\"text\": \"Signal\"}"));

		// The issuer received the caller's own token as the subject to exchange, and the
		// token the API received names the same caller and carries the configured
		// audience.
		assertThat(LocalIssuer.get().lastTokenRequest())
			.containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
			.containsEntry("subject_token", callerToken)
			.containsEntry("audience", "movies-api");
		JWTClaimsSet exchangedClaims = LocalIssuer.claimsOf(MoviesApiServer.lastAuthorization());
		assertThat(exchangedClaims.getSubject()).isEqualTo("agent");
		assertThat(exchangedClaims.getAudience()).containsExactly("movies-api");
		assertThat(MoviesApiServer.lastAuthorization()).isNotEqualTo("Bearer " + callerToken);
	}

	private static ToolCallback callbackFor(AssertableApplicationContext context, String name) {
		ToolCallback[] callbacks = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks();
		for (ToolCallback callback : callbacks) {
			if (callback.getToolDefinition().name().equals(name)) {
				return callback;
			}
		}
		throw new AssertionError("The in-process tools do not hold " + name);
	}

}
