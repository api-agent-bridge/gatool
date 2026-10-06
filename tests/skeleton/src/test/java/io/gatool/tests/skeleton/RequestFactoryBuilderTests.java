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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.ClassUtils;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GATool builds its request factory with the application's own
 * {@code ClientHttpRequestFactoryBuilder} bean, or with the one
 * {@code ClientHttpRequestFactoryBuilder.detect()} picks for the classpath, which prefers
 * Apache HttpClient 5 and Jetty over the JDK client. The starters leave both out, so the
 * JDK client is what ships, and startup names the builder in one INFO line. An
 * application that carries {@code httpclient5} for another reason moves GATool onto
 * Boot's Apache builder, whose pool holds 5 connections per route and 25 in all unless a
 * customizer raised them, and startup then warns; that branch runs without a test here,
 * because the jar is absent from this classpath.
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestFactoryBuilderTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls");

	@Test
	void startup_onTheStarterClasspath_shouldNameTheJdkBuilderBootDetected(CapturedOutput output) {
		boolean apachePresent = ClassUtils.isPresent("org.apache.hc.client5.http.impl.classic.HttpClients", null);
		boolean jettyPresent = ClassUtils.isPresent("org.eclipse.jetty.client.HttpClient", null);

		this.contextRunner.run((context) -> {
			assertThat(context).hasNotFailed();
			String line = output.getAll()
				.lines()
				.filter((candidate) -> candidate.contains("builds the request factory"))
				.findFirst()
				.orElse("");

			assertThat(apachePresent).isFalse();
			assertThat(jettyPresent).isFalse();
			assertThat(line).as("the startup line naming the builder")
				.contains("INFO")
				.contains("JdkClientHttpRequestFactoryBuilder")
				.contains("detected");
			assertThat(output.getAll()).doesNotContain("HttpComponentsClientHttpRequestFactoryBuilder");
		});
	}

	// Spring Boot declares a ClientHttpRequestFactoryBuilder bean of its own in every
	// application that is not reactive, so the line keeps "the bean of this application"
	// for a bean the application declared.
	@Test
	void startup_withTheBuilderBeanSpringBootDeclares_shouldNotCallItTheApplicationsOwn(CapturedOutput output) {
		this.contextRunner.withConfiguration(AutoConfigurations.of(ImperativeHttpClientAutoConfiguration.class))
			.run((context) -> {
				assertThat(context).hasNotFailed().hasSingleBean(ClientHttpRequestFactoryBuilder.class);
				assertThat(requestFactoryLine(output)).contains("JdkClientHttpRequestFactoryBuilder")
					.contains("spring.http.clients.imperative.factory")
					.doesNotContain("bean of this application");
			});
	}

	@Test
	void startup_withABuilderBeanOfTheApplication_shouldSayTheBeanIsTheApplications(CapturedOutput output) {
		this.contextRunner.withConfiguration(AutoConfigurations.of(ImperativeHttpClientAutoConfiguration.class))
			.withBean("applicationBuilder", ClientHttpRequestFactoryBuilder.class, ClientHttpRequestFactoryBuilder::jdk)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(requestFactoryLine(output))
					.contains("the ClientHttpRequestFactoryBuilder bean of this application");
			});
	}

	private static String requestFactoryLine(CapturedOutput output) {
		return output.getAll()
			.lines()
			.filter((candidate) -> candidate.contains("builds the request factory"))
			.findFirst()
			.orElse("");
	}

}
