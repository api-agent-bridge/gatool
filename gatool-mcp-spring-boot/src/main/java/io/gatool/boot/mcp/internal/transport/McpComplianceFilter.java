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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.internal.naming.ToolNameRules;

/**
 * Checks each request to the MCP endpoint against the transport rules of revision
 * 2025-11-25, which Spring AI 2.0.1 leaves partly open.
 *
 * <p>
 * Four rules run here, and one rule of MCP's that this filter leaves open:
 *
 * <ul>
 * <li><b>Origin.</b> A request carrying an {@code Origin} outside
 * {@code gatool.mcp.transport.allowed-origins} gets 403. The list starts empty, so a page
 * in a browser reaches this endpoint only once {@code allowed-origins} names its origin.
 * A request without the header passes, because a command line client leaves it off. Both
 * sides of the comparison are read as a serialized origin, scheme, host and optional
 * port, with the scheme and the host lower-cased and one trailing slash dropped, so
 * {@code http://LocalHost:8111/} in the list matches the {@code http://localhost:8111} a
 * browser sends. An entry that is not a serialized origin stops startup, because the
 * comparison cannot match it.</li>
 * <li><b>Method.</b> Spring AI's stateless router serves GET and POST, and the stateful
 * one DELETE as well. Any other method would reach the router, which answers it through
 * the container's error dispatch: with Spring Security present, the chain there gives a
 * bare 401 without a challenge, which sends an MCP client into authorization discovery
 * for what is a 405, and without it the caller reads Boot's HTML error page. The filter
 * answers such a method itself, with 405, an {@code Allow} header and a JSON-RPC error,
 * before the body is read. OPTIONS passes, because a browser's preflight is answered by
 * the CORS filter inside the security chain behind this one.</li>
 * <li><b>Body size.</b> A body above {@code gatool.mcp.transport.max-request-body-size}
 * gets 413. Spring Boot 4.1.1 leaves a JSON body unbounded, so this property is the only
 * cap the endpoint has.</li>
 * <li><b>Protocol version.</b> A request carrying an {@code MCP-Protocol-Version} outside
 * the accepted revisions gets 400, which the Protocol Version Header section states as a
 * MUST: "If the server receives a request with an invalid or unsupported
 * {@code MCP-Protocol-Version}, it MUST respond with {@code 400 Bad Request}". This
 * filter departs from it in one place, deliberately: an {@code initialize} call whose
 * header is a date later than the newest revision here passes through, and the SDK
 * answers the handshake with a revision it serves. Lifecycle asks a server to answer a
 * version it does not support with one it does, and a client that reaches for the newest
 * revision first would otherwise spend a round trip on a refusal. Everything else is
 * refused, including a value that is not a date: a comparison of raw strings alone would
 * hand such a value the exemption, because every ASCII letter sorts above every digit. A
 * superseded revision stays refused at every point, because the SDK cannot parse the
 * JSON-RPC batches those revisions require. Spring AI's transports accept a wider set and
 * agree to a superseded revision during {@code initialize}, so
 * {@code gatool.mcp.security.unsafe.allow-superseded-mcp-revisions} widens this set to
 * match, for a client pinned to one of them.</li>
 * <li><b>A missing version header passes.</b> MCP writes that rule for backwards
 * compatibility: "if the server does not receive an {@code MCP-Protocol-Version} header,
 * and has no other way to identify the version ... the server SHOULD assume protocol
 * version 2025-03-26". This release serves 2025-11-25 and 2025-06-18, so assuming that
 * revision would mean refusing the call, which is what the rule exists to prevent. The
 * call is served at the revision this server does support.</li>
 * </ul>
 *
 * <p>
 * The rule MCP asks for and this filter leaves open is the cursor. Pagination says
 * "Invalid cursors SHOULD result in an error with code -32602", and a {@code tools/list}
 * carrying any cursor is invalid here, because every answer holds the whole list and
 * leaves {@code nextCursor} out. MCP Java SDK 2.0.0 implements pagination in its schema
 * and in its client and leaves the server half out, so both {@code McpAsyncServer} and
 * {@code McpStatelessAsyncServer} build the result from the whole tool list, leaving the
 * request's cursor unread. A servlet filter is the only seat GATool has for the rule,
 * which would make it an HTTP rule while GATool serves stdio as well. A protocol rule
 * that holds on one transport is worse than the SDK's own behaviour on both. The client
 * reads every tool either way, because one page is the whole list. GATool waits for the
 * SDK here.
 *
 * <p>
 * The version rule reads the JSON-RPC body, and a servlet body reads once, so the filter
 * buffers it through {@link BufferedBodyRequest} and hands that buffer to the servlet.
 * The security chain decides a {@code tools/call} by what this filter read, so a body the
 * filter cannot read the way the MCP server would is refused here. The refused bodies are
 * one declared in a charset other than UTF-8, one that fails to parse, one that is not a
 * JSON object, and a JSON-RPC batch. A {@code tools/call} naming a tool outside printable
 * ASCII is refused as well, because such a name could reach a challenge header.
 *
 * <p>
 * The filter runs ahead of Spring Security, so it buffers and parses a body before any
 * token is checked. That work is bounded by
 * {@code gatool.mcp.transport.max-request-body-size} and by Jackson's own limits on
 * nesting and string length, so an unauthenticated caller costs this server at most one
 * bounded read per request. MCP asks for the Origin check ahead of everything else, which
 * is why the filter sits here and why that check runs before the body is read: a page
 * from a foreign origin costs this server its headers alone.
 *
 * <p>
 * The body rule also refuses what the SDK's own request record refuses in its
 * constructor. One is an {@code id} that is present and is something other than a string
 * or an integer, the other a {@code method} that is present and is not a string. Each
 * would otherwise reach Spring AI's transport and come back as a 500 carrying -32603,
 * where JSON-RPC reserves -32600 for an invalid request. Three shapes that parse and are
 * still invalid are refused the same way: a {@code jsonrpc} member absent or other than
 * the string {@code "2.0"}, which the SDK answers with a 500 or serves; a {@code params}
 * member that is not an object, which the SDK answers with -32603 and its own class name
 * in the message; and, under {@code tools/call}, an {@code arguments} member that is not
 * an object, which fails inside the call. The two {@code tools/call} cases answer -32602
 * inside a 200, the way an unnamed tool does, because the Java client drops its session
 * on a 400.
 *
 * <p>
 * The wrapper the filter installs also rewrites the {@code Accept} header the SDK reads,
 * see {@link BufferedBodyRequest}: q-values go, and a header of {@code *}{@code /*} or an
 * absent header reads as the two media types MCP names.
 *
 * <p>
 * Every refusal is written as a JSON-RPC error, because Spring AI 2.0.1 writes three
 * different shapes on this endpoint, and a client parses one. A request refused on its
 * headers has yet to be parsed, so its error carries a null {@code id}, and the body rule
 * answers with the {@code id} the call sent. {@code McpSchema.JSONRPCResponse} requires a
 * non-null {@code id}, so the body is built as a map.
 *
 * <p>
 * The filter implements {@link Filter} instead of extending {@code HttpFilter}, whose
 * base class is serializable. A serializable filter holding a JSON mapper draws a
 * compiler warning about a field that fails to serialize with it, and this class holds
 * exactly such a field.
 *
 * @author Željko Kozina
 */
