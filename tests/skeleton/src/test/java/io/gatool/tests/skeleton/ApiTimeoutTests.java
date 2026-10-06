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
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A GraphQL API that stops answering must not hold the tool call open.
 *
 * <p>
 * The deadline is the application's, from {@code spring.http.clients.connect-timeout} and
 * {@code spring.http.clients.read-timeout}. Spring Boot does not declare a default for
 * either, so an application that leaves both unset would have every call running until
 * the socket closes, which takes more than 75 seconds, holding the request thread and the
 * agent with it. GATool therefore fills whichever deadline the application left unset and
 * names the applied values at startup, and those lines are what these tests pin. That the
 * timeout itself fires belongs to Spring's HTTP client, so one call covers it and the
 * rest stay out of the way.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiTimeoutTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final CountDownLatch RELEASED = new CountDownLatch(1);

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues(SCHEMA);

	@BeforeAll
	static void startSilentApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		// The handler accepts the request and withholds the answer, as an API that has
		// stopped responding does: the connection is open and the body does not arrive.
		server.createContext("/graphql", (exchange) -> {
			try {
				// Released when the class finishes, so the build does not wait for a
				// handler parked on a deadline. Thirty seconds is the backstop for a
				// handler the latch somehow misses, and it stays clear of both the one
				// second deadline one call below sets and the 15 second fallback another
				// call below waits out, so no test here waits on this number.
				RELEASED.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();
	}

	@AfterAll
	static void stopSilentApi() {
		RELEASED.countDown();
		server.stop(0);
	}

	@Test
	void call_apiThatNeverAnswers_shouldGiveUpAtTheConfiguredReadTimeout() {
		this.contextRunner.withPropertyValues(apiUrlProperty(), "spring.http.clients.read-timeout=1s")
			.run((context) -> {
				GATool tool = context.getBean(GAToolCatalog.class).mcpTools().getFirst();

				long startedAt = System.nanoTime();
				assertThatExceptionOfType(ToolCallFailedException.class).isThrownBy(() -> tool.call(Map.of()))
					.withMessageContaining("topRatedMovies");
				Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

				// One second of deadline, and room for the call around it. Without the
				// deadline this assertion waits for the two minute handler above.
				assertThat(waited).isLessThan(Duration.ofSeconds(15));
			});
	}

	@Test
	void startup_noDeadlineConfigured_shouldNameTheDeadlinesGAToolSupplied(CapturedOutput output) {
		// Spring Boot does not declare a default for either property, so GATool fills
		// both and says which property decides each one.
		this.contextRunner.withPropertyValues(apiUrlProperty()).run((context) -> {
			assertThat(context).hasNotFailed();

			assertThat(output.getAll()).contains("waiting up to 3000ms to connect and 15000ms for an answer")
				.contains("GATool supplied both deadlines, because spring.http.clients.connect-timeout and "
						+ "spring.http.clients.read-timeout are unset");
		});
	}

	@Test
	void startup_apiUrlCarryingUserinfoAndAQuery_shouldKeepBothOutOfTheDeadlineLine(CapturedOutput output) {
		// The line names the API on every remote-mode startup, and a URL can carry a
		// password in its userinfo or a key in its query, the way a registry URL does.
		// The line prints the URL without either, as the schema fetch does.
		int port = server.getAddress().getPort();
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://user:S3cretPassw0rd@127.0.0.1:" + port + "/graphql?api_key=k123")
			.run((context) -> {
				assertThat(context).hasNotFailed();

				assertThat(output.getAll())
					.contains("sends each document to the GraphQL API at http://127.0.0.1:" + port + "/graphql,")
					.doesNotContain("S3cretPassw0rd")
					.doesNotContain("api_key=k123");
			});
	}

	@Test
	void startup_bothDeadlinesConfigured_shouldNameTheValuesAndLeaveTheWarningOut(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues(apiUrlProperty(), "spring.http.clients.connect-timeout=2s",
					"spring.http.clients.read-timeout=3s")
			.run((context) -> {
				assertThat(context).hasNotFailed();

				// The values are the application's own, and GATool reads them from the
				// settings Boot built from the properties.
				assertThat(output.getAll()).contains("waiting up to 2000ms to connect and 3000ms for an answer");
				assertThat(output.getAll()).doesNotContain("is unset");
			});
	}

	@Test
	@Timeout(30)
	void callTool_apiThatStaysSilentWithTheJavaClientAtItsDefault_shouldGiveTheModelGAToolsSentence() {
		// This test uses a real MCP client instead of the in-process call the tests
		// above make, because the race is between the client's own request timeout and
		// the deadline GATool supplies for the call to the API. The client is built
		// without requestTimeout, so the Java SDK's own default, 20 seconds, stands, and
		// no property here sets spring.http.clients.read-timeout, so GATool's fallback
		// decides.
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder().sources(SilentApiApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties("server.port=0", "spring.main.banner-mode=off", "spring.ai.mcp.server.protocol=STATELESS",
					"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true", SCHEMA, apiUrlProperty(),
					"gatool.mcp.operations.locations=classpath:gatool/mutation/")
			.run()) {
			int port = ((WebServerApplicationContext) context).getWebServer().getPort();

			try (McpSyncClient client = McpClient
				.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build())
				.build()) {
				client.initialize();

				McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("addReview")
					.arguments(Map.of("input", Map.of("movieId", "movie-1", "score", 9)))
					.build());

				// GATool's own deadline passes before the client's, so the model reads
				// GATool's sentence as a tool error, and after a mutation that sentence
				// asks it to read the current state before it sends the write again.
				assertThat(result.isError()).isTrue();
				assertThat(text(result)).contains("the read timeout of 15 seconds")
					.contains("read the current state before you send it again");
			}
		}
	}

	private static String text(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.findFirst()
			.orElse("");
	}

	private static String apiUrlProperty() {
		return "gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql";
	}

	// Without a component scan, so that the test classes of this package stay out of
	// the context, the way McpLazyInitializationOverHttpTests's own application does.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class SilentApiApplication {

	}

}
