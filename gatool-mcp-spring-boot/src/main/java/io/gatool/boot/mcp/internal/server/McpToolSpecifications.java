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

package io.gatool.boot.mcp.internal.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import io.micrometer.observation.Observation;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.mcp.internal.transport.RequestThread;
import io.gatool.boot.mcp.limit.RateLimitDecision;
import io.gatool.core.internal.schema.JsonSchemaKeywords;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

/**
 * Turns the tool model into MCP Java SDK types.
 *
 * <p>
 * One method builds the {@link McpSchema.Tool} record and one builds the stateless
 * specification around it, so the stateful list that stdio runs on is a second mapper
 * over the same tool. The SDK reuses the record as it is built, so the title, the
 * annotations and the output schema of later releases reach {@code tools/list} unchanged.
 *
 * <p>
 * The input schema arrives as text, because graphql-java and Spring for GraphQL lack a
 * JSON Schema class and Spring AI's generator works from a Java method or type. The text
 * is read into the map the SDK's record holds with a reader that keeps every digit of a
 * number, so a decimal default on a custom scalar reaches {@code tools/list} as GATool
 * wrote it. Each schema also gains a {@code $id} built from the SHA-256 of its text,
 * which is the key the SDK's validator keeps the compiled schema under.
 *
 * <p>
 * A {@link ToolCallFailedException} becomes a result with {@code isError} true carrying
 * GATool's own message, which is how MCP answers a failed call. Every other ending of a
 * call arrives the same way, because the catch covers {@code Throwable}: a client parses
 * one shape whatever happened. The one exception is a {@code VirtualMachineError} other
 * than out-of-memory and stack overflow, which says the JVM is corrupted and travels on
 * so the server stops serving.
 *
 * @author Željko Kozina
 */
public final class McpToolSpecifications {

	private static final Log logger = LogFactory.getLog(McpToolSpecifications.class);

	private static final String FAILED = " failed";

	// GATool gives the observation its own name. An observability backend keeps the
	// series it receives, so renaming this later breaks the dashboards built on it.
	private static final String OBSERVATION_NAME = "gatool.call";

	private static final String OUTCOME_TAG = "gatool.call.outcome";

	private static final String TOOL_ERROR_OUTCOME = "tool-error";

	private static final String OFF_REQUEST_THREAD_OUTCOME = "off-request-thread";

	// One line a minute for each tool, the window ToolCallRunner uses for a failing API.
	private static final long REFUSAL_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

	// The throttle of each tool's refusal lines. The map holds one entry per tool that
	// was refused, so it is bounded by the tool catalogue.
	private static final Map<String, RefusalWindow> REFUSAL_WINDOWS = new ConcurrentHashMap<>();

	private static final String TOOL_PREFIX = "The tool ";

	// Reading the one field an image operation selects, which is all this mapper does.
	private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

	// The map the SDK's Tool record holds, in the order the schema writer put the
	// keywords.
	private static final TypeReference<LinkedHashMap<String, Object>> SCHEMA_TYPE = new TypeReference<>() {
	};

	// The keyword a JSON Schema names itself with. It is declared here and stays out of
	// the keywords the two schema writers share, because this class alone writes it.
	private static final String ID_KEYWORD = "$id";

	// A URN, because the id names the text, where a URL would promise an address that
	// serves it. The algorithm is part of the name, so a later change of digest
	// publishes ids that cannot be mistaken for these.
	private static final String ID_PREFIX = "urn:gatool:schema:sha256:";

	// A SHA-256 digest is 32 bytes, and each byte is two hexadecimal digits.
	private static final int SHA_256_HEX_DIGITS = 64;