public final class McpComplianceFilter implements Filter {

	// The name comes from io.modelcontextprotocol.spec.HttpHeaders, and a servlet
	// container matches a header name whatever case it arrives in.
	static final String PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";

	/**
	 * The request attribute that carries the tool a {@code tools/call} names, read from
	 * the buffered body, so the security chain decides the call without reading the body
	 * again.
	 */
	public static final String TOOL_NAME_ATTRIBUTE = "io.gatool.mcp.toolName";

	/**
	 * The request attribute that carries the JSON-RPC method of every POST this filter
	 * read, empty where the body was unreadable, so the security chain can tell a POST
	 * this filter saw from one that reached it another way.
	 */
	public static final String METHOD_ATTRIBUTE = "io.gatool.mcp.method";

	private static final String TOOLS_CALL = "tools/call";

	/**
	 * The methods Spring AI's stateless router serves, in the order the {@code Allow}
	 * header names them.
	 */
	public static final List<String> STATELESS_METHODS = List.of("GET", "POST");

	/**
	 * The methods the stateful router serves: the stateless two, and DELETE, which ends a
	 * session.
	 */
	public static final List<String> STATEFUL_METHODS = List.of("GET", "POST", "DELETE");

	private static final String ALLOWED_ORIGINS_PROPERTY = "gatool.mcp.transport.allowed-origins";

