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

package io.gatool.tests.embedded;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import reactor.core.publisher.Mono;

import io.gatool.boot.inprocess.GAToolCallbacks;
import io.gatool.fixtures.movies.api.MoviesApiApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Embedded mode in an application without a web server runs the
 * {@code WebGraphQlInterceptor} beans in front of every tool call, as it does in a
 * servlet application.
 *
 * <p>
 * Boot builds the {@code WebGraphQlHandler} bean, interceptors included, in a servlet or
 * reactive web application alone. Without it,
 * {@code GAToolAutoConfiguration.gaToolGraphQlExecutor} builds a handler from the
 * {@code ExecutionGraphQlService}, and that handler carries the interceptors as well.
 * This test runs the movie API with the in-process starter once without a web server and
 * once as a servlet application, with an interceptor that counts and refuses every
 * request, the way a tenant or audit gate refuses one it cannot attribute.
 *
 * <p>
 * The test sits outside the skeleton package, because the application the stdio tests
 * launch scans that package, and the interceptor bean must stay out of it.
 */
@ExtendWith(OutputCaptureExtension.class)
class NonWebInterceptorsTests {

	@Test
	void inProcessCall_nonWebApplication_shouldRunTheInterceptorTheStartupLogNames(CapturedOutput output) {
		try (ConfigurableApplicationContext context = start(WebApplicationType.NONE)) {
			RefusingInterceptor interceptor = context.getBean(RefusingInterceptor.class);
			boolean handlerBean = context.getBeanProvider(WebGraphQlHandler.class).getIfAvailable() != null;

			String outcome = call(context);

			String logLine = output.getAll()
				.lines()
				.filter((line) -> line.contains("runs each document inside this application"))
				.findFirst()
				.orElse("");
			assertThat(handlerBean).isFalse();
			assertThat(logLine).contains("INFO")
				.contains("through these WebGraphQlInterceptor beans")
				.contains("RefusingInterceptor")
				.doesNotContain("stay out");
			assertThat(interceptor.calls.get()).isEqualTo(1);
			assertThat(outcome).doesNotContain("Signal from Kepler");
		}
	}

	@Test
	void inProcessCall_servletApplication_shouldRunTheInterceptor(CapturedOutput output) {
		try (ConfigurableApplicationContext context = start(WebApplicationType.SERVLET)) {
			RefusingInterceptor interceptor = context.getBean(RefusingInterceptor.class);

			String outcome = call(context);

			assertThat(interceptor.calls.get()).isEqualTo(1);
			assertThat(outcome).doesNotContain("Signal from Kepler");
		}
	}

	// The refusal leaves the callback as Spring AI's ToolExecutionException, so the
	// outcome is its message, and a call that got through is the result text.
	private static String call(ConfigurableApplicationContext context) {
		ToolCallback callback = context.getBean(GAToolCallbacks.class).toolCallbackProvider().getToolCallbacks()[0];
		try {
			return callback.call("{\"first\": 1}");
		}
		catch (RuntimeException ex) {
			return "threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
		}
	}

	private static ConfigurableApplicationContext start(WebApplicationType type) {
		return new SpringApplicationBuilder().sources(MoviesApiApplication.class, Interceptors.class)
			.web(type)
			.properties("server.port=0", "spring.main.banner-mode=off",
					// The in-process starter alone: the MCP side of the skeleton
					// classpath
					// backs off, so the embedded executor is the one thing under test.
					"spring.ai.mcp.server.enabled=false",
					"spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.web.servlet"
							+ ".ServletWebSecurityAutoConfiguration,"
							+ "org.springframework.boot.security.autoconfigure.web.servlet"
							+ ".SecurityFilterAutoConfiguration")
			.run();
	}

	@Configuration(proxyBeanMethods = false)
	static class Interceptors {

		@Bean
		RefusingInterceptor refusingInterceptor() {
			return new RefusingInterceptor();
		}

	}

	static final class RefusingInterceptor implements WebGraphQlInterceptor {

		final AtomicInteger calls = new AtomicInteger();

		@Override
		public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
			this.calls.incrementAndGet();
			return Mono.error(new IllegalStateException("RefusingInterceptor refused the request"));
		}

	}

}