	/**
	 * The characters {@code tools/list} carries for each schema beyond the text its
	 * writer produced: the {@code $id} keyword, its value and the comma after it, 98 in
	 * all.
	 *
	 * <p>
	 * The startup listing and the warning about a large tool count these for every schema
	 * an MCP tool publishes, so their estimate covers what an MCP client reads.
	 */
	// Counted from the two constants the keyword is written from, so a change to the
	// prefix or the keyword reaches the estimate with it.
	public static final int CHARACTERS_ADDED_TO_EACH_SCHEMA = ("\"" + ID_KEYWORD + "\":\"" + ID_PREFIX + "\",").length()
			+ SHA_256_HEX_DIGITS;

	private McpToolSpecifications() {
	}

	/**
	 * Builds the specification of one tool.
	 * @param tool the tool to serve
	 * @param jsonMapper the mapper the MCP server transports write with, whose reader
	 * turns the schema text into the record's map
	 * @param policy the settings every call of the tool runs under
	 * @return the specification that Spring AI hands to the MCP server
	 */
	public static McpStatelessServerFeatures.SyncToolSpecification specification(GATool tool, JsonMapper jsonMapper,
			McpCallSettings policy) {
		return new McpStatelessServerFeatures.SyncToolSpecification(buildTool(tool, jsonMapper),
				(context, request) -> observeCall(tool, copyArguments(request), policy));
	}

	/**
	 * Builds the stateful specification of one tool, which stdio and stateful Streamable
	 * HTTP both run on.
	 *
	 * <p>
	 * The two specifications differ in the first argument their handler takes, which is
	 * the transport context for a stateless server and the server exchange for a stateful
	 * one. Both handlers reach the same tool, so a call answers the same way on either
	 * transport.
	 * @param tool the tool to serve
	 * @param jsonMapper the mapper the MCP server transports write with, whose reader
	 * turns the schema text into the record's map
	 * @param policy the settings every call of the tool runs under
	 * @return the specification that Spring AI hands to the MCP server
	 */
	public static McpServerFeatures.SyncToolSpecification statefulSpecification(GATool tool, JsonMapper jsonMapper,
			McpCallSettings policy) {
		return new McpServerFeatures.SyncToolSpecification(buildTool(tool, jsonMapper),
				(exchange, request) -> observeCall(tool, copyArguments(request), policy));
	}

	// Calls the tool inside one observation and returns the result of the call.
	// GATool records one observation around the whole call, so the duration covers
	// the rate limit check as well as the GraphQL request. An application without an
	// ObservationRegistry bean gets ObservationRegistry.NOOP, where every call below
	// costs a few field reads.
	private static McpSchema.CallToolResult observeCall(GATool tool, Map<String, @Nullable Object> arguments,
			McpCallSettings policy) {
		Observation observation = Observation.createNotStarted(OBSERVATION_NAME, policy.observationRegistry())
			.lowCardinalityKeyValue("gatool.call.name", tool.name())
			.lowCardinalityKeyValue("gatool.operation.type", tool.readOnly() ? "query" : "mutation");
		if (policy.includeContent()) {
			observation.highCardinalityKeyValue("gatool.call.arguments", String.valueOf(arguments));
		}
		// The result is held here instead of returned from observe(...), so that an
		// ObservationHandler failing on stop leaves the answer the model was going to
		// read. Telemetry belongs around a tool call and out of the path its answer
		// takes.
		AtomicReference<McpSchema.CallToolResult> answer = new AtomicReference<>();
		// Set as the call begins, so the call outside the observation below runs only
		// where the tool has not been reached.
		AtomicBoolean began = new AtomicBoolean();
		try {
			// observe(...) opens a scope around the call, so the observation of the
			// outbound GraphQL request finds this one as its parent. Starting and
			// stopping the observation without a scope would leave the tool call and its
			// HTTP call in two unrelated traces.
			observation.observe(() -> {
				began.set(true);
				answer.set(guardCall(tool, arguments, policy, observation));
			});
		}
		catch (Throwable failure) {
			// guardCall answers every ending of the call except the one it rethrows, so a
			// failure arriving here is that one, or came from the observation, which an
			// application's own registry owns.
			if (isFatal(failure)) {
				throw failure;
			}
			logger.error(
					"The observation of tool " + tool.name() + FAILED + whenItFailed(answer.get() != null, began.get()),
					failure);
		}
		McpSchema.CallToolResult result = answer.get();
		if (result != null) {
			return result;
		}
		if (began.get()) {
			// The call began and ended without an answer, so the API may hold a write
			// already. Running it again here would send a mutation twice inside one
			// tools/call, so the model reads that the call did not complete.
			return buildFailedResult(tool);
		}
		// A handler that failed on start left observe(...) before the call ran, so the
		// call runs here, outside that observation. Answering a tool error instead would
		// let a metrics backend that is unreachable skip every tool call, where telemetry
		// sits around a call and stays out of its answer. NOOP stands in for the failed
		// observation, because its handlers are what just failed.
		return guardCall(tool, arguments, policy, Observation.NOOP);
	}

