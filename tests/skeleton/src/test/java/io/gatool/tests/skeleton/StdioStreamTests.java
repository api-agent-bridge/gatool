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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under stdio, stdout carries JSON-RPC alone.
 *
 * <p>
 * Spring Boot logs to stdout, so a single log line corrupts the stream and the client's
 * parser gives up. {@code GAToolEnvironmentPostProcessor} switches the console log off,
 * the banner off and the web server off for stdio, and this reads the raw stream to hold
 * that. The second test is the control: it turns the console log back on and shows the
 * corruption the default prevents, so a later change to those defaults fails here with
 * the reason visible.
 */
class StdioStreamTests {

	private static final String INITIALIZE = initializeBody("2025-11-25");

	@Test
	void stdout_gaToolDefaults_shouldCarryJsonRpcAlone() throws Exception {
		List<String> lines = run(List.of());

		assertThat(lines).isNotEmpty().allSatisfy((line) -> assertThat(line).startsWith("{\"jsonrpc\""));
		assertThat(String.join("\n", lines)).contains("\"protocolVersion\":\"2025-11-25\"");
	}

	@Test
	void stdout_consoleLoggingTurnedBackOn_shouldCarryLogLinesBesideJsonRpc() throws Exception {
		List<String> lines = run(List.of("--logging.console.enabled=true", "--spring.main.banner-mode=console"));

		// Spring Boot writes its log to stdout, so the stream a client parses fills with
		// lines that are not JSON-RPC. This is the failure GATool's defaults avoid.
		assertThat(lines).anySatisfy((line) -> assertThat(line).doesNotStartWith("{\"jsonrpc\""));
	}

	@Test
	void initialize_clientAskingForASupersededRevision_shouldAnswerWithTheNewestGAToolServes() throws Exception {
		// MCP Java SDK 2.0.0 leaves the JSON-RPC batches 2025-03-26 requires unparsed, so
		// GATool refuses that revision over HTTP and the stdio transport has to refuse it
		// too. The SDK's own list carries it, and reaching GATool's list needs a
		// transport provider GATool built, so this fails whenever that wiring stops
		// taking effect.
		List<String> lines = run(List.of(), initializeBody("2025-03-26"));

		assertThat(String.join("\n", lines)).contains("\"protocolVersion\":\"2025-11-25\"")
			.doesNotContain("2025-03-26");
	}

	@Test
	void initialize_supersededRevisionsSwitchedBackOn_shouldAgreeToTheOneTheClientAsked() throws Exception {
		// The control. The unsafe switch is documented as adding those revisions back,
		// and the transport provider GATool builds is what makes the switch apply over
		// stdio.
		List<String> lines = run(List.of("--gatool.mcp.security.unsafe.allow-superseded-mcp-revisions=true"),
				initializeBody("2025-03-26"));

		assertThat(String.join("\n", lines)).contains("\"protocolVersion\":\"2025-03-26\"");
	}

	private static String initializeBody(String protocolVersion) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + "{\"protocolVersion\":\""
				+ protocolVersion + "\",\"capabilities\":{},"
				+ "\"clientInfo\":{\"name\":\"stream-probe\",\"version\":\"1\"}}}";
	}

	private static List<String> run(List<String> extraArguments) throws Exception {
		return run(extraArguments, INITIALIZE);
	}

	private static List<String> run(List<String> extraArguments, String initialize) throws Exception {
		List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-cp",
				System.getProperty("java.class.path"), StdioServerApplication.class.getName(),
				"--spring.ai.mcp.server.stdio=true", "--spring.application.name=gatool-skeleton-tests",
				"--gatool.api.url=http://127.0.0.1:1/graphql",
				"--gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls"));
		command.addAll(extraArguments);
		Process server = new ProcessBuilder(command).redirectErrorStream(false).start();
		try {
			try (Writer toServer = new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8)) {
				toServer.write(initialize + System.lineSeparator());
				toServer.flush();
				return readUntilTheAnswer(server);
			}
		}
		finally {
			server.destroy();
			server.waitFor(20, TimeUnit.SECONDS);
		}
	}

	// The server answers and then waits for more input, so the stream stays open and
	// the reader stops at the answer.
	private static List<String> readUntilTheAnswer(Process server) throws IOException {
		List<String> lines = new ArrayList<>();
		BufferedReader fromServer = new BufferedReader(
				new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));
		String line;
		while ((line = fromServer.readLine()) != null) {
			lines.add(line);
			if (line.contains("\"id\":1")) {
				break;
			}
		}
		return lines;
	}

}
