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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digits of a default, as they travel over HTTP.
 *
 * <p>
 * GATool writes a decimal default on a custom scalar with every digit the operation file
 * spells, and a whole number past a long the same way. The MCP Java SDK builds its tool
 * record by parsing that text again, and a mapper that reads a JSON float into a double
 * turns {@code 1234567890.123456789012345678} into {@code 1.2345678901234567E9} on the
 * wire. The body is read as text here, because an SDK client parses the number the same
 * way and would hide the rounding.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:gatool/pass-through/amounts.graphqls",
				"gatool.mcp.operations.locations=classpath:gatool/pass-through/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/" })
class McpDefaultDigitsOverHttpTests {

	private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

	@LocalServerPort
	private int port;

	@Test
	void listTools_aDecimalDefaultOnACustomScalar_shouldCarryEveryDigitOnTheWire() throws Exception {
		HttpResponse<String> response = post(TOOLS_LIST);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"default\":1234567890.123456789012345678")
			.doesNotContain("1.2345678901234567E9");
	}

	@Test
	void listTools_aWholeNumberDefaultPastALong_shouldCarryItsDigitsOnTheWire() throws Exception {
		HttpResponse<String> response = post(TOOLS_LIST);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"default\":9223372036854775808");
	}

	private HttpResponse<String> post(String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", "2025-11-25")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
