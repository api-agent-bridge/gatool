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

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonComparator;
import org.springframework.test.json.JsonCompareMode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Compares the tool contract this server publishes with a committed snapshot.
 *
 * <p>
 * A patch release produces identical tool definitions for the same schema and operation
 * files, so this test fails when a change alters what agents see: a name, a description,
 * an input schema or an annotation. A deliberate change arrives with an updated snapshot
 * file in the same commit, which is what makes the change visible in review.
 *
 * <p>
 * The list is sorted by tool name before the comparison, because {@code tools/list}
 * carries an array and the contract leaves its order to the server.
 */
@SpringBootTest(classes = ToolContractSnapshotTests.SnapshotApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class ToolContractSnapshotTests {

	private static final String SNAPSHOT = "gatool/snapshot/tools-list.json";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	// STRICT is JSONAssert's strict mode, which the two recording tests below pin down:
	// it holds array order and rejects an unexpected field. The sort above makes the
	// first of those safe, and the second is what a contract snapshot is for.
	private static final JsonComparator COMPARATOR = JsonAssert.comparator(JsonCompareMode.STRICT);

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void listTools_theServedTools_shouldMatchTheCommittedSnapshot() throws Exception {
		String served = sortedToolsList();

		COMPARATOR.assertIsMatch(snapshot(), served);
	}

	@Test
	void comparator_reorderedObjectKey_shouldStillMatch() {
		// JSON leaves object keys unordered, so a change in key order leaves the contract
		// as it was.
		String one = "{\"name\":\"topRatedMovies\",\"description\":\"Returns the highest-rated movies, best first.\"}";
		String other = "{\"description\":\"Returns the highest-rated movies, best first.\",\"name\":\"topRatedMovies\"}";

		COMPARATOR.assertIsMatch(one, other);
	}

	@Test
	void comparator_reorderedArrayEntry_shouldFail() {
		// This mode holds array order, which is why the test above sorts the tools by
		// name before comparing.
		String one = "{\"tools\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
		String other = "{\"tools\":[{\"name\":\"b\"},{\"name\":\"a\"}]}";

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> COMPARATOR.assertIsMatch(one, other));
	}

	// The snapshot holds what an agent reads, so the tools arrive through a real client,
	// and each tool keeps the four fields of the contract: the name, the description,
	// the input schema and the annotations.
	private String sortedToolsList() {
		try (McpSyncClient client = mcpClient()) {
			client.initialize();
			List<McpSchema.Tool> tools = client.listTools()
				.tools()
				.stream()
				.sorted(Comparator.comparing(McpSchema.Tool::name))
				.toList();
			ObjectNode root = JSON.createObjectNode();
			ArrayNode array = root.putArray("tools");
			for (McpSchema.Tool tool : tools) {
				ObjectNode node = array.addObject();
				node.put("name", tool.name());
				node.put("description", tool.description());
				node.set("inputSchema", JSON.valueToTree(tool.inputSchema()));
				node.set("annotations", JSON.valueToTree(tool.annotations()));
			}
			return writer().writeValueAsString(root);
		}
	}

	private static ObjectWriter writer() {
		return JSON.writer();
	}

	private static String snapshot() throws Exception {
		String text = new ClassPathResource(SNAPSHOT).getContentAsString(StandardCharsets.UTF_8);
		JsonNode parsed = JSON.readTree(text);
		return writer().writeValueAsString(parsed);
	}

	private McpSyncClient mcpClient() {
		return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + this.port).build())
			.build();
	}

	// This package holds more than one @SpringBootConfiguration, so a test that does
	// not name one leaves Boot's finder with several and it refuses to choose. Each
	// test here therefore brings its own, as the in-process end-to-end test does.
	@SpringBootApplication
	static class SnapshotApplication {

	}

}
