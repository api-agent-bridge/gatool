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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import io.gatool.boot.internal.execution.RemoteHttpClients;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request for a token waits under the deadlines GATool supplies, as the call to the
 * GraphQL API does.
 *
 * <p>
 * Spring Boot leaves {@code spring.http.clients.connect-timeout} and
 * {@code spring.http.clients.read-timeout} unset. GATool fills both for the client that
 * calls the API and for the client that asks the issuer for a token. On Boot's own
 * request factory, where both are unset, a token endpoint that accepts a request and
 * stays silent would hold the tool call for as long as the socket stays open.
 *
 * <p>
 * The request factory builder of this application records the settings each factory was
 * built with, under the path of every request the factory sends, so the test reads the
 * deadlines of a request without waiting for one to pass.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/",
				"spring.security.oauth2.resourceserver.jwt.audiences=gatool-skeleton",
				"gatool.mcp.security.baseline-scopes=mcp:tools", "gatool.api.credentials.strategy=client-credentials",
				"gatool.api.credentials.client-registration-id=movies",
				"spring.security.oauth2.client.registration.movies.client-id=gatool-server",
				"spring.security.oauth2.client.registration.movies.client-secret=secret",
				"spring.security.oauth2.client.registration.movies.authorization-grant-type=client_credentials",
				"spring.security.oauth2.client.registration.movies.scope=movies:api",
				"spring.security.oauth2.client.registration.movies.provider=local" })
class TokenEndpointDeadlineOverHttpTests {

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
			+ "\"searchMovies\",\"arguments\":{\"text\":\"a\"}}}";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void servers(DynamicPropertyRegistry registry) {
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> LocalIssuer.get().url());
		registry.add("spring.security.oauth2.client.provider.local.token-uri", () -> LocalIssuer.get().tokenUrl());
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void callTool_underClientCredentials_shouldAskForTheTokenUnderTheDeadlinesGAToolSupplies() {
		String caller = LocalIssuer.get().token("gatool-skeleton", List.of("mcp:tools"));

		assertThat(post(caller).getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpClientSettings tokenRequest = RecordedRequestFactories.SETTINGS_BY_PATH.get("/token");
		assertThat(tokenRequest).as("the settings of the factory that sent the token request").isNotNull();
		assertThat(tokenRequest.connectTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_CONNECT_TIMEOUT);
		assertThat(tokenRequest.readTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_READ_TIMEOUT);
		// The call to the API runs under the same two deadlines.
		HttpClientSettings apiRequest = RecordedRequestFactories.SETTINGS_BY_PATH.get("/graphql");
		assertThat(apiRequest).as("the settings of the factory that called the API").isNotNull();
		assertThat(apiRequest.connectTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_CONNECT_TIMEOUT);
		assertThat(apiRequest.readTimeout()).isEqualTo(RemoteHttpClients.FALLBACK_READ_TIMEOUT);
	}

	private ResponseEntity<String> post(String token) {
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + this.port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> headers.setBearerAuth(token))
			.body(CALL)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	// A test configuration, which the component scan of the other test applications
	// in this package passes over, so the builder reaches this application alone.
	@TestConfiguration(proxyBeanMethods = false)
	static class RecordedRequestFactories {

		static final Map<String, HttpClientSettings> SETTINGS_BY_PATH = new ConcurrentHashMap<>();

		@Bean
		ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory> recordingRequestFactoryBuilder() {
			ClientHttpRequestFactoryBuilder<?> jdk = ClientHttpRequestFactoryBuilder.jdk();
			return (settings) -> {
				ClientHttpRequestFactory factory = jdk.build(settings);
				HttpClientSettings built = (settings != null) ? settings : HttpClientSettings.defaults();
				return (uri, method) -> {
					SETTINGS_BY_PATH.put(uri.getPath(), built);
					return factory.createRequest(uri, method);
				};
			};
		}

	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
