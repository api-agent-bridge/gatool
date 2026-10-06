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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line Spring AI writes at startup in an application whose tools all come from
 * operation files: {@code No tool methods found in the provided tool
 * objects: []}, at WARN.
 *
 * <p>
 * Spring AI's annotation scanner collects the beans that carry {@code @McpTool} methods
 * and builds a list of tool specifications from them. An application without such a
 * method hands it an empty list of beans, and the provider says so. The tools of the
 * operation files reach the server as a list bean of GATool's own, which the server reads
 * beside the scanner's, so the endpoint serves them whatever the line says. With the
 * scanner switched off the line is gone and the tools are served all the same.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpAnnotationScannerLineTests {

	private static final String LINE = "No tool methods found in the provided tool objects: []";

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"scanner-line\",\"version\":\"1\"}}}";

	private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

	@Test
	void startup_statelessWithToolsFromOperationFilesAlone_shouldCarryTheLineAtWarnAndServeTheTools(
			CapturedOutput output) {
		try (ConfigurableApplicationContext context = start("STATELESS")) {
			assertThat(linesHolding(output, LINE)).singleElement()
				.asString()
				.contains(" WARN ")
				.contains("SyncStatelessMcpToolProvider");
			// Spring AI's server counts what it registered, the tool of the operation
			// file included, a few lines further down.
			assertThat(linesHolding(output, "Registered tools: 1")).singleElement().asString().contains(" INFO ");
			assertThat(post(context, null, TOOLS_LIST).getBody()).contains("\"name\":\"topRatedMovies\"");
		}
	}

	@Test
	void startup_statefulWithToolsFromOperationFilesAlone_shouldCarryTheLineAtWarnAndServeTheTools(
			CapturedOutput output) {
		try (ConfigurableApplicationContext context = start("STREAMABLE")) {
			assertThat(linesHolding(output, LINE)).singleElement()
				.asString()
				.contains(" WARN ")
				.contains("SyncMcpToolProvider");
			assertThat(linesHolding(output, "Registered tools: 1")).singleElement().asString().contains(" INFO ");
			String session = post(context, null, INITIALIZE).getHeaders().getFirst("Mcp-Session-Id");
			assertThat(post(context, session, TOOLS_LIST).getBody()).contains("\"name\":\"topRatedMovies\"");
		}
	}

	@Test
	void startup_withTheScannerSwitchedOff_shouldLeaveTheLineOutAndServeTheTools(CapturedOutput output) {
		try (ConfigurableApplicationContext context = start("STATELESS",
				"spring.ai.mcp.server.annotation-scanner.enabled=false")) {
			assertThat(linesHolding(output, "No tool methods found")).isEmpty();
			assertThat(linesHolding(output, "Registered tools: 1")).hasSize(1);
			assertThat(post(context, null, TOOLS_LIST).getBody()).contains("\"name\":\"topRatedMovies\"");
		}
	}

	private static List<String> linesHolding(CapturedOutput output, String text) {
		return output.getAll().lines().filter((line) -> line.contains(text)).toList();
	}

	private static ConfigurableApplicationContext start(String protocol, String... more) {
		String[] base = { "server.port=0", "spring.main.banner-mode=off", "spring.ai.mcp.server.protocol=" + protocol,
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" };
		String[] properties = new String[base.length + more.length];
		System.arraycopy(base, 0, properties, 0, base.length);
		System.arraycopy(more, 0, properties, base.length, more.length);
		return new SpringApplicationBuilder().sources(ScannerLineApplication.class)
			.web(WebApplicationType.SERVLET)
			.properties(properties)
			.run();
	}

	private static ResponseEntity<String> post(ConfigurableApplicationContext context, String sessionId, String body) {
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		return RestClient.builder()
			.baseUrl("http://127.0.0.1:" + port)
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.headers((headers) -> {
				if (sessionId != null) {
					headers.set("Mcp-Session-Id", sessionId);
				}
			})
			.body(body)
			.exchange((request, reply) -> new ResponseEntity<>(reply.bodyTo(String.class), reply.getHeaders(),
					reply.getStatusCode()));
	}

	// Without a component scan, so that the test classes of this package, some of which
	// declare @McpTool methods, stay out of the context.
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ScannerLineApplication {

	}

}
