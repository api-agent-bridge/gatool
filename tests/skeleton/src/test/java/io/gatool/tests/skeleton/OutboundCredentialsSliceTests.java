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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.webmvc.autoconfigure.McpServerStatelessWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.GAToolCredentialsAutoConfiguration;
import io.gatool.boot.execution.ApiCredentialStrategy;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpSecurityAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What startup does with the outbound credential settings: which stop names which
 * property, and when an application's own strategy takes over.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboundCredentialsSliceTests {

	private static final String SECURED = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1";

	private static final String AUDIENCES = "spring.security.oauth2.resourceserver.jwt.audiences=GATool";

	private static final String SCOPES = "gatool.mcp.security.baseline-scopes=mcp:tools";

	private static final String UNSAFE_SWITCH_ON = "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true";

	private static final String REGISTRATION_ID = "gatool.api.credentials.client-registration-id=movies";

	private static final String[] MOVIES_REGISTRATION = {
			"spring.security.oauth2.client.registration.movies.client-id=gatool-server",
			"spring.security.oauth2.client.registration.movies.client-secret=secret",
			"spring.security.oauth2.client.registration.movies.authorization-grant-type=client_credentials",
			"spring.security.oauth2.client.registration.movies.provider=local",
			"spring.security.oauth2.client.provider.local.token-uri=http://127.0.0.1:1/token" };

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class, GAToolCredentialsAutoConfiguration.class,
				OAuth2ClientAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
				GAToolMcpSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
				OAuth2ResourceServerWebSecurityAutoConfiguration.class, SecurityAutoConfiguration.class,
				ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class))
		.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/", SECURED, AUDIENCES,
				SCOPES);

	@Test
	void startup_clientCredentialsWithoutARegistrationId_shouldStopNamingTheProperty() {
		this.contextRunner.withPropertyValues("gatool.api.credentials.strategy=client-credentials")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.credentials.client-registration-id")
				.hasMessageContaining("spring.security.oauth2.client.registration"));
	}

	@Test
	void startup_clientCredentialsWithAnUnknownRegistration_shouldStopNamingTheId() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=client-credentials",
					"gatool.api.credentials.client-registration-id=elsewhere")
			.withPropertyValues(MOVIES_REGISTRATION)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring.security.oauth2.client.registration.elsewhere"));
	}

	@Test
	void startup_tokenExchangeOverAClientCredentialsRegistration_shouldStopNamingBothGrantTypes() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=token-exchange", REGISTRATION_ID,
					"gatool.api.credentials.audience=movies-api")
			.withPropertyValues(MOVIES_REGISTRATION)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("urn:ietf:params:oauth:grant-type:token-exchange")
				.hasMessageContaining("client_credentials"));
	}

	@Test
	void startup_tokenExchangeWithoutAnAudience_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=token-exchange", REGISTRATION_ID,
					"spring.security.oauth2.client.registration.movies.authorization-grant-type="
							+ "urn:ietf:params:oauth:grant-type:token-exchange")
			.withPropertyValues(MOVIES_REGISTRATION[0], MOVIES_REGISTRATION[1], MOVIES_REGISTRATION[3],
					MOVIES_REGISTRATION[4])
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.credentials.audience"));
	}

	@Test
	void startup_tokenExchangeWhileSecurityIsOff_shouldStopNamingTheSwitch() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=token-exchange", REGISTRATION_ID,
					"gatool.api.credentials.audience=movies-api", UNSAFE_SWITCH_ON,
					"spring.security.oauth2.client.registration.movies.authorization-grant-type="
							+ "urn:ietf:params:oauth:grant-type:token-exchange")
			.withPropertyValues(MOVIES_REGISTRATION[0], MOVIES_REGISTRATION[1], MOVIES_REGISTRATION[3],
					MOVIES_REGISTRATION[4])
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("allow-mcp-calls-without-authentication")
				.hasMessageContaining("token exchange"));
	}

	@Test
	void startup_forwardingBesideAStrategy_shouldStopNamingBoth() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.unsafe.forward-client-tokens=true",
					"gatool.api.credentials.strategy=static-header", "gatool.api.credentials.header-name=X-Key",
					"gatool.api.credentials.header-value=secret")
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("forward-client-tokens")
				.hasMessageContaining("static-header"));
	}

	@Test
	void startup_forwardingBesideClientCredentials_shouldStopNamingBothWhicheverBeanComesFirst() {
		// The check runs where the executor picks the strategy, ahead of any bean, so the
		// order the two built-in beans register in cannot let the pair through.
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.unsafe.forward-client-tokens=true",
					"gatool.api.credentials.strategy=client-credentials", REGISTRATION_ID)
			.withPropertyValues(MOVIES_REGISTRATION)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("forward-client-tokens")
				.hasMessageContaining("client-credentials"));
	}

	@Test
	void startup_strategySpelledTheWayBootAlsoBindsIt_shouldSelectTheSameBean() {
		// Boot's relaxed binding reads CLIENT_CREDENTIALS as the same enum value, and the
		// bean's condition binds the property the same way, so the spelling cannot skip
		// the bean and stop startup naming a missing starter that is present.
		this.contextRunner.withPropertyValues("gatool.api.credentials.strategy=CLIENT_CREDENTIALS", REGISTRATION_ID)
			.withPropertyValues(MOVIES_REGISTRATION)
			.run((context) -> assertThat(context).hasNotFailed().hasSingleBean(ApiCredentialStrategy.class));
	}

	@Test
	void startup_forwardingSpelledTheWayBootBindsABoolean_shouldSelectTheBean() {
		// Boot binds yes and on as true, and a bean condition reading the raw text alone
		// would leave the switch on in the properties and the bean absent.
		this.contextRunner.withPropertyValues("gatool.api.credentials.unsafe.forward-client-tokens=yes")
			.run((context) -> {
				assertThat(context).hasNotFailed().hasSingleBean(ApiCredentialStrategy.class);
				assertThat(context.getBean(ApiCredentialStrategy.class).getClass().getSimpleName())
					.isEqualTo("ForwardedTokenCredentialStrategy");
			});
	}

	@Test
	void startup_strategyOutsideTheEnum_shouldStopWhereThePropertiesBind() {
		// The bean condition binds the same property, and a value outside the enum has
		// to reach Boot's own binding report, with the property named.
		this.contextRunner.withPropertyValues("gatool.api.credentials.strategy=bogus", REGISTRATION_ID)
			.withPropertyValues(MOVIES_REGISTRATION)
			.run((context) -> assertThat(context).getFailure().rootCause().hasMessageContaining("bogus"));
	}

	@Test
	void startup_tokenExchangeWithAResourceThatIsNotAUri_shouldStopNamingTheProperty() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.strategy=token-exchange", REGISTRATION_ID,
					"gatool.api.credentials.resource=not a uri#x",
					"spring.security.oauth2.client.registration.movies.authorization-grant-type="
							+ "urn:ietf:params:oauth:grant-type:token-exchange")
			.withPropertyValues(MOVIES_REGISTRATION[0], MOVIES_REGISTRATION[1], MOVIES_REGISTRATION[3],
					MOVIES_REGISTRATION[4])
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.credentials.resource"));
	}

	@Test
	void startup_forwardingWhileSecurityIsOff_shouldStopNamingTheSwitch() {
		this.contextRunner
			.withPropertyValues("gatool.api.credentials.unsafe.forward-client-tokens=true", UNSAFE_SWITCH_ON)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("allow-mcp-calls-without-authentication")
				.hasMessageContaining("forwarding the caller's token"));
	}

	@Test
	void startup_clientCredentialsWithRegistrationsButWithoutAnAuthorizedClientService_shouldStopNamingTheProperty() {
		// Boot's OAuth2ClientAutoConfiguration defines the service beside its
		// registrations. An application that declares the registrations itself and
		// leaves that auto-configuration out has the registration and lacks a store
		// for the token, and the stop is a property failure Boot's analyzer renders.
		new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					GAToolCredentialsAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
					McpServerStatelessWebMvcAutoConfiguration.class, GAToolMcpAutoConfiguration.class,
					GAToolMcpSecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class,
					OAuth2ResourceServerWebSecurityAutoConfiguration.class, SecurityAutoConfiguration.class,
					ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class))
			.withUserConfiguration(RegistrationsAlone.class)
			.withPropertyValues("spring.ai.mcp.server.protocol=STATELESS", "gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
					"gatool.mcp.operations.locations=classpath:gatool/scoped-every-tool-declared/", SECURED, AUDIENCES,
					SCOPES, "gatool.api.credentials.strategy=client-credentials", REGISTRATION_ID)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.credentials.strategy")
				.hasMessageContaining("OAuth2AuthorizedClientService"));
	}

	@Test
	void startup_clientCredentialsWithoutTheOAuth2Client_shouldStopNamingTheStarter() {
		// The strategy is a bean of the credentials auto-configuration, which needs the
		// OAuth2 client on the classpath, so the executor meets the property without a
		// bean and names what to add.
		this.contextRunner.withClassLoader(new FilteredClassLoader(OAuth2AuthorizedClientManager.class))
			.withPropertyValues("gatool.api.credentials.strategy=client-credentials", REGISTRATION_ID)
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("spring-boot-starter-security-oauth2-client"));
	}

	@Test
	void startup_applicationsOwnStrategyBesideAConfiguredOne_shouldTakeItAndSaySo(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(OwnStrategy.class)
			.withPropertyValues("gatool.api.credentials.strategy=client-credentials", REGISTRATION_ID)
			.withPropertyValues(MOVIES_REGISTRATION)
			.run((context) -> {
				assertThat(context).hasNotFailed().hasSingleBean(ApiCredentialStrategy.class);
				assertThat(context.getBean(ApiCredentialStrategy.class)).isInstanceOf(SignedRequests.class);
			});

		assertThat(output.getAll()).contains("ApiCredentialStrategy bean").contains("replaces the built-in strategy");
	}

	@Test
	void startup_tokenExchangeOverStdio_shouldStopNamingTheTransport() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					GAToolCredentialsAutoConfiguration.class, OAuth2ClientAutoConfiguration.class))
			.withPropertyValues("spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.stdio=true",
					"gatool.api.url=http://127.0.0.1:1/graphql",
					"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
					"gatool.api.credentials.strategy=token-exchange", REGISTRATION_ID,
					"gatool.api.credentials.audience=movies-api",
					"spring.security.oauth2.client.registration.movies.authorization-grant-type="
							+ "urn:ietf:params:oauth:grant-type:token-exchange",
					MOVIES_REGISTRATION[0], MOVIES_REGISTRATION[1], MOVIES_REGISTRATION[3], MOVIES_REGISTRATION[4])
			.run((context) -> assertThat(context).getFailure()
				.rootCause()
				.isInstanceOf(InvalidConfigurationPropertyValueException.class)
				.hasMessageContaining("gatool.api.credentials.strategy")
				.hasMessageContaining("Over stdio")
				.hasMessageContaining("client credentials or a static header"));
	}

	@Configuration(proxyBeanMethods = false)
	static class RegistrationsAlone {

		@Bean
		ClientRegistrationRepository registrations() {
			return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("movies")
				.clientId("gatool-server")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.tokenUri("http://127.0.0.1:1/token")
				.build());
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OwnStrategy {

		@Bean
		ApiCredentialStrategy signedRequests() {
			return new SignedRequests();
		}

	}

	static final class SignedRequests implements ApiCredentialStrategy {

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			request.getHeaders().set("X-Signature", "signed");
			return execution.execute(request, body);
		}

	}

}
