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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the handshake the way a client pinned to an older revision does.
 *
 * <p>
 * The MCP Java SDK answers {@code initialize} from the revision list its transport
 * carries, and the compliance filter checks the {@code MCP-Protocol-Version} header of
 * every request after it. Where the two lists differ, the SDK agrees to 2025-03-26 and
 * the filter then refuses every request made under it, so a client has a session it
 * cannot use. These tests hold the two lists together.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS", "spring.application.name=gatool-skeleton-tests",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class McpProtocolRevisionTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String NEWEST = "2025-11-25";

	private static final String SUPERSEDED = "2025-03-26";

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void initialize_clientAskingForASupersededRevision_shouldAnswerWithTheNewestThisServerServes() throws Exception {
		HttpResponse<String> response = post(initializeBody(SUPERSEDED), null);

		assertThat(response.statusCode()).isEqualTo(200);
		// MCP asks a server that does not serve the requested revision to answer with
		// one it does serve, so the client can step down or disconnect.
		assertThat(revisionOf(response.body())).isEqualTo(NEWEST);
	}

	@Test
	void toolsList_afterAHandshakeThatSteppedTheClientUp_shouldSucceedUnderTheAgreedRevision() throws Exception {
		String agreed = revisionOf(post(initializeBody(SUPERSEDED), null).body());

		HttpResponse<String> tools = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
				agreed);

		assertThat(tools.statusCode()).isEqualTo(200);
		assertThat(tools.body()).contains("topRatedMovies");
	}

	@Test
	void initialize_clientAskingForTheNewestRevision_shouldAgreeToIt() throws Exception {
		HttpResponse<String> response = post(initializeBody(NEWEST), null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(revisionOf(response.body())).isEqualTo(NEWEST);
	}

	@Test
	void initialize_applicationServingToolsAlone_shouldAdvertiseToolsAndNothingElse() throws Exception {
		String body = post(initializeBody(NEWEST), null).body();

		// Spring AI's capability properties default to true, and GATool turns off the
		// three this server does not serve, because a client that reads capabilities
		// before calling tries each one advertised.
		assertThat(body).contains("\"tools\"")
			.doesNotContain("\"resources\"")
			.doesNotContain("\"prompts\"")
			.doesNotContain("\"completions\"");
	}

	@Test
	void initialize_applicationThatConfiguresNothing_shouldIntroduceItselfAndSayWhatAResultIs() throws Exception {
		String body = post(initializeBody(NEWEST), null).body();

		// Spring AI's defaults introduce a server as mcp-server 1.0.0, without
		// instructions. The application's own name is the one GATool can know, and the
		// instructions carry what every GATool tool shares.
		assertThat(body).contains("\"name\":\"gatool-skeleton-tests\"")
			.contains("trusted GraphQL documents")
			.contains("errors array");
	}

	@Test
	void initialize_carryingAVersionHeaderThatIsNotARevision_shouldAnswerBadRequest() throws Exception {
		// The Protocol Version Header section makes 400 a MUST for an invalid value. A
		// handshake may reach for a newer revision, and every ASCII letter sorts above
		// every digit, so a comparison of raw strings would take "garbage" for one.
		HttpResponse<String> response = post(initializeBody(NEWEST), "garbage");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("garbage");
	}

	@Test
	void request_carryingASupersededRevisionHeader_shouldStayRefused() throws Exception {
		// A superseded revision is refused on every request that carries it, and the
		// handshake answers with a revision this server serves, so a client is not
		// handed a superseded one to carry.
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}",
				SUPERSEDED);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("2025-06-18 and 2025-11-25").contains(SUPERSEDED);
	}

	@Test
	void request_withoutAVersionHeader_shouldBeServedAtTheRevisionThisServerHas() throws Exception {
		// MCP asks a server that receives a request without a version header to assume
		// 2025-03-26, for backwards compatibility. GATool leaves that revision out,
		// because the SDK cannot parse the JSON-RPC batches it requires, so assuming it
		// would mean refusing the call, which is the outcome the rule exists to prevent.
		// Serving the request keeps a client working behind a proxy that strips the
		// header.
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\",\"params\":{}}",
				null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("topRatedMovies");
	}

	private static String initializeBody(String revision) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + revision
				+ "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"revision-probe\",\"version\":\"1\"}}}";
	}

	private static String revisionOf(String body) {
		JsonNode message = JSON.readTree(body);
		return message.path("result").path("protocolVersion").asString();
	}

	private HttpResponse<String> post(String body, String revisionHeader) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(body));
		if (revisionHeader != null) {
			request.header("MCP-Protocol-Version", revisionHeader);
		}
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