	// What the log says about the call beside a failed observation.
	private static String whenItFailed(boolean answered, boolean began) {
		if (answered) {
			return ", and the call was unaffected";
		}
		return began ? " while the call ran" : " before the call ran";
	}

	// Calls the tool within the rate limit and turns every failure into a result carrying
	// a tool error. Every ending of a tool call is a CallToolResult, including the ones
	// nobody plans for. An Error, such as the OutOfMemoryError a large response can
	// raise, is not a RuntimeException, so uncaught it would travel to the servlet. The
	// client would then read Spring Boot's own 500 body, without a jsonrpc member, an id
	// or an error object, which is a protocol failure where a tool error is meant. The
	// rate limiter runs inside this guard as well, because an application can replace it
	// with a ToolRateLimiter bean of its own, and that implementation can throw anything.
	// The one failure this guard lets through is a VirtualMachineError other than those
	// two. An InternalError or an UnknownError says the JVM itself is corrupted, and a
	// server that keeps answering from a corrupted JVM answers wrongly, so it stops
	// serving.
	private static McpSchema.CallToolResult guardCall(GATool tool, Map<String, @Nullable Object> arguments,
			McpCallSettings policy, Observation observation) {
		try {
			// First, ahead of the scope check, the rate limiter and the tool. GATool
			// reads the caller from the thread the call runs on, and a thread the
			// compliance filter left unmarked carries whichever caller it kept from an
			// earlier request, so the caller GATool would read here cannot be trusted.
			if (policy.requiresTheRequestThread() && !RequestThread.isBound()) {
				return refuseOffTheRequestThread(tool, observation);
			}
			return callWithinScopesAndLimit(tool, arguments, policy, observation);
		}
		catch (Throwable failure) {
			if (isFatal(failure)) {
				throw failure;
			}
			logger.error(TOOL_PREFIX + tool.name() + FAILED, failure);
			recordError(tool, observation, failure);
			return buildFailedResult(tool);
		}
	}

	// Records the failure of a call on its observation, and keeps the answer of the call
	// when a handler throws while it reads that failure.
	// Observation.error(...) runs every handler's onError on this thread, and an
	// application's own handler can throw there, for example one that reports to a
	// service that is down. Thrown from inside the catch block that is building the
	// answer, it would take the answer with it: the model would lose the sentence of the
	// failure, which for a write says what happened to the request.
	private static void recordError(GATool tool, Observation observation, Throwable failure) {
		try {
			observation.lowCardinalityKeyValue(OUTCOME_TAG, TOOL_ERROR_OUTCOME).error(failure);
		}
		catch (Throwable handlerFailure) {
			if (isFatal(handlerFailure)) {
				throw handlerFailure;
			}
			logger.error(
					"The observation of tool " + tool.name()
							+ " failed while it recorded the failure of the call, and the answer was unaffected",
					handlerFailure);
		}
	}

