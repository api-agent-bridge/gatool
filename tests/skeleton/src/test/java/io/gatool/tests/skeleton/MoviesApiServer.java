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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import io.gatool.fixtures.movies.api.MoviesApiApplication;

/**
 * Starts the movie API once, on a random port, in an application of its own. That is
 * remote mode: GATool runs in one application and the GraphQL API runs in another, and
 * the two meet over HTTP.
 */
final class MoviesApiServer {

	// The builder starts the fixture as its own application, so its component scan is
	// rooted at io.gatool.fixtures.movies.api and reaches ScalarConfiguration, which
	// registers the DateTime and CountryCode implementations the schema declares. With
	// the calling test class as the source, the scan misses them and the schema fails
	// to build.
	//
	// Both applications run in the one test JVM, so the fixture sees the whole test
	// classpath, and that classpath carries the GATool starter and Spring AI's MCP
	// server. The fixture is a plain GraphQL API, so it excludes GATool's two
	// auto-configurations, which would otherwise stop its startup on the unset
	// gatool.api.url, and it switches Spring AI's MCP server off. The test classpath
	// also carries Spring Security for the security tests, and Boot's default chain
	// would put /graphql behind a password, so the two auto-configurations that
	// install it stay out too. Every property is Boot's and Spring AI's own, so the
	// fixture module stays as it is.
	private static final ConfigurableApplicationContext CONTEXT = new SpringApplicationBuilder()
		.sources(MoviesApiApplication.class, AuthorizationRecorder.class)
		.web(WebApplicationType.SERVLET)
		.properties("server.port=0", "spring.main.banner-mode=off",
				"spring.autoconfigure.exclude=io.gatool.boot.autoconfigure.GAToolAutoConfiguration,"
						+ "io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.web.servlet"
						+ ".ServletWebSecurityAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.web.servlet"
						+ ".SecurityFilterAutoConfiguration,"
						+ "org.springframework.boot.security.oauth2.client.autoconfigure"
						+ ".OAuth2ClientAutoConfiguration,"
						+ "org.springframework.boot.security.oauth2.client.autoconfigure.servlet"
						+ ".OAuth2ClientWebSecurityAutoConfiguration",
				"spring.ai.mcp.server.enabled=false")
		.run();

	private MoviesApiServer() {
	}

	/**
	 * Returns the URL that {@code gatool.api.url} points at.
	 * @return the GraphQL endpoint of the running movie API
	 */
	static String graphQlUrl() {
		return "http://127.0.0.1:" + ((WebServerApplicationContext) CONTEXT).getWebServer().getPort() + "/graphql";
	}

	/**
	 * Returns the {@code Authorization} header the movie API last received, or
	 * {@code null} where the last call arrived without one.
	 */
	static @Nullable String lastAuthorization() {
		return AuthorizationRecorder.LAST.get();
	}

	/**
	 * Returns how many requests for {@code /graphql} have arrived since the JVM started,
	 * so a test can tell a call that reached the API from one refused ahead of it.
	 */
	static int graphQlCalls() {
		return AuthorizationRecorder.GRAPHQL_CALLS.get();
	}

	/**
	 * Records the credential each call to the API carries, so a test can read what
	 * GATool's outbound strategy sent, and counts the calls.
	 */
	@Configuration(proxyBeanMethods = false)
	static class AuthorizationRecorder {

		static final AtomicReference<@Nullable String> LAST = new AtomicReference<>();

		// Each test application of this package scans it, so the server under test
		// carries this filter as well, and a request for /mcp passes through it there.
		// The header and the count are therefore taken from the requests for /graphql
		// alone, and a call that GATool refuses ahead of the API leaves both as they
		// were.
		static final AtomicInteger GRAPHQL_CALLS = new AtomicInteger();

		@Bean
		OncePerRequestFilter authorizationRecordingFilter() {
			return new OncePerRequestFilter() {

				@Override
				protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
						FilterChain chain) throws ServletException, IOException {
					if ("/graphql".equals(request.getRequestURI())) {
						LAST.set(request.getHeader("Authorization"));
						GRAPHQL_CALLS.incrementAndGet();
					}
					chain.doFilter(request, response);
				}
			};
		}

	}

}