	// A serialized origin: a scheme, a host or an IPv6 literal, and an optional port. One
	// trailing slash is allowed, because that is how an origin is often written in
	// configuration, and dropped. The scheme and the host are case-insensitive, and the
	// port is kept as written.
	private static final Pattern ORIGIN_PATTERN = Pattern
		.compile("([A-Za-z][A-Za-z0-9+.\\-]*)://([A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?/?");

	// The revisions live in McpRevisions, because the transport hands the same list to
	// the MCP Java SDK. If the two disagreed, the SDK would agree to a revision during
	// initialize that this filter refuses on every request after it.
	private static final String NEWEST_SUPPORTED_REVISION = McpRevisions.newestSupported();

	// JSON-RPC calls this Invalid Request, which is what a refused Origin, a refused
	// revision and an oversized body all are. The HTTP status carries the difference
	// between them.
	private static final int INVALID_REQUEST = -32600;

	private static final int PARSE_ERROR = -32700;

	private static final int INVALID_PARAMS = -32602;

	private static final int UNSUPPORTED_MEDIA_TYPE = 415;

	// 413 Content Too Large. The servlet API names its constant after the earlier
	// wording of that status, so the number carries the meaning here.
	private static final int CONTENT_TOO_LARGE = 413;

	private static final int METHOD_NOT_ALLOWED = 405;

	private static final String INITIALIZE = "initialize";

	// MCP names every revision by its release date, so a value shaped like one is a
	// revision this server has not heard of, and anything else is malformed. The
	// exemption below orders revisions by comparing the strings, and a comparison orders
	// any two strings. Every ASCII letter sorts above every digit, so "garbage", "latest"
	// and "v2" all sort above 2025-11-25 and would take the exemption meant for a future
	// revision. So would "2025-11-250" and a trailing space.
	private static final Pattern REVISION_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

	// Decodes percent escapes and removes path parameters, which is what the servlet
	// container did to the path before it chose a handler for the request.
	private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

	private final String endpoint;

	private final Set<String> allowedOrigins;

	private final Set<String> acceptedRevisions;

	private final int maxRequestBodySize;

	private final JsonMapper jsonMapper;

	private final Set<String> routedMethods;

	// The Allow header names what the router serves, in a fixed order.
	private final String allow;

	/**
	 * Creates the filter.
	 * @param endpoint the path Spring AI serves MCP on, from
	 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}
	 * @param allowedOrigins the origins a browser page may call from, each a serialized
	 * origin
	 * @param allowSupersededRevisions whether the two superseded revisions pass
	 * @param maxRequestBodySize the largest body this endpoint reads
	 * @param jsonMapper reads the JSON-RPC body and writes the error
	 * @param routedMethods the HTTP methods the transport's router serves,
	 * {@link #STATELESS_METHODS} or {@link #STATEFUL_METHODS}
	 * @throws InvalidConfigurationPropertyValueException if an entry of
	 * {@code allowedOrigins} is not a serialized origin
	 */
	public McpComplianceFilter(String endpoint, List<String> allowedOrigins, boolean allowSupersededRevisions,
			DataSize maxRequestBodySize, JsonMapper jsonMapper, List<String> routedMethods) {
		this.endpoint = endpoint;
		this.allowedOrigins = normalizeAllowedOrigins(allowedOrigins);
		this.acceptedRevisions = Set.copyOf(McpRevisions.accepted(allowSupersededRevisions));
		// One body sits in one array, so the cap lives inside an int. A configured
		// value above that takes the largest array the platform builds.
		this.maxRequestBodySize = (int) Math.min(maxRequestBodySize.toBytes(), Integer.MAX_VALUE);
		this.jsonMapper = jsonMapper;
		this.routedMethods = Set.copyOf(routedMethods);
		this.allow = String.join(", ", routedMethods);
	}