	// Answers a call that arrived off the request thread with a tool error, and tells the
	// operator once a minute for each tool.
	// The model reads that the call was refused for the shape of this deployment, so an
	// agent stops re-sending the same call with other arguments. The operator reads the
	// thread the call ran on and the two ways a call leaves the request thread. The line
	// is throttled the way ToolCallRunner throttles a failing API, because a client in a
	// loop would otherwise write one line per call.
	private static McpSchema.CallToolResult refuseOffTheRequestThread(GATool tool, Observation observation) {
		observation.lowCardinalityKeyValue(OUTCOME_TAG, OFF_REQUEST_THREAD_OUTCOME);
		String leftOut = REFUSAL_WINDOWS.computeIfAbsent(tool.name(), (name) -> new RefusalWindow())
			.admit(System.nanoTime());
		if (leftOut != null) {
			logger.error("GATool refused a call to " + tool.name() + ", because it ran on thread "
					+ Thread.currentThread().getName() + ". GATool reads the caller on the request thread, and a "
					+ "call leaves that thread where the MCP server was built without immediateExecution(true) or "
					+ "where a transport of the application's own hands the call to another thread. Keep "
					+ "immediateExecution(true) in the application's McpSyncServerCustomizer bean." + leftOut);
		}
		return McpSchema.CallToolResult.builder()
			.addTextContent(TOOL_PREFIX + tool.name() + " was not called. This server ran the call on a thread "
					+ "that does not carry the caller, so GATool cannot tell who is calling. The arguments are not "
					+ "the cause, and the same call gets the same answer until the operator of this server corrects "
					+ "its configuration.")
			.isError(true)
			.build();
	}

	// Whether a failure says the JVM is corrupted, which is every VirtualMachineError
	// except the two a tool call raises on its own: OutOfMemoryError for a response too
	// large to hold, and StackOverflowError for a document nested too deep.
	private static boolean isFatal(Throwable failure) {
		return failure instanceof VirtualMachineError && !(failure instanceof OutOfMemoryError)
				&& !(failure instanceof StackOverflowError);
	}

	// What a model reads for a failure this class could not turn into a tool error. A
	// write gets the sentence ToolCallRunner uses for the same case: the failure may have
	// come after the API applied it, and a model told to try again would apply it twice.
	private static McpSchema.CallToolResult buildFailedResult(GATool tool) {
		String whatToDoNext = tool.readOnly() ? "A later call may succeed."
				: "This tool writes, and a failure partway cannot be told from one before the call ran, so "
						+ "read the current state before you send it again.";
		return McpSchema.CallToolResult.builder()
			.addTextContent(TOOL_PREFIX + tool.name() + " failed, and the call did not complete. " + whatToDoNext)
			.isError(true)
			.build();
	}

