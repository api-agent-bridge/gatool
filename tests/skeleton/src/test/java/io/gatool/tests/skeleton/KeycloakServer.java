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
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak in a container, started once with the {@code GATool} realm, so the security
 * tests that want a real issuer run against one: an agent client a user signs in with,
 * the GATool server as a confidential client with token exchange switched on, and the
 * movie API as the audience of exchanged tokens.
 *
 * <p>
 * The tests that use it are enabled only where Docker answers, so a machine without it
 * skips them and the rest of the suite is unaffected.
 */
final class KeycloakServer {

	static final String IMAGE = "quay.io/keycloak/keycloak:26.7.3";

	static final String REALM = "gatool";

	static final String USER = "mara";

	static final String PASSWORD = "mara123";

	private static final Object LOCK = new Object();

	private static GenericContainer<?> container;

	private KeycloakServer() {
	}

	/**
	 * Says whether Docker answers on this machine, for {@code @EnabledIf}.
	 */
	static boolean dockerAvailable() {
		return DockerClientFactory.instance().isDockerAvailable();
	}

	/**
	 * Returns the issuer of the realm, as Spring Boot's {@code issuer-uri} names it,
	 * starting the container on first use.
	 */
	static String issuer() {
		GenericContainer<?> running = start();
		return "http://" + running.getHost() + ":" + running.getMappedPort(8080) + "/realms/" + REALM;
	}

	/**
	 * Returns the realm's token endpoint.
	 */
	static String tokenUrl() {
		return issuer() + "/protocol/openid-connect/token";
	}

	/**
	 * Signs the test user in through the agent client and returns the access token, the
	 * way an MCP client's sign-in ends.
	 * @param scopes the scopes to ask for
	 * @return the serialized JWT
	 */
	static String signIn(List<String> scopes) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "password");
		form.add("username", USER);
		form.add("password", PASSWORD);
		form.add("scope", String.join(" ", scopes));
		TokenResponse response = RestClient.create()
			.post()
			.uri(tokenUrl())
			.headers((headers) -> headers.setBasicAuth("agent", "agent-secret"))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			.body(TokenResponse.class);
		if (response == null || response.access_token() == null) {
			throw new IllegalStateException("Keycloak did not issue a token for " + USER);
		}
		return response.access_token();
	}

	private static GenericContainer<?> start() {
		synchronized (LOCK) {
			if (container == null) {
				GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse(IMAGE))
					.withExposedPorts(8080)
					.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
					.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
					.withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/gatool-realm.json"),
							"/opt/keycloak/data/import/gatool-realm.json")
					.withCommand("start-dev", "--import-realm")
					.waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
						.forPort(8080)
						.withStartupTimeout(Duration.ofMinutes(3)));
				keycloak.start();
				container = keycloak;
			}
			return container;
		}
	}

	// Jackson maps the token endpoint's snake_case field as it is.
	record TokenResponse(String access_token) {
	}

}
