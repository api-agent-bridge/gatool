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

package io.gatool.boot.mcp.internal.transport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

// Spring's servlet test doubles run the filter the way a container does, so these tests
// assert the status and the body that a client would read.
class McpComplianceFilterTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private final MockFilterChain chain = new MockFilterChain();

	@Test
	void doFilter_initializeWithoutAnOriginOrAVersion_shouldReachTheServer() throws Exception {
		// The handshake is the one call that arrives before a version is agreed.
		filter(List.of(), false).doFilter(initializePost("/mcp"), this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(this.response.getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_originOutsideTheList_shouldAnswerForbiddenWithAJsonRpcError() throws Exception {
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader("Origin", "https://evil.example.com");

		filter(List.of("https://inspector.example.com"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(403);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(error.path("id").isNull()).isTrue();
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(error.path("error").path("message").asString()).contains("https://evil.example.com");
	}

	@Test
	void doFilter_originInsideTheList_shouldReachTheServer() throws Exception {
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader("Origin", "https://inspector.example.com");

		filter(List.of("https://inspector.example.com"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_supportedRevision_shouldReachTheServer() throws Exception {
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_supersededRevision_shouldAnswerBadRequestNamingWhatTheServerServes() throws Exception {
		// This request carries an initialize body, so it also proves that the exemption
		// below reaches a newer revision alone and leaves a superseded one refused.
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2024-11-05");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("error").path("message").asString()).contains("2025-11-25", "2024-11-05");
	}

	@Test
	void doFilter_initializeAskingForANewerRevision_shouldReachTheServerToNegotiate() throws Exception {
		// Claude Code asks for 2026-07-28 before it tries anything older. MCP asks a
		// server to answer the handshake with a revision it supports, so the call reaches
		// the SDK and the SDK picks the revision.
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2026-07-28");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(this.response.getStatus()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = { "garbage", "latest", "draft", "v2", "Z", "2025-11-250", "2025-11-25 ", "3" })
	void doFilter_initializeCarryingAValueThatIsNotARevision_shouldAnswerBadRequest(String revision) throws Exception {
		// A comparison of raw strings orders any two of them: every ASCII letter sorts
		// above every digit, so each value here sorts above 2025-11-25 and would take the
		// exemption written for a future revision. The header rule makes 400 a MUST for
		// an invalid value, and the shape check is what tells an invalid one from a
		// revision this server has not heard of.
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, revision);

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
	}

	@Test
	void doFilter_callAfterTheHandshakeAskingForANewerRevision_shouldAnswerBadRequest() throws Exception {
		// The exemption covers the handshake alone. Once a revision is agreed, a request
		// naming another one is a client contradicting its own handshake.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2026-07-28");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(4);
		assertThat(error.path("error").path("message").asString()).contains("2026-07-28");
	}

	@Test
	void doFilter_supersededRevisionWithTheUnsafeSwitch_shouldReachTheServer() throws Exception {
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2024-11-05");

		filter(List.of(), true).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_anotherPath_shouldPassEveryCheck() throws Exception {
		// The filter sees every request, so a foreign origin on another endpoint stays
		// the application's own business.
		MockHttpServletRequest request = initializePost("/graphql");
		request.addHeader("Origin", "https://evil.example.com");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_mcpPathCarryingAPathParameter_shouldRefuseTheForeignOrigin() throws Exception {
		// A container removes path parameters before it chooses a handler, so /mcp;x=1
		// reaches the MCP server. Matching the raw URI would let that spelling skip every
		// rule.
		MockHttpServletRequest request = post("/mcp;x=1", INITIALIZE);
		request.addHeader("Origin", "https://evil.example.com");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(403);
	}

	@Test
	void doFilter_mcpPathWrittenWithAPercentEscape_shouldRefuseTheForeignOrigin() throws Exception {
		// A container decodes the path before it chooses a handler, so /%6Dcp reaches the
		// MCP server as /mcp.
		MockHttpServletRequest request = post("/%6Dcp", INITIALIZE);
		request.addHeader("Origin", "https://evil.example.com");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(403);
	}

	@Test
	void doFilter_callAfterTheHandshakeWithoutTheVersionHeader_shouldReachTheServer() throws Exception {
		// MCP asks a server that does not receive the version header to assume
		// 2025-03-26,
		// for backwards compatibility. This release serves 2025-11-25 and 2025-06-18, so
		// assuming that revision would mean refusing the call, which is the outcome the
		// rule exists to prevent. The call is served at the revision this server has.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_callAfterTheHandshakeNamingASupersededRevision_shouldStillBeRefused() throws Exception {
		// The MUST in the same paragraph stays: "If the server receives a request with
		// an invalid or unsupported MCP-Protocol-Version, it MUST respond with 400 Bad
		// Request." A header that names a revision this server leaves out is that case.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-03-26");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(7);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
	}

	@Test
	void doFilter_toolsListCarryingACursor_shouldReachTheServer() throws Exception {
		MockHttpServletRequest request = post("/mcp",
				"{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{\"cursor\":\"page-two\"}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		// MCP's pagination rule recommends -32602 for a cursor no answer of this server
		// ever issued. A servlet filter is the only seat for it, so the rule would hold
		// over HTTP while GATool serves stdio as well, and MCP Java SDK 2.0.0 leaves
		// server-side pagination out on both. GATool waits for the SDK, and a client
		// reads every tool either way, because one page is the whole list.
		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_toolsListWithoutACursor_shouldReachTheServer() throws Exception {
		MockHttpServletRequest request = post("/mcp",
				"{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_bodyAboveTheCap_shouldAnswerContentTooLargeNamingTheProperty() throws Exception {
		MockHttpServletRequest request = post("/mcp", INITIALIZE);

		new McpComplianceFilter("/mcp", List.of(), false, DataSize.ofBytes(20), JSON,
				McpComplianceFilter.STATELESS_METHODS)
			.doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(413);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("error").path("message").asString()).contains("20 bytes",
				"gatool.mcp.transport.max-request-body-size");
	}

	@Test
	void doFilter_bodyUnderTheCap_shouldHandTheServerTheWholeBody() throws Exception {
		// The filter reads the body, and a servlet body reads once, so this asserts that
		// Spring AI's transport still receives every byte the client sent.
		filter(List.of(), false).doFilter(post("/mcp", INITIALIZE), this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		String delivered = new String(this.chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(delivered).isEqualTo(INITIALIZE);
	}

	@Test
	void doFilter_bodyThatFailsToParse_shouldAnswerAParseErrorAheadOfTheServer() throws Exception {
		// The security chain decides a tools/call by what this filter read, so a body the
		// filter cannot read is refused here, ahead of the MCP server.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32700);
	}

	@Test
	void doFilter_emptyBody_shouldAnswerAParseError() throws Exception {
		MockHttpServletRequest request = post("/mcp", "  ");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32700);
	}

	@Test
	void doFilter_validJsonThatIsNotAnObject_shouldAnswerInvalidRequest() throws Exception {
		// JSON-RPC keeps -32700 for text that is not JSON; a number is JSON and is not a
		// request object, which is -32600.
		MockHttpServletRequest request = post("/mcp", "42");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32600);
	}

	@Test
	void doFilter_responseWithoutAMethod_shouldReadAsAbsentAndReachTheServer() throws Exception {
		// A JSON-RPC response a client posts carries an id and a result, and the filter
		// reads the method as absent and applies the header rules alone.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(this.chain.getRequest().getAttribute(McpComplianceFilter.METHOD_ATTRIBUTE)).isEqualTo("");
	}

	@Test
	void doFilter_charsetParameterWithSpacesAroundTheEquals_shouldStillBeRefused() throws Exception {
		// Spring's parser trims around the equals sign, and the transport reads the body
		// through that parser, so the filter has to read the same charset out of it.
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setRequestURI("/mcp");
		request.setContentType("application/json; charset = utf-16");
		request.setContent(INITIALIZE.getBytes(StandardCharsets.UTF_16));
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
	}

	@Test
	void doFilter_batch_shouldAnswerInvalidRequest() throws Exception {
		// The revisions this server serves send one message per request. A batch would
		// carry a tools/call the filter left unnamed, so it stops here.
		MockHttpServletRequest request = post("/mcp", "[" + INITIALIZE + "," + INITIALIZE + "]");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(error.path("error").path("message").asString()).contains("batch");
	}

	@Test
	void doFilter_bodyDeclaredInAnotherCharset_shouldAnswerInvalidRequestNamingIt() throws Exception {
		// Read as UTF-8 the UTF-16 bytes are not JSON, and read by the declared charset
		// they are a tools/call. Two readers disagreeing on one body is what a scope
		// check cannot allow, so the body is refused whichever reader is right.
		String call = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"secret\"}}";
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
		request.setRequestURI("/mcp");
		request.setContentType("application/json; charset=UTF-16");
		request.setContent(call.getBytes(StandardCharsets.UTF_16));
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(error.path("error").path("message").asString()).contains("UTF-16");
	}

	@Test
	void doFilter_bodyDeclaredAsUtf8WithAQuotedCharset_shouldReachTheServer() throws Exception {
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.setContentType("application/json; charset=\"utf-8\"");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_toolNameOutsidePrintableAscii_shouldAnswerInvalidParamsInsideA200() throws Exception {
		// The name would reach a challenge header and a log line, and a registered tool
		// cannot carry one like it, so the call is refused ahead of the security chain.
		// The status is 200, because the Java client drops its session on a 400.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"evil\\r\\nWWW-Authenticate: x\"}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(200);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(3);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
		assertThat(this.response.getContentAsString()).doesNotContain("WWW-Authenticate");
	}

	@Test
	void doFilter_toolNameLongerThanAHeaderCarries_shouldAnswerInvalidParams() throws Exception {
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"" + "a".repeat(257) + "\"}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32602);
	}

	@Test
	void doFilter_toolsCallWithoutAStringName_shouldAnswerInvalidParamsNamingTheField() throws Exception {
		MockHttpServletRequest request = post("/mcp",
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"," + "\"params\":{\"name\":42}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
		assertThat(error.path("error").path("message").asString()).contains("params.name has to be a string");
	}

	@Test
	void doFilter_methodOfAnotherJsonType_shouldAnswerInvalidRequest() throws Exception {
		// JSON-RPC makes the method a string, and the SDK would coerce a number into
		// one, so the filter refuses it ahead of the server.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":123}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32600);
	}

	@Test
	void doFilter_contentTypeThatIsNotJson_shouldAnswerUnsupportedMediaType() throws Exception {
		// A multipart body would reach the MCP server and fail inside it with a 500 after
		// the scope check passed on the JSON the filter read out of it.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.setContentType("multipart/form-data; boundary=x");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(415);
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32600);
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/*", "*/*" })
	void doFilter_contentTypeWithAWildcard_shouldAnswerUnsupportedMediaType(String contentType) throws Exception {
		// A wildcard is compatible with application/json in Spring's reading and names a
		// range of media types where the transport reads one. Spring's router refuses it
		// while it builds the request, so a wildcard this filter passed would leave the
		// caller reading Tomcat's HTML page under a 500.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.setContentType(contentType);

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(415);
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32600);
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json;charset=utf-8", "application/vnd.api+json" })
	void doFilter_concreteJsonMediaType_shouldReachTheServer(String contentType) throws Exception {
		// A charset parameter and a +json suffix both name JSON, and the transport reads
		// both.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.setContentType(contentType);

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "1.5", "{}", "[1]", "true", "null", "99999999999999999999" })
	void doFilter_idThatIsNeitherAStringNorAnInteger_shouldAnswerInvalidRequestWithANullId(String id) throws Exception {
		// MCP requires a request id that is a string or an integer, and the SDK's request
		// record asserts that in its constructor, so each of these would fail inside the
		// transport as a 500 carrying -32603, where JSON-RPC reserves -32600. The error's
		// id is null, because the id is what could not be read.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").isNull()).isTrue();
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
	}

	@Test
	void doFilter_methodThatIsNull_shouldAnswerInvalidRequestWithTheCallsId() throws Exception {
		// The SDK refuses a null method inside the transport, as a 500, and an absent
		// method is a different thing: a response a client posts.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":null}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(5);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
	}

	@Test
	void doFilter_originOutsideTheList_shouldRefuseBeforeReadingTheBody() throws Exception {
		// MCP asks for the Origin check ahead of everything else, and the Javadoc says
		// it runs first, so a refused page costs this server its headers alone. The body
		// here fails the size rule, whose 413 would have answered had the body been read.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.addHeader("Origin", "https://evil.example.com");

		new McpComplianceFilter("/mcp", List.of(), false, DataSize.ofBytes(20), JSON,
				McpComplianceFilter.STATELESS_METHODS)
			.doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(403);
		assertThat(request.getInputStream().available()).as("the bytes the filter left unread")
			.isEqualTo(INITIALIZE.length());
	}

	@Test
	void doFilter_asyncDispatchOfARequestAlreadyRead_shouldPassItThroughUnchanged() throws Exception {
		// The dispatch that ends a stateful stream carries the request this filter read
		// on the first pass, with its attributes, and the body is spent by then.
		MockHttpServletRequest request = post("/mcp", "");
		request.setDispatcherType(DispatcherType.ASYNC);
		request.setAttribute(McpComplianceFilter.METHOD_ATTRIBUTE, "tools/call");
		request.setAttribute(McpComplianceFilter.TOOL_NAME_ATTRIBUTE, "topRatedMovies");
		request.addHeader("Origin", "https://evil.example.com");

		filter(List.of("https://inspector.example.com"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isSameAs(request);
		assertThat(this.response.getStatus()).isEqualTo(200);
	}

	@Test
	void doFilter_messageWithoutAJsonrpcMember_shouldAnswerInvalidRequestWithTheCallsId() throws Exception {
		// The SDK's request record asserts the member in its constructor, and Jackson's
		// databind exception misses the transport's 400 branch, so the answer would be a
		// 500 carrying -32603 where JSON-RPC reserves -32600 for an invalid request.
		MockHttpServletRequest request = post("/mcp", "{\"id\":1,\"method\":\"ping\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(1);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(error.path("error").path("message").asString()).contains("jsonrpc", "2.0");
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"1.0\"", "2", "null", "\"2.0 \"" })
	void doFilter_jsonrpcMemberOtherThanTheString20_shouldAnswerInvalidRequest(String version) throws Exception {
		// JSON-RPC 2.0 makes the member the string "2.0" exactly, and the transport
		// accepts any text there.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":" + version + ",\"id\":1,\"method\":\"ping\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		assertThat(JSON.readTree(this.response.getContentAsString()).path("error").path("code").asInt())
			.isEqualTo(-32600);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"bad\"", "[1]", "42", "null" })
	void doFilter_paramsThatIsNotAnObject_shouldAnswerInvalidRequestWithTheCallsId(String params) throws Exception {
		// JSON-RPC 2.0 section 4.2 allows an object or an array, and MCP narrows every
		// method's params to an object. The SDK reads a string here for initialize and
		// answers 200 with -32603 and its own class name in the message.
		MockHttpServletRequest request = post("/mcp",
				"{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"initialize\",\"params\":" + params + "}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(400);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(8);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(error.path("error").path("message").asString()).contains("params");
	}

	@Test
	void doFilter_paramsAbsent_shouldReachTheServer() throws Exception {
		// ping and tools/list travel without params, so an absent member stays valid.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_toolsCallWhoseParamsIsNotAnObject_shouldAnswerInvalidParamsInsideA200() throws Exception {
		// The same rule under tools/call takes the answer an unnamed tool takes: -32602
		// inside a 200, because the Java client drops its session on a 400.
		MockHttpServletRequest request = post("/mcp",
				"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":\"bad\"}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(200);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(3);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"bad\"", "[1]", "7", "null" })
	void doFilter_toolsCallWhoseArgumentsAreNotAnObject_shouldAnswerInvalidParamsInsideA200(String arguments)
			throws Exception {
		// The SDK converts the arguments into a map inside the call, so a string there
		// would fail as a 500 on the stateless transport and escape the SSE consumer on
		// the stateful one.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"topRatedMovies\",\"arguments\":" + arguments + "}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(200);
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").asInt()).isEqualTo(3);
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32602);
		assertThat(error.path("error").path("message").asString()).contains("params.arguments");
	}

	@Test
	void doFilter_toolsCallWithoutArguments_shouldReachTheServer() throws Exception {
		// A tool without variables is called without the member, which the SDK reads as
		// an empty map.
		MockHttpServletRequest request = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
				+ "\"params\":{\"name\":\"topRatedMovies\"}}");
		request.addHeader(McpComplianceFilter.PROTOCOL_VERSION_HEADER, "2025-11-25");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(this.chain.getRequest().getAttribute(McpComplianceFilter.TOOL_NAME_ATTRIBUTE))
			.isEqualTo("topRatedMovies");
	}

	@ParameterizedTest
	@ValueSource(strings = { "PUT", "PATCH", "HEAD", "TRACE" })
	void doFilter_methodTheTransportDoesNotRoute_shouldAnswerMethodNotAllowedBeforeReadingTheBody(String method)
			throws Exception {
		// Spring AI's router serves GET and POST, and a stateful one DELETE as well. Any
		// other method would reach the router, which answers through the container's
		// error dispatch: Spring Security's chain there gives a bare 401, and without it
		// the caller reads Boot's HTML page.
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/mcp");
		request.setRequestURI("/mcp");
		request.setContent(INITIALIZE.getBytes(StandardCharsets.UTF_8));

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(405);
		assertThat(this.response.getHeader("Allow")).isEqualTo("GET, POST");
		JsonNode error = JSON.readTree(this.response.getContentAsString());
		assertThat(error.path("id").isNull()).isTrue();
		assertThat(error.path("error").path("code").asInt()).isEqualTo(-32600);
		assertThat(error.path("error").path("message").asString()).contains(method);
		assertThat(request.getInputStream().available()).as("the bytes the filter left unread")
			.isEqualTo(INITIALIZE.length());
	}

	@Test
	void doFilter_deleteOnTheStatelessTransport_shouldAnswerMethodNotAllowed() throws Exception {
		// The stateless transport serves without sessions, so its router leaves DELETE
		// out, and the caller would read the same error dispatch as for PUT.
		MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/mcp");
		request.setRequestURI("/mcp");
		request.addHeader("Mcp-Session-Id", "abc");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(405);
		assertThat(this.response.getHeader("Allow")).isEqualTo("GET, POST");
	}

	@Test
	void doFilter_deleteOnTheStatefulTransport_shouldReachTheServer() throws Exception {
		// A stateful session ends with DELETE, so that method is routed there.
		MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/mcp");
		request.setRequestURI("/mcp");
		request.addHeader("Mcp-Session-Id", "abc");

		statefulFilter().doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_putOnTheStatefulTransport_shouldAnswerMethodNotAllowedNamingDelete() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/mcp");
		request.setRequestURI("/mcp");

		statefulFilter().doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(405);
		assertThat(this.response.getHeader("Allow")).isEqualTo("GET, POST, DELETE");
	}

	@Test
	void doFilter_optionsRequest_shouldReachTheChain() throws Exception {
		// A browser sends a preflight ahead of a cross-origin POST, and the CORS filter
		// that answers it sits inside the security chain behind this filter.
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/mcp");
		request.setRequestURI("/mcp");
		request.addHeader("Origin", "https://inspector.example.com");
		request.addHeader("Access-Control-Request-Method", "POST");

		filter(List.of("https://inspector.example.com"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_foreignOriginOnAMethodTheTransportDoesNotRoute_shouldAnswerForbiddenFirst() throws Exception {
		// MCP asks for the Origin check ahead of everything else, the method rule
		// included.
		MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/mcp");
		request.setRequestURI("/mcp");
		request.addHeader("Origin", "https://evil.example.com");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.response.getStatus()).isEqualTo(403);
	}

	@Test
	void doFilter_acceptAnyMediaType_shouldReachTheServerAsBothMediaTypes() throws Exception {
		// The SDK's transports read the header for the two literal media types, so
		// Accept: */* would answer 400 with an empty body although it accepts both.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.addHeader("Accept", "*/*");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(delivered().getHeader("Accept")).isEqualTo("application/json, text/event-stream");
	}

	@Test
	void doFilter_acceptWithQValues_shouldReachTheServerWithoutThem() throws Exception {
		// MimeType.equals compares parameters, so application/json;q=0.9 would miss the
		// SDK's List.contains check.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.addHeader("Accept", "application/json;q=0.9, text/event-stream;q=0.8");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(delivered().getHeader("Accept")).isEqualTo("application/json, text/event-stream");
		assertThat(delivered().getHeaders("Accept").asIterator()).toIterable()
			.containsExactly("application/json, text/event-stream");
	}

	@Test
	void doFilter_acceptAbsent_shouldReachTheServerAsBothMediaTypes() throws Exception {
		// A missing header means the client takes any answer, and the SDK would refuse it
		// as it refuses */*.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(delivered().getHeader("Accept")).isEqualTo("application/json, text/event-stream");
		assertThat(delivered().getHeaderNames().asIterator()).toIterable().anyMatch("Accept"::equalsIgnoreCase);
	}

	@Test
	void doFilter_acceptNamingNeitherMediaType_shouldReachTheServerUnchanged() throws Exception {
		// A client that asks for text/plain alone has said what it accepts, and the
		// SDK's refusal of it stands.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.addHeader("Accept", "text/plain");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(delivered().getHeader("Accept")).isEqualTo("text/plain");
	}

	@Test
	void doFilter_acceptNamingOneMediaType_shouldReachTheServerUnchanged() throws Exception {
		// MCP asks a client to list both, and a client that lists one keeps the SDK's
		// answer to that.
		MockHttpServletRequest request = post("/mcp", INITIALIZE);
		request.addHeader("Accept", "application/json;q=0.5");

		filter(List.of(), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
		assertThat(delivered().getHeader("Accept")).isEqualTo("application/json");
	}

	@Test
	void doFilter_originDifferingInCaseAndATrailingSlash_shouldMatchTheConfiguredEntry() throws Exception {
		// The scheme and the host of an origin are case-insensitive, and a trailing slash
		// is a common way to write one in configuration. Both would miss an exact string
		// comparison.
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader("Origin", "http://localhost:8111");

		filter(List.of("http://LocalHost:8111/"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_originHeaderWithATrailingSlash_shouldMatchTheConfiguredEntry() throws Exception {
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader("Origin", "HTTP://Inspector.Example.com/");

		filter(List.of("http://inspector.example.com"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNotNull();
	}

	@Test
	void doFilter_originDifferingInPort_shouldStayRefused() throws Exception {
		MockHttpServletRequest request = initializePost("/mcp");
		request.addHeader("Origin", "http://localhost:8112");

		filter(List.of("http://localhost:8111"), false).doFilter(request, this.response, this.chain);

		assertThat(this.chain.getRequest()).isNull();
		assertThat(this.response.getStatus()).isEqualTo(403);
	}

	@ParameterizedTest
	@ValueSource(strings = { "localhost:8111", "http://localhost:8111/mcp", "http://user@localhost", "null", "http://",
			"http://localhost:8111?x=1", "http://localhost:8111 " })
	void constructor_allowedOriginThatIsNotASerializedOrigin_shouldStopStartupNamingTheProperty(String entry) {
		// An entry the comparison cannot match would leave a browser client refused
		// with 403 and the operator reading the list for a typo.
		assertThatExceptionOfType(InvalidConfigurationPropertyValueException.class)
			.isThrownBy(() -> filter(List.of(entry), false))
			.withMessageContaining("gatool.mcp.transport.allowed-origins")
			.withMessageContaining(entry.strip());
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://localhost:8111", "https://inspector.example.com/", "HTTP://Example.COM",
			"http://[::1]:6274", "http://127.0.0.1", "chrome-extension://abcdefghijklmnop" })
	void constructor_serializedOrigin_shouldBeAccepted(String entry) {
		assertThat(filter(List.of(entry), false)).isNotNull();
	}

	@Test
	void doFilter_shouldBindTheRequestThreadForTheChainAndRestoreAfter() throws Exception {
		AtomicBoolean boundInsideTheChain = new AtomicBoolean();
		FilterChain recordingChain = (servletRequest, servletResponse) -> boundInsideTheChain
			.set(RequestThread.isBound());

		filter(List.of(), false).doFilter(initializePost("/mcp"), this.response, recordingChain);

		// The tool handler runs inside the chain and refuses a call without the mark, and
		// the thread goes back to the container as it arrived.
		assertThat(boundInsideTheChain).isTrue();
		assertThat(RequestThread.isBound()).isFalse();
	}

	@Test
	void doFilter_chainThatThrowsAnException_shouldStillLeaveTheThreadAsItFoundIt() {
		FilterChain failingChain = (servletRequest, servletResponse) -> {
			throw new IllegalStateException("the servlet failed");
		};

		assertThatIllegalStateException()
			.isThrownBy(() -> filter(List.of(), false).doFilter(initializePost("/mcp"), this.response, failingChain));

		assertThat(RequestThread.isBound()).isFalse();
	}

	// The request the filter handed the chain, with the wrapper it installed.
	private HttpServletRequest delivered() {
		return (HttpServletRequest) this.chain.getRequest();
	}

	private static McpComplianceFilter filter(List<String> allowedOrigins, boolean allowSuperseded) {
		return new McpComplianceFilter("/mcp", allowedOrigins, allowSuperseded, DataSize.ofKilobytes(256), JSON,
				McpComplianceFilter.STATELESS_METHODS);
	}

	// The stateful transport routes DELETE as well, which is the one difference the
	// filter reads between the two.
	private static McpComplianceFilter statefulFilter() {
		return new McpComplianceFilter("/mcp", List.of(), false, DataSize.ofKilobytes(256), JSON,
				McpComplianceFilter.STATEFUL_METHODS);
	}

	private static MockHttpServletRequest initializePost(String path) {
		return post(path, INITIALIZE);
	}

	private static MockHttpServletRequest post(String path, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.setRequestURI(path);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

}