	// Calls the tool where the rate limit allows the call, and answers with a tool error
	// naming the wait where the limit refuses it.
	// A call over the limit gets a tool error naming the tool, the limit and the wait, so
	// an agent in a loop learns how long to pause. The check runs ahead of the operation,
	// so a refused call stops before the GraphQL API, and the call below sets the outcome
	// tag for every other ending.
	private static McpSchema.CallToolResult callWithinScopesAndLimit(GATool tool,
			Map<String, @Nullable Object> arguments, McpCallSettings policy, Observation observation) {
		// Over stdio the caller is the process that started the server, and the scopes
		// it holds come from the environment. The check that a 403 makes over HTTP is
		// made here, ahead of the rate limit, and answers as a tool error.
		Set<String> granted = policy.grantedScopes();
		List<String> missing = missingScopes(tool, granted);
		if (granted != null && !missing.isEmpty()) {
			observation.lowCardinalityKeyValue(OUTCOME_TAG, "insufficient-scope");
			return McpSchema.CallToolResult.builder()
				.addTextContent(TOOL_PREFIX + tool.name() + " needs the scopes " + String.join(" ", missing)
						+ ", and this server was started "
						+ (granted.isEmpty() ? "without scopes" : "with " + String.join(" ", granted))
						+ " (gatool.mcp.stdio.granted-scopes).")
				.isError(true)
				.build();
		}
		RateLimitDecision decision = policy.rateLimiter().decide(policy.caller().get(), tool.name());
		if (!decision.allowed()) {
			observation.lowCardinalityKeyValue(OUTCOME_TAG, "rate-limited");
			// The wait rounds up, because a client that waits the truncated value arrives
			// before the limiter holds a token for it. A truncated wait of 1.9 seconds
			// would read as 1 second.
			long retryAfterSeconds = Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000);
			String message = "Rate limit reached for " + tool.name() + ": " + decision.limit() + ". Retry after "
					+ retryAfterSeconds + " seconds.";
			return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
		}
		McpSchema.CallToolResult result = call(tool, arguments, observation,
				policy.toolResponseMimeTypes().get(tool.name()));
		if (policy.includeContent()) {
			observation.highCardinalityKeyValue("gatool.call.result", textOf(result));
		}
		return result;
	}

	private static List<String> missingScopes(GATool tool, @Nullable Set<String> granted) {
		List<String> scopes = tool.scopes();
		if (granted == null || scopes == null) {
			return List.of();
		}
		return scopes.stream().filter((scope) -> !granted.contains(scope)).toList();
	}

	private static String textOf(McpSchema.CallToolResult result) {
		return result.content()
			.stream()
			.filter(McpSchema.TextContent.class::isInstance)
			.map((content) -> ((McpSchema.TextContent) content).text())
			.collect(Collectors.joining());
	}

	// Package-private, so that the test covers both answers without building an MCP
	// transport context.
	static McpSchema.CallToolResult call(GATool tool, Map<String, @Nullable Object> arguments) {
		return call(tool, arguments, Observation.NOOP, null);
	}

	// Calls the tool and turns the outcome, or a failure of the call, into an MCP result.
	// The outcome tag is set here, because this is where the answers part: the kind the
	// outcome carries, and a transport failure that leaves the executor as
	// ToolCallFailedException. Deciding it from the finished result instead would read
	// every error as one, since a refusal and a GraphQL error both arrive with isError
	// true.
	private static McpSchema.CallToolResult call(GATool tool, Map<String, @Nullable Object> arguments,
			Observation observation, @Nullable String mimeType) {
		try {
			ToolCallOutcome outcome = tool.call(arguments);
			observation.lowCardinalityKeyValue(OUTCOME_TAG, outcome.kind().label());
			return buildResult(tool.name(), outcome, mimeType);
		}
		catch (ToolCallFailedException ex) {
			recordError(tool, observation, ex);
			return McpSchema.CallToolResult.builder().addTextContent(ex.getMessage()).isError(true).build();
		}
		// Every other runtime failure becomes a tool error as well. Letting one travel on
		// reaches the client as a JSON-RPC internal error carrying that exception's own
		// message, which is written for a developer reading a log. The log keeps the
		// cause, and the model reads a sentence it can act on.
		catch (RuntimeException ex) {
			recordError(tool, observation, ex);
			logger.warn(TOOL_PREFIX + tool.name() + FAILED, ex);
			return buildFailedResult(tool);
		}
	}

	/**
	 * Whether a response mime type names an image.
	 *
	 * <p>
	 * Reads the parsed type, the way Spring AI's own converter reads this property.
	 * Testing the raw string with {@code startsWith("image")} would also match
	 * {@code imagex/thing}, which would turn a JSON result into an image block whose data
	 * fails to decode.
	 * @param mimeType the configured value, trimmed
	 * @return whether the value parses and names the image type
	 */
	private static boolean isImage(String mimeType) {
		try {
			return "image".equals(MimeType.valueOf(mimeType).getType());
		}
		catch (InvalidMimeTypeException ex) {
			// Startup refuses an unparseable value, so reaching here means the map was
			// built another way. Text is the answer that loses the least.
			return false;
		}
	}

	// Returns the result for one tool outcome, as image content where the tool is
	// configured to answer with an image, and as text otherwise.
	// spring.ai.mcp.server.tool-response-mime-type names the media type a tool answers
	// with, and Spring AI's own converter reads it for an application's own tools. It
	// acts on an image type alone: the result text becomes the data of an image content
	// addressed to the assistant, and every other value answers as text. A tool built
	// from an operation file answers with the JSON of a GraphQL response, so an image
	// type set here says that this operation returns image data.
	private static McpSchema.CallToolResult buildResult(String toolName, ToolCallOutcome outcome,
			@Nullable String mimeType) {
		String text = outcome.text();
		boolean error = outcome.isError();
		// Trimmed, the way startup parses the value and the way the check above reads it.
		// The raw value would carry a trailing space in the property to the client inside
		// mimeType.
		String imageType = (mimeType != null) ? mimeType.trim() : null;
		if (!error && imageType != null && isImage(imageType)) {
			String imageData = readImageData(toolName, text);
			if (imageData != null) {
				McpSchema.Annotations annotations = McpSchema.Annotations.builder()
					.audience(List.of(McpSchema.Role.ASSISTANT))
					.build();
				return McpSchema.CallToolResult.builder()
					.addContent(McpSchema.ImageContent.builder(imageData, imageType).annotations(annotations).build())
					.isError(false)
					.build();
			}
		}
		McpSchema.CallToolResult.Builder result = McpSchema.CallToolResult.builder()
			.addTextContent(text)
			.isError(error);
		// MCP asks a server returning structured content to send the same JSON as text,
		// which the block above already is. A refusal of GATool's own, such as a result
		// above the size limit, carries a sentence instead of the envelope, and arrives
		// here without structured content, so it goes back as text alone and nothing
		// claims to conform.
		//
		// An API that answers with data and errors together gives an envelope that
		// conforms all the same, and MCP asks a server publishing an output schema for
		// structured results that conform to it. The error flag says the response carries
		// errors, and this says what came back beside them. A check that read the flag as
		// well would drop the structured half for exactly those calls and leave a client
		// re-parsing the text this field exists to replace.
		@Nullable Map<String, @Nullable Object> structured = outcome.structuredContent();
		if (structured != null) {
			result.structuredContent(structured);
		}
		return result.build();
	}

	// Returns the base64 image data of the one field the operation selects, or null
	// where the response holds something else.
	// A hand-written tool that answers with an image returns the base64 data itself, and
	// a tool built from an operation file returns a GraphQL envelope, so the one selected
	// field inside data carries the image. Passing the envelope through would produce
	// content whose data begins with {"data": and fails to decode.
	private static @Nullable String readImageData(String toolName, String text) {
		String field = readSingleDataField(text);
		if (field == null) {
			logger.warn(TOOL_PREFIX + toolName + " is configured to answer with an image through "
					+ "spring.ai.mcp.server.tool-response-mime-type, and its operation selects more than one field, "
					+ "so GATool answered with the GraphQL response as text. Select the one field that holds the "
					+ "image data.");
			return null;
		}
		try {
			Base64.getDecoder().decode(field);
			return field;
		}
		catch (IllegalArgumentException ex) {
			logger.warn(TOOL_PREFIX + toolName + " is configured to answer with an image through "
					+ "spring.ai.mcp.server.tool-response-mime-type, and the field it selects holds something other "
					+ "than base64 data, so GATool answered with the GraphQL response as text.");
			return null;
		}
	}

	// Returns the string value of the one field inside data, or null where data holds
	// anything else.
	private static @Nullable String readSingleDataField(String text) {
		JsonNode imageData = JSON_MAPPER.readTree(text).path("data");
		if (!imageData.isObject() || imageData.size() != 1) {
			return null;
		}
		JsonNode fieldValue = imageData.properties().iterator().next().getValue();
		return fieldValue.isString() ? fieldValue.asString() : null;
	}

	// Builds the McpSchema.Tool record that tools/list publishes.
	// The MCP Java SDK 2.0.0 jar lacks a @NullMarked package, and Tool's compact
	// constructor requires the name and the input schema alone, so a null description
	// reaches the builder as it is.
	//
	// The record holds each schema as a map, and the SDK's own builder fills it by
	// reading the text with the application's mapper as it stands. That mapper reads a
	// JSON float into a double, so a default the writer spelled as
	// 1234567890.123456789012345678 would reach tools/list as 1.2345678901234567E9, while
	// a whole number past a long keeps its digits. The reader here is the same mapper
	// with big decimals on: the transport still writes the map, and every digit of the
	// value it writes came from the operation file.
	private static McpSchema.Tool buildTool(GATool tool, JsonMapper jsonMapper) {
		ObjectReader schemas = jsonMapper.reader()
			.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.forType(SCHEMA_TYPE);
		// Spring AI's provider falls back to the tool name when a hand-written tool
		// leaves its title unset, so a generated tool does the same and clients show one
		// shape. A blank title falls back too: @gatool(title: "") reaches here as the
		// empty string, and publishing that renders a blank label where the name would
		// have done.
		String title = tool.title();
		boolean titled = title != null && !title.isBlank();
		McpSchema.Tool.Builder builder = McpSchema.Tool.builder(tool.name(), readSchema(schemas, tool.inputSchema()))
			.title(titled ? title : tool.name())
			.description(tool.description())
			.annotations(buildAnnotations(tool, titled ? title : null));
		// MCP binds a server to a schema it publishes, so this appears only where the
		// property or the operation file asked for it.
		String outputSchema = tool.outputSchema();
		if (outputSchema != null) {
			builder.outputSchema(readSchema(schemas, outputSchema));
		}
		return builder.build();
	}

	// Reads one schema text into the map the SDK's record holds, and names the schema
	// with a $id built from the SHA-256 of that text.
	// The SDK compiles a schema on its first use and keeps the compiled form in a cache
	// that one validator holds for the whole JVM. The key is the schema's $id, and for a
	// schema without one it is Map.hashCode() written as text, which two different
	// schemas can share. A map adds up the hash codes of its entries, so the schemas of
	// $prId: ID! and $prID: ID! come to the same number: the hash codes of the two names
	// differ by 32, each name sits once under properties and once under required, and the
	// two differences cancel. The cache trusts its key, so the tool called second would
	// be validated against the schema of the tool called first, and its every correct
	// call refused with "required property 'prId' not found" until the JVM restarts.
	// With output schemas on, two aliases such as pr and PR would do the same to a
	// result: the request runs, and the caller reads an output validation error in place
	// of the answer.
	//
	// The $id is the SHA-256 of the text as the writer produced it, in UTF-8, so the key
	// follows the content. Two different texts get two keys. Two tools with the same text
	// get the same key, and one compiled schema is the right one for both. Neither
	// writer produces a $id, and a text that arrived with one would publish this one in
	// its place, so the rule holds for every schema.
	//
	// The schema writers leave the keyword out, because their text also travels
	// in-process to a model provider, where the keyword adds 98 characters to every
	// prompt that carries the tool, and a keyword still unmeasured against a provider can
	// refuse a whole tool. The SDK's validator is the only reader of the key and it runs
	// on the MCP surface alone, so the keyword is added here, on the way to the SDK.
	//
	// It sits right after $schema, or first in a schema that leaves $schema out, so the
	// two keywords that describe the schema itself open the text a client reads, and the
	// order is the same on every run.
	private static Map<String, Object> readSchema(ObjectReader schemas, String text) {
		Map<String, Object> written = schemas.readValue(text);
		Map<String, Object> identified = new LinkedHashMap<>();
		Object dialect = written.get(JsonSchemaKeywords.SCHEMA);
		if (dialect != null) {
			identified.put(JsonSchemaKeywords.SCHEMA, dialect);
		}
		identified.put(ID_KEYWORD, ID_PREFIX + sha256(text));
		written.forEach(identified::putIfAbsent);
		return identified;
	}

	private static String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every JDK ships SHA-256", ex);
		}
	}

	// Builds the tool annotations, deriving the read-only, destructive and idempotent
	// hints from the tool's own read-only flag.
	// A hand-written @McpTool publishes all four hints, because every member of a Java
	// annotation carries a value, so a generated tool publishes each hint it can derive.
	// The GraphQL specification requires a query field to be free of side effects, so a
	// query reads alone, leaves the data as it found it, and answers the same way each
	// time. That makes readOnly and idempotent true, and destructive false.
	//
	// A mutation takes the other three, which are MCP's own defaults for a tool that is
	// not read-only: destructive true and idempotent false. Both are conservative: a
	// mutation that only creates a row does not destroy anything, and one that sets a
	// field to a given value is idempotent, and GATool cannot tell which a mutation is
	// from the schema. The hints exist for a client deciding whether to ask a human
	// first or retry on a timeout, so claiming less is the safe direction.
	//
	// The annotations title is the same hint the tool's own title carries, so it is set
	// where the operation file gives one and left out otherwise. The empty string, the
	// value Spring AI writes for a hand-written tool whose author omits it, renders as a
	// blank label in a client showing the hint.
	private static McpSchema.ToolAnnotations buildAnnotations(GATool tool, @Nullable String title) {
		McpSchema.ToolAnnotations.Builder annotations = McpSchema.ToolAnnotations.builder()
			.readOnlyHint(tool.readOnly())
			.destructiveHint(!tool.readOnly())
			.idempotentHint(tool.readOnly());
		if (title != null) {
			annotations.title(title);
		}
		// The open world hint says whether the tool reaches an open-ended set of
		// entities, which depends on what sits behind the GraphQL API. An operation file
		// can state that and the starter cannot infer it, so the hint appears when the
		// file declares it.
		Boolean openWorld = tool.openWorld();
		if (openWorld != null) {
			annotations.openWorldHint(openWorld);
		}
		return annotations.build();
	}

	// Returns the arguments of one call as a map whose values may be null, or an empty
	// map for a request without arguments.
	// A request without arguments carries a null map, and a tool without variables is
	// called that way. The copy is the boundary between two nullness contracts:
	// mcp-core 2.0.0 declares arguments() as Map<String, Object> without nullness
	// annotations, while the rest of GATool carries Map<String, @Nullable Object>,
	// because a GraphQL variable may be an explicit null. Copying states that a value
	// from JSON may be null, where a cast would assert knowledge the SDK withheld. It
	// costs one map per call, against a call to a GraphQL API.
	private static Map<String, @Nullable Object> copyArguments(McpSchema.CallToolRequest request) {
		Map<String, Object> arguments = request.arguments();
		return (arguments != null) ? new LinkedHashMap<>(arguments) : Map.of();
	}

	/**
	 * The throttle of one tool's refusal lines: a line opens a window of a minute, and
	 * the refusals inside it are counted for the next line.
	 */
	// A copy of what ToolCallRunner does for a failing API, kept here because that one
	// belongs to another module and holds the state of one runner, while these
	// specifications are built by a static method.
	static final class RefusalWindow {

		private boolean open;

		private long openedAt;

		private int suppressed;

		// Returns null while the window is open, and otherwise opens a new one and
		// returns what the line says about the refusals left out since the last line.
		synchronized @Nullable String admit(long now) {
			if (this.open && now - this.openedAt < REFUSAL_WINDOW_NANOS) {
				this.suppressed++;
				return null;
			}
			int leftOut = this.suppressed;
			this.open = true;
			this.openedAt = now;
			this.suppressed = 0;
			if (leftOut == 0) {
				return "";
			}
			return " GATool left " + leftOut + " more such " + ((leftOut == 1) ? "refusal" : "refusals")
					+ " for this tool out of the log since its last line.";
		}

	}

}
