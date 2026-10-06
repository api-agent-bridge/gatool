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

import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * What GATool's clients do with the request factory builder of the application.
 */
class RemoteHttpClientsTests {

	@Test
	void apiClientBuilder_builderThatSetsPropertiesThroughReflection_shouldStopNamingTheBuilderAndTheWaysOut() {
		// Spring Boot's reflective builder sets the deadlines of a factory class it does
		// not know and refuses the redirect rule. Boot's sentence alone, "Unable to set
		// redirect follow using reflection", does not name the builder or what to declare
		// in its place.
		RemoteHttpClients clients = new RemoteHttpClients(RestClient.builder(), HttpClientSettings.defaults(),
				ClientHttpRequestFactoryBuilder.of(FactoryOfTheApplication::new));

		assertThatIllegalStateException().isThrownBy(() -> clients.apiClientBuilder(DataSize.ofMegabytes(1)))
			.withMessageStartingWith("GATool asks the application's ClientHttpRequestFactoryBuilder for a request "
					+ "factory that leaves redirects unfollowed, and the builder ")
			.withMessageContaining("refused: Unable to set redirect follow using reflection.")
			.withMessageContaining("ClientHttpRequestFactoryBuilder.of(...)")
			.withMessageContaining("ClientHttpRequestFactoryBuilder.jdk()")
			.withMessageEndingWith(
					"applies the timeouts of the settings it receives and leaves redirects " + "unfollowed.")
			.withCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void schemaClient_builderThatSetsPropertiesThroughReflection_shouldStopWithTheSameSentence() {
		RemoteHttpClients clients = new RemoteHttpClients(RestClient.builder(), HttpClientSettings.defaults(),
				ClientHttpRequestFactoryBuilder.of(FactoryOfTheApplication::new));

		assertThatIllegalStateException().isThrownBy(() -> clients.schemaClient(DataSize.ofMegabytes(1)))
			.withMessageStartingWith("GATool asks the application's ClientHttpRequestFactoryBuilder for a request "
					+ "factory that leaves redirects unfollowed");
	}

	@Test
	void tokenEndpointClientBuilder_builderThatSetsPropertiesThroughReflection_shouldBuildTheClient() {
		// The token request keeps the redirect rule of the application, so the
		// reflective builder is asked for the deadlines alone, which it can set.
		RemoteHttpClients clients = new RemoteHttpClients(RestClient.builder(), HttpClientSettings.defaults(),
				ClientHttpRequestFactoryBuilder.of(FactoryOfTheApplication::new));

		assertThat(clients.tokenEndpointClientBuilder()).isNotNull();
	}

	@Test
	void apiClientBuilder_builderWrittenAsALambda_shouldBuildTheClient() {
		ClientHttpRequestFactoryBuilder<ClientHttpRequestFactory> ofTheApplication = (
				settings) -> new FactoryOfTheApplication();
		RemoteHttpClients clients = new RemoteHttpClients(RestClient.builder(), HttpClientSettings.defaults(),
				ofTheApplication);

		assertThat(clients.apiClientBuilder(DataSize.ofMegabytes(1))).isNotNull();
	}

}
