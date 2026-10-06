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

package io.gatool.tests.hosts;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.server.McpSyncServer;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContextHolder;

import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.boot.mcp.limit.ToolRateLimiter;

/**
 * An application whose MCP tool calls run off the request thread: it serves the stateful
 * transport and declares a {@code McpSyncServerCustomizer} of its own that leaves
 * {@code immediateExecution} as the MCP SDK sets it, so the SDK hands each call to a
 * worker thread of Reactor's bounded elastic scheduler. The bean is marked primary, or,
 * with {@code --host.customizer=named}, carries the name of the parameter Spring AI's
 * server bean takes it through, which are the two ways a second bean of the type gets
 * past Spring's choice of one.
 *
 * <p>
 * The test that launches it runs it in a JVM of its own, because what a worker thread
 * holds depends on state a JVM keeps for every application in it: Spring Security's
 * context holder strategy, Reactor's hook for automatic context propagation, and the
 * worker threads the scheduler already made. The program argument {@code --host.strategy}
 * names the strategy to set before the application starts, the way an application sets it
 * in its own {@code main}, and {@code --host.tokens} holds the bearer token of each
 * caller, in the order the calls are made, or {@code anonymous} for a caller without a
 * token.
 *
 * <p>
 * As it stands the host stops at startup, because GATool refuses an MCP server built
 * without immediate execution. {@code --host.shape=converted-without-immediate-execution}
 * gets past that stop and keeps the calls on a worker thread, which is the state the
 * refusal at call time is tested in.
 *
 * <p>
 * Each caller opens a session and calls {@code topRatedMovies} once. The host prints one
 * line per call, opened by {@code OFF-THREAD-CALL}, with what the endpoint answered, and
 * one line with the caller and the thread the rate limiter was asked about, in the order
 * of the calls.
 *
 * <p>
 * It lives outside {@code io.gatool.tests.skeleton}, whose {@code @SpringBootApplication}
 * classes scan their package.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ OffThreadCallHostApplication.PrimaryCustomizer.class, OffThreadCallHostApplication.NamedCustomizer.class,
		OffThreadCallHostApplication.ConvertedWithoutImmediateExecution.class })
public class OffThreadCallHostApplication {

	/** The prefix of the lines this host prints. */
	public static final String LINE = "OFF-THREAD-CALL ";

	private static final String STRATEGY_ARGUMENT = "--host.strategy=";

	private static final String ANONYMOUS = "anonymous";

	private static final List<String> SEEN = new CopyOnWriteArrayList<>();

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"off-thread-host\",\"version\":\"1\"}}}";

	private static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";

	private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
			+ "{\"name\":\"topRatedMovies\",\"arguments\":{\"first\":1}}}";

	public static void main(String[] args) throws Exception {
		for (String argument : args) {
			if (argument.startsWith(STRATEGY_ARGUMENT)) {
				SecurityContextHolder.setStrategyName(argument.substring(STRATEGY_ARGUMENT.length()));
			}
		}
		ConfigurableApplicationContext context = new SpringApplicationBuilder(OffThreadCallHostApplication.class)
			.web(WebApplicationType.SERVLET)
			.run(args);
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		String tokens = context.getEnvironment().getRequiredProperty("host.tokens");
		int call = 0;
		for (String token : tokens.split(",")) {
			call++;
			String session = post(port, token, null, INITIALIZE)[2];
			post(port, token, session, INITIALIZED);
			String[] answer = post(port, token, session, CALL);
			System.out
				.println(LINE + "call=" + call + " status=" + answer[0] + " body=" + answer[1].replace('\n', ' '));
		}
		System.out.println(LINE + "seen=" + SEEN);
		context.close();
		System.exit(0);
	}

	private static String[] post(int port, String token, @Nullable String session, String body) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/mcp")
			.toURL()
			.openConnection();
		connection.setRequestMethod("POST");
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(20000);
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json, text/event-stream");
		connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25");
		if (!ANONYMOUS.equals(token)) {
			connection.setRequestProperty("Authorization", "Bearer " + token);
		}
		if (session != null) {
			connection.setRequestProperty("Mcp-Session-Id", session);
		}
		connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
		int status = connection.getResponseCode();
		InputStream stream = (status < 400) ? connection.getInputStream() : connection.getErrorStream();
		String text = (stream != null) ? new String(stream.readAllBytes(), StandardCharsets.UTF_8) : "";
		String opened = connection.getHeaderField("Mcp-Session-Id");
		connection.disconnect();
		return new String[] { String.valueOf(status), text, (opened != null) ? opened : "" };
	}

	/** Records the caller GATool named for each call, and the thread the call ran on. */
	@Bean
	ToolRateLimiter recordingRateLimiter() {
		return (caller, toolName) -> {
			SEEN.add(caller + " on " + Thread.currentThread().getName());
			return RateLimitDecision.allow();
		};
	}

	// Spring AI's server bean takes one customizer, and the primary one wins over the
	// bean Spring AI contributes, which is the one that sets immediateExecution(true).
	// This one sets the request timeout alone, so the SDK's default stays: a synchronous
	// tool handler is subscribed on Schedulers.boundedElastic().
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "host.customizer", havingValue = "primary", matchIfMissing = true)
	static class PrimaryCustomizer {

		@Bean
		@Primary
		McpSyncServerCustomizer ownServerCustomizer() {
			return (server) -> server.requestTimeout(Duration.ofSeconds(30));
		}

	}

	// Spring falls back to the name of the parameter where it finds several beans of a
	// type, and Spring AI's server bean method calls its parameter
	// mcpSyncServerCustomizer.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "host.customizer", havingValue = "named")
	static class NamedCustomizer {

		@Bean
		McpSyncServerCustomizer mcpSyncServerCustomizer() {
			return (server) -> server.requestTimeout(Duration.ofSeconds(30));
		}

	}

	/**
	 * The shape whose server reads {@code immediateExecution} as true while its tools
	 * were converted without it, which is how a call reaches a worker thread in an
	 * application that passes GATool's startup check.
	 */
	// The builder converts each synchronous tool specification as the server is built,
	// and the customizer above left immediate execution out, so every tool is already
	// wrapped for Reactor's bounded elastic scheduler. Building a second McpSyncServer
	// around the same asynchronous server changes what the setting reads and leaves
	// those tools as they are. It stands in for a transport of the application's own
	// that hands a call to another thread, which GATool cannot see at startup either.
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "host.shape", havingValue = "converted-without-immediate-execution")
	static class ConvertedWithoutImmediateExecution {

		@Bean
		static BeanPostProcessor immediateExecutionOnTheServerAlone() {
			return new BeanPostProcessor() {

				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					if (bean instanceof McpSyncServer server) {
						return new McpSyncServer(server.getAsyncServer(), true);
					}
					return bean;
				}
			};
		}

	}

}