	// An entry the comparison cannot match would leave a browser client refused
	// with 403 and the operator reading the list for a typo, so startup stops on it.
	private static Set<String> normalizeAllowedOrigins(List<String> allowedOrigins) {
		Set<String> origins = new LinkedHashSet<>();
		for (String entry : allowedOrigins) {
			String origin = normalizeOrigin(entry);
			if (origin == null) {
				throw new InvalidConfigurationPropertyValueException(ALLOWED_ORIGINS_PROPERTY, entry,
						"An origin is a scheme, a host and an optional port, such as http://localhost:6274, "
								+ "without a path, a query, credentials or whitespace.");
			}
			origins.add(origin);
		}
		return Set.copyOf(origins);
	}

	// Returns the origin in the form both sides are compared in, or null where the value
	// is not a serialized origin.
	private static @Nullable String normalizeOrigin(String value) {
		Matcher matcher = ORIGIN_PATTERN.matcher(value);
		if (!matcher.matches()) {
			return null;
		}
		String port = matcher.group(3);
		return matcher.group(1).toLowerCase(Locale.ROOT) + "://" + matcher.group(2).toLowerCase(Locale.ROOT)
				+ ((port != null) ? port : "");
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest httpRequest)
				|| !(response instanceof HttpServletResponse httpResponse) || !isMcpEndpointRequest(httpRequest)) {
			chain.doFilter(request, response);
			return;
		}
		// The async dispatch that ends a stateful stream carries the request this filter
		// already read, with its attributes, so it passes as it is. This branch leaves
		// the request-thread mark off on purpose: the MCP SDK runs each tool handler on
		// the initial dispatch today, and the async dispatch carries the stream to its
		// end. An SDK that moved message handling into the async dispatch would refuse
		// every stateful call.
		if (httpRequest.getDispatcherType() == DispatcherType.ASYNC
				&& httpRequest.getAttribute(METHOD_ATTRIBUTE) != null) {
			chain.doFilter(request, response);
			return;
		}
		// The Origin rule reads a header, so it runs before the body is read, and a
		// refused page costs this server its headers alone.
		if (refuseForeignOrigin(httpRequest, httpResponse)) {
			return;
		}
		if (refuseUnroutedMethod(httpRequest, httpResponse)) {
			return;
		}
		// A GET opens the response stream and a DELETE ends a session, so a body
		// belongs to a POST alone.
		BufferedBodyRequest body = "POST".equalsIgnoreCase(httpRequest.getMethod())
				? new BufferedBodyRequest(httpRequest, this.maxRequestBodySize) : null;
		JsonRpcBody call = (body != null) ? readCall(body) : JsonRpcBody.UNREAD;
		if (refuse(httpRequest, body, call, httpResponse)) {
			return;
		}
		// The method and the tool name are read here, where the body is already
		// parsed, and left for the security chain behind this filter, which decides a
		// tools/call by the name and refuses a POST without the method attribute.
		if (body != null) {
			httpRequest.setAttribute(METHOD_ATTRIBUTE, call.method());
			if (call.toolName() != null) {
				httpRequest.setAttribute(TOOL_NAME_ATTRIBUTE, call.toolName());
			}
		}
		// The mark travels with the thread for the length of the chain, so the tool
		// handler below can tell the thread that served this request from a thread a pool
		// handed the call to. The earlier value goes back in the finally, so a filter of
		// the application's own that already marked the thread keeps its mark.
		boolean markedAlready = RequestThread.isBound();
		RequestThread.bind();
		try {
			// The servlet reads the buffer, because the container's own stream is spent.
			chain.doFilter((body != null) ? body : httpRequest, httpResponse);
		}
		finally {
			if (!markedAlready) {
				RequestThread.unbind();
			}
		}
	}

	// The filter is registered for every request, so each one is matched against the MCP
	// path here. Registering it for the path alone would tie the registration to a
	// property that an application can change after the bean is built.
	// The path comes from Spring's helper instead of from getRequestURI(), because the
	// container routes on a path it has decoded and stripped of path parameters. Reading
	// the raw URI would let /mcp;x=1 and /%6Dcp reach the MCP server while this filter
	// read them as another endpoint, so every rule below would be skipped for those two
	// spellings.
	private boolean isMcpEndpointRequest(HttpServletRequest request) {
		return this.endpoint.equals(PATH_HELPER.getPathWithinApplication(request));
	}

	// Checks the origin and writes a JSON-RPC error for one outside the list. A true
	// answer means the error is already written.
	private boolean refuseForeignOrigin(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String origin = request.getHeader("Origin");
		if (origin == null) {
			return false;
		}
		// A header that is not a serialized origin, "null" among them, is refused,
		// because every entry is one.
		String normalized = normalizeOrigin(origin);
		if (normalized == null || !this.allowedOrigins.contains(normalized)) {
			writeError(response, HttpServletResponse.SC_FORBIDDEN, INVALID_REQUEST, null, "The origin " + origin
					+ " may not call this MCP endpoint. Name it in " + ALLOWED_ORIGINS_PROPERTY + " to allow it.");
			return true;
		}
		return false;
	}

	// Answers a method the transport's router leaves out with 405, and leaves OPTIONS to
	// the CORS filter behind this one. A true answer means the error is already written.
	private boolean refuseUnroutedMethod(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String method = request.getMethod().toUpperCase(Locale.ROOT);
		if (this.routedMethods.contains(method) || "OPTIONS".equals(method)) {
			return false;
		}
		response.setHeader("Allow", this.allow);
		writeError(response, METHOD_NOT_ALLOWED, INVALID_REQUEST, null,
				"This MCP endpoint serves " + this.allow + ", and the request used " + method + ".");
		return true;
	}

	// Checks the body size, the body and the protocol version, in that order, and writes
	// a JSON-RPC error for the first rule the request breaks. A true answer means the
	// error is already written.
	private boolean refuse(HttpServletRequest request, @Nullable BufferedBodyRequest body, JsonRpcBody call,
			HttpServletResponse response) throws IOException {
		if (body != null && (refuseUnreadableBody(body, call, response) || refuseMalformedMessage(call, response))) {
			return true;
		}
		return refuseUnservedRevision(request, call, response);
	}

	// Writes a JSON-RPC error for a body the filter could not read as one JSON-RPC
	// message object. A true answer means the error is already written.
	private boolean refuseUnreadableBody(BufferedBodyRequest body, JsonRpcBody call, HttpServletResponse response)
			throws IOException {
		if (body.isTooLarge()) {
			writeError(response, CONTENT_TOO_LARGE, INVALID_REQUEST, null,
					"This MCP endpoint reads a request body of up to " + this.maxRequestBodySize
							+ " bytes, and this request sent more. gatool.mcp.transport.max-request-body-size"
							+ " sets that limit.");
			return true;
		}
		// What this filter cannot read, the MCP server must not read either, because the
		// security chain decides a tools/call by what this filter read. A body in another
		// charset, a body that fails to parse, and a batch are refused here.
		if (!body.isUtf8()) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, null,
					"This MCP endpoint reads UTF-8 JSON, and the request's Content-Type, " + body.getContentType()
							+ ", names another charset or cannot be read.");
			return true;
		}
		if (call.shape() == Shape.INVALID) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, PARSE_ERROR, null,
					"The request body is not valid JSON.");
			return true;
		}
		if (call.shape() == Shape.SCALAR) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, null,
					"The request body is valid JSON and is not a JSON-RPC message object.");
			return true;
		}
		if (call.shape() == Shape.ARRAY) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, null,
					"This MCP endpoint reads one JSON-RPC message per request, and a batch arrived. MCP Java SDK "
							+ "2.0.0 reads single messages alone, and the current revisions send one message at "
							+ "a time.");
			return true;
		}
		if (!body.isJson()) {
			writeError(response, UNSUPPORTED_MEDIA_TYPE, INVALID_REQUEST, null,
					"This MCP endpoint reads application/json, and the request's Content-Type is "
							+ body.getContentType() + ".");
			return true;
		}
		return false;
	}

	// Writes a JSON-RPC error for a message object that breaks a JSON-RPC or MCP rule. A
	// true answer means the error is already written.
	private boolean refuseMalformedMessage(JsonRpcBody call, HttpServletResponse response) throws IOException {
		// JSON-RPC asks the error's id to be null where the request's id could not be
		// read, and an id of the wrong type is one that could not be read.
		if (call.shape() == Shape.INVALID_ID) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, null,
					"A JSON-RPC id is a string or an integer, and this request's id is something else.");
			return true;
		}
		if (call.shape() == Shape.WRONG_JSONRPC_VERSION) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, call.id(),
					"A JSON-RPC message carries the member jsonrpc with the string \"2.0\", and this request "
							+ "leaves it out or gives it another value.");
			return true;
		}
		if (call.shape() == Shape.METHOD_NOT_A_STRING) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, call.id(),
					"A JSON-RPC method is a string.");
			return true;
		}
		if (call.shape() == Shape.PARAMS_NOT_AN_OBJECT) {
			// Under tools/call the status and the code are those of an unnamed tool, so
			// the Java client keeps its session.
			boolean toolCall = TOOLS_CALL.equals(call.method());
			writeError(response, toolCall ? HttpServletResponse.SC_OK : HttpServletResponse.SC_BAD_REQUEST,
					toolCall ? INVALID_PARAMS : INVALID_REQUEST, call.id(), "MCP passes the params of " + call.method()
							+ " as an object, and this request's params is " + "something else.");
			return true;
		}
		// The Java client drops its session on a 400 to a request that carried a session
		// id. The SDK's own answer to an unknown tool is an error inside a 200, so a
		// refused name is answered the same way.
		if (call.shape() == Shape.UNNAMED_TOOL) {
			writeError(response, HttpServletResponse.SC_OK, INVALID_PARAMS, call.id(),
					"tools/call lacks a tool name: params.name has to be a string.");
			return true;
		}
		if (call.shape() == Shape.UNNAMEABLE_TOOL) {
			writeError(response, HttpServletResponse.SC_OK, INVALID_PARAMS, call.id(),
					"The tool name has to be printable ASCII without a space, of at most 256 characters, so "
							+ "that a challenge can carry it.");
			return true;
		}
		if (call.shape() == Shape.ARGUMENTS_NOT_AN_OBJECT) {
			writeError(response, HttpServletResponse.SC_OK, INVALID_PARAMS, call.id(),
					"tools/call passes the tool's arguments as an object under params.arguments, and this "
							+ "request's arguments is something else.");
			return true;
		}
		return false;
	}

	// Writes a JSON-RPC error for a request naming a revision this server does not serve.
	// A true answer means the error is already written.
	private boolean refuseUnservedRevision(HttpServletRequest request, JsonRpcBody call, HttpServletResponse response)
			throws IOException {
		// The body is read here, because the revision rule below tells an initialize call
		// from the rest, and the error it writes answers the call that sent it.
		String revision = request.getHeader(PROTOCOL_VERSION_HEADER);
		if (revision != null && !this.acceptedRevisions.contains(revision)
				&& !isInitializeForANewerRevision(revision, call)) {
			writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID_REQUEST, call.id(),
					"This server serves MCP revisions "
							+ String.join(" and ", this.acceptedRevisions.stream().sorted().toList())
							+ ", and the request asked for " + revision + ".");
			return true;
		}
		return false;
	}

	// Returns whether an initialize call asking for a revision newer than this server
	// serves may pass through to the SDK.
	// Such a handshake belongs to a client that can step down, so it reaches the SDK,
	// which answers with a revision it serves.
	// The value has to read as a date first, and then a string comparison orders it: a
	// superseded revision sorts below the newest one here and stays refused. A GET stays
	// refused as well, because a method arrives inside a body.
	private static boolean isInitializeForANewerRevision(String revision, JsonRpcBody call) {
		return INITIALIZE.equals(call.method()) && REVISION_DATE_PATTERN.matcher(revision).matches()
				&& revision.compareTo(NEWEST_SUPPORTED_REVISION) > 0;
	}

	// JSON-RPC tells a parse error from an invalid request: text that is not JSON reads
	// -32700, and valid JSON that is not one message object reads -32600.
	private JsonRpcBody readCall(BufferedBodyRequest body) {
		String bodyText = body.bodyAsText();
		if (bodyText.isBlank()) {
			return JsonRpcBody.INVALID;
		}
		JsonNode message;
		try {
			message = this.jsonMapper.readTree(bodyText);
		}
		catch (JacksonException ex) {
			return JsonRpcBody.INVALID;
		}
		if (message.isArray()) {
			return JsonRpcBody.BATCH;
		}
		if (!message.isObject()) {
			return JsonRpcBody.SCALAR;
		}
		// MCP requires a request id that is a string or an integer, and the SDK's request
		// record asserts that in its constructor. A fraction, an object, an array, a
		// boolean or an explicit null would fail inside the transport as a 500. An
		// integer beyond a long fails there too, because the SDK accepts Integer and
		// Long.
		if (!isReadableId(message.path("id"))) {
			return JsonRpcBody.INVALID_ID;
		}
		Object id = idOf(message);
		// JSON-RPC 2.0 makes the member the string "2.0" exactly. The SDK's request
		// record asserts that the member has text and reads any text as the version, so a
		// message without it would fail as a 500 and "1.0" would be served.
		JsonNode versionNode = message.path("jsonrpc");
		if (!versionNode.isString() || !"2.0".equals(versionNode.asString())) {
			return new JsonRpcBody("", id, null, Shape.WRONG_JSONRPC_VERSION);
		}
		JsonNode methodNode = message.path("method");
		// JSON-RPC makes the method a string. The SDK would coerce a number into one and
		// refuse an explicit null inside the transport, so both are refused ahead of it.
		// An absent method is a response a client posts, which travels on to the server
		// for an answer of its own.
		if (!methodNode.isMissingNode() && !methodNode.isString()) {
			return new JsonRpcBody("", id, null, Shape.METHOD_NOT_A_STRING);
		}
		String method = stringAt(message, "method");
		// JSON-RPC allows an object or an array here, and MCP narrows every method's
		// params to an object. The SDK converts whatever arrives into its request record
		// and answers a string with -32603 and its own class name in the message. A
		// response a client posts carries a result and lacks a method, so the rule reads
		// requests and notifications alone.
		JsonNode paramsNode = message.path("params");
		if (!method.isEmpty() && !paramsNode.isMissingNode() && !paramsNode.isObject()) {
			return new JsonRpcBody(method, id, null, Shape.PARAMS_NOT_AN_OBJECT);
		}
		if (!TOOLS_CALL.equals(method)) {
			return new JsonRpcBody(method, id, null, Shape.OBJECT);
		}
		return readToolCall(paramsNode, method, id);
	}

	// The tool name and the arguments of a tools/call, read from its params.
	private static JsonRpcBody readToolCall(JsonNode paramsNode, String method, @Nullable Object id) {
		// A tool name reaches the security chain and, from there, a challenge header
		// and a log line, so a name outside printable ASCII is refused ahead of both.
		// A registered tool cannot carry such a name, because startup stops on one.
		JsonNode nameNode = paramsNode.path("name");
		if (!nameNode.isString() || nameNode.asString().isEmpty()) {
			return new JsonRpcBody(method, id, null, Shape.UNNAMED_TOOL);
		}
		String toolName = nameNode.asString();
		if (!ToolNameRules.fitsInHeader(toolName)) {
			return new JsonRpcBody(method, id, null, Shape.UNNAMEABLE_TOOL);
		}
		// The SDK reads the arguments into a map inside the call, so a string there would
		// fail as a 500 on the stateless transport and escape the SSE consumer on the
		// stateful one. An absent member is a call without arguments.
		JsonNode argumentsNode = paramsNode.path("arguments");
		if (!argumentsNode.isMissingNode() && !argumentsNode.isObject()) {
			return new JsonRpcBody(method, id, null, Shape.ARGUMENTS_NOT_AN_OBJECT);
		}
		return new JsonRpcBody(method, id, toolName, Shape.OBJECT);
	}

	// A field of another JSON type reads as absent, so a message whose method is an
	// object travels on to the MCP server, which answers it with an error of its own.
	private static String stringAt(JsonNode message, String field) {
		JsonNode node = message.path(field);
		return node.isString() ? node.asString() : "";
	}

	// An absent id marks a notification, which JSON-RPC exempts from the reply rule. A
	// present one is a string or an integer that fits a long, which is what the SDK's
	// request record accepts.
	private static boolean isReadableId(JsonNode id) {
		return id.isMissingNode() || id.isString() || (id.isIntegralNumber() && id.canConvertToLong());
	}

	private static @Nullable Object idOf(JsonNode message) {
		JsonNode id = message.path("id");
		// JSON-RPC allows a string or a number, and a client matches the answer against
		// the one it sent, so each keeps its own JSON type here.
		if (id.isNumber()) {
			return id.numberValue();
		}
		return id.isString() ? id.asString() : null;
	}

	private void writeError(HttpServletResponse response, int status, int code, @Nullable Object id, String message)
			throws IOException {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message);
		Map<String, @Nullable Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("id", id);
		body.put("error", error);
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(this.jsonMapper.writeValueAsString(body));
	}

	/**
	 * What the filter found in a body.
	 */
	private enum Shape {

		NONE, OBJECT, ARRAY, SCALAR, INVALID, INVALID_ID, WRONG_JSONRPC_VERSION, METHOD_NOT_A_STRING,
		PARAMS_NOT_AN_OBJECT, UNNAMED_TOOL, UNNAMEABLE_TOOL, ARGUMENTS_NOT_AN_OBJECT

	}

	/**
	 * What the filter reads out of one JSON-RPC body.
	 *
	 * @param method the method the call names, which is empty where the body fails to
	 * parse or leaves the method out
	 * @param id the JSON-RPC id, so an error answers the call it refuses
	 * @param toolName the tool a {@code tools/call} names, or {@code null} for every
	 * other call
	 * @param shape what the body was, as one of the values of {@link Shape}: a readable
	 * message object, or the rule it broke
	 */
	private record JsonRpcBody(String method, @Nullable Object id, @Nullable String toolName, Shape shape) {

		static final JsonRpcBody INVALID = new JsonRpcBody("", null, null, Shape.INVALID);

		static final JsonRpcBody BATCH = new JsonRpcBody("", null, null, Shape.ARRAY);

		static final JsonRpcBody SCALAR = new JsonRpcBody("", null, null, Shape.SCALAR);

		// The id is what could not be read, so the error answers with a null one.
		static final JsonRpcBody INVALID_ID = new JsonRpcBody("", null, null, Shape.INVALID_ID);

		// A request without a body, a GET or a DELETE, meets the header rules alone.
		static final JsonRpcBody UNREAD = new JsonRpcBody("", null, null, Shape.NONE);

	}

}
