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

package io.gatool.boot.internal.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.GraphQlResponse;

import io.gatool.boot.execution.GraphQlExecutionRequest;
import io.gatool.boot.execution.GraphQlExecutor;
import io.gatool.boot.execution.ToolCallFailedException;
import io.gatool.boot.internal.CauseChain;
import io.gatool.boot.internal.credentials.CredentialUnavailableException;
import io.gatool.core.internal.operation.NullArguments;
import io.gatool.core.internal.operation.OperationType;
import io.gatool.core.internal.operation.ToolOperation;
import io.gatool.core.model.GATool;
import io.gatool.core.model.ToolCallOutcome;

/**
 * Runs one tool call and builds the {@link GATool} that carries it.
 *
 * <p>
 * This is the one place that executes a document, writes the result text and decides what
 * a failure looks like. The MCP adapter calls it, and the Spring AI adapter calls it as
 * well, so both exposure types answer a call the same way.
 *
 * <p>
 * The error contract has two halves:
 *
 * <ul>
 * <li>A response that holds GraphQL errors comes back as a {@link ToolCallOutcome} whose
 * error flag is true, with the data and the errors in its text. That keeps a partial
 * result readable for the model. An answer the API refused, a credential the strategy
 * could not supply and a result too large to return come back the same way, each with a
 * sentence written for the model and a {@link ToolCallOutcome.Kind} an observation
 * reads.</li>
 * <li>A transport failure leaves this method as a {@link ToolCallFailedException}. The
 * in-process needs a {@code ToolExecutionException}, and GATool's own
 * {@code GAToolCallback} builds one from an exception that leaves the function and copies
 * its message. The MCP adapter catches the same exception and answers with a tool
 * error.</li>
 * </ul>
 *
 * <p>
 * Returning an outcome with the error flag set for a transport failure as well would read
 * more simply here, and it would hand the in-process an ordinary result where a raised
 * exception is needed, so that adapter would have to rebuild the failure from the text.
 *
 * @author Željko Kozina
 */
public final class ToolCallRunner {

	private static final Log logger = LogFactory.getLog(ToolCallRunner.class);

	private static final String ANSWERED = " called the GraphQL API and it answered ";

	// Enough for a JSON error body or a sentence, and short of a page of markup.
	private static final int MAX_BODY_CHARACTERS = 2000;

	// One WARN line per tool per minute for an answer the operator has to act on,
	// because an API that stays down fails every call, and a line per call fills the
	// log with the same line repeated. The next line carries the count left out.
	private static final long WARN_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

	private final GraphQlExecutor executor;

	private final GraphQlResultWriter writer;

	private final int maxCharacters;

	private static final String ERRORS = "errors";

	// An API or a proxy that echoes the request's Authorization header into an error page
	// would hand the outbound token to the model, so the pattern is blanked. The
	// separator group is possessive (++), because Java's regex engine recurses once per
	// iteration of a repeated group that holds an alternation, and a body of the size the
	// response cap allows would end in a StackOverflowError. Possessive means the
	// separators are never given back: a "%20" with nothing after it stays as it is,
	// where the backtracking form would blank it as if it were a token. The (?i) flag
	// covers the character class as well, so one letter range matches both cases.
	private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer(?:\\s|%20)++[a-z0-9._~+/=%-]+");

	private final boolean sendExplicitNulls;

	private final boolean partialResultsAsSuccess;

	private final UnaryOperator<String> redaction;

	private final LongSupplier clock;

	// The read deadline of the client behind the executor, or null where GATool did
	// not build that client and so cannot name its deadline.
	private final @Nullable Duration readTimeout;

	private final ConcurrentMap<String, WarnWindow> warnWindows = new ConcurrentHashMap<>();

	private ToolCallRunner(Builder builder, int maxCharacters) {
		this.clock = builder.clock;
		this.readTimeout = builder.readTimeout;
		this.executor = builder.executor;
		this.writer = builder.writer;
		this.maxCharacters = maxCharacters;
		this.sendExplicitNulls = builder.nullInputs == NullInputs.SEND;
		this.partialResultsAsSuccess = builder.partialResults == PartialResults.AS_SUCCESS;
		this.redaction = builder.redaction;
	}

	/**
	 * Starts a runner.
	 * @param executor runs the document
	 * @param writer writes the result text
	 * @return a builder, in which {@code maxCharacters} is the one value that has to be
	 * set
	 */
	public static Builder builder(GraphQlExecutor executor, GraphQlResultWriter writer) {
		return new Builder(executor, writer);
	}

	/**
	 * Builds the tool for one operation, with this runner behind it.
	 * @param operation the validated operation
	 * @return the tool that the adapters turn into their own type
	 */
	public GATool toolFor(ToolOperation operation) {
		// Named first, so the handler's parameter type is stated where the record's
		// constructor would otherwise infer it from the surrounding arguments.
		Function<Map<String, @Nullable Object>, ToolCallOutcome> handler = (arguments) -> run(operation, arguments);
		return GATool.builder()
			.name(operation.toolName())
			.title(operation.title())
			.description(operation.description())
			.inputSchema(operation.inputSchema())
			.readOnly(operation.operationType() == OperationType.QUERY)
			.openWorld(operation.openWorld())
			.outputSchema(operation.outputSchema())
			.callHandler(handler)
			.scopes(operation.scopes())
			.build();
	}

	/**
	 * Runs one call.
	 * @param operation the operation the tool sends
	 * @param arguments the arguments the caller sent
	 * @return the result text, with the error flag set when the response holds GraphQL
	 * errors
	 * @throws ToolCallFailedException if the call failed in transport
	 */
	public ToolCallOutcome run(ToolOperation operation, Map<String, @Nullable Object> arguments) {
		GraphQlResponse response;
		try {
			response = executeDocument(operation, arguments);
		}
		catch (ResponseTooLargeException ex) {
			// The call reached the API and the answer was too big to read, which is a
			// result the model can act on, so it reads the reason instead of the
			// sentence a transport failure gets.
			return new ToolCallOutcome(ex.getMessage() + afterAnAnswerTooLarge(operation, true), true, null,
					ToolCallOutcome.Kind.RESPONSE_TOO_LARGE);
		}
		catch (ApiRefusedException ex) {
			// The API refused the call and said why. The model reads the status and
			// the body, so it can correct a request the API rejected, and it is told
			// whether waiting helps.
			return new ToolCallOutcome(describeRefusal(operation, ex), true, null, ToolCallOutcome.Kind.API_REFUSED);
		}
		catch (CredentialUnavailableException ex) {
			// The call stopped before the API, because the strategy could not supply the
			// credential it sends. The credential is the operator's to fix, so the model
			// reads that the arguments are not the cause, the way it does for a 401.
			return new ToolCallOutcome(describeMissingCredential(operation, ex), true, null,
					ToolCallOutcome.Kind.CREDENTIAL_UNAVAILABLE);
		}
		Map<String, @Nullable Object> envelope = withoutCredentials(this.writer.envelope(response));
		String text = this.writer.write(envelope);
		if (text.length() > this.maxCharacters) {
			return new ToolCallOutcome(describeTooLarge(operation, text.length()), true, null,
					ToolCallOutcome.Kind.RESULT_TOO_LARGE);
		}
		// The structured result is read back from the text, so the two halves carry the
		// same values, which is what a tool publishing an output schema promises. The
		// envelope map itself holds Java objects, and the MCP mapper writes those
		// differently from the application's own modules: a Long the application writes
		// as a string would arrive as a number, a BigDecimal would lose its scale, and a
		// null field would go missing.
		@Nullable Map<String, @Nullable Object> structured = (operation.outputSchema() != null) ? this.writer.readBack(text)
				: null;
		boolean error = isError(response);
		return new ToolCallOutcome(text, error, structured,
				error ? ToolCallOutcome.Kind.GRAPHQL_ERRORS : ToolCallOutcome.Kind.SUCCESS);
	}

	// A response holding both data and errors is a partial result. By default it is a
	// tool error, so the model reads that something failed, and the text carries the
	// data as well. With gatool.results.partial-results-as-success the data is the
	// result and the errors travel in the text beside it, which suits an API whose
	// errors describe fields that are missing on purpose. A response without data is
	// an error either way, because no data reached the model.
	private boolean isError(GraphQlResponse response) {
		if (this.partialResultsAsSuccess) {
			return !response.isValid();
		}
		return this.writer.holdsErrors(response);
	}

	// Every message a tool call produces opens with the tool's name, because a reader of
	// a log line or a model reading an error both need to know which tool it came from.
	private static String describeTool(ToolOperation operation) {
		return "The tool " + operation.toolName();
	}

	// Returns the ResponseTooLargeException this failure is, or carries as a cause, or
	// null otherwise.
	private static @Nullable ResponseTooLargeException findResponseTooLarge(Throwable failure) {
		return CauseChain.first(failure, ResponseTooLargeException.class);
	}

	/**
	 * The largest result any tool of this application returns, in characters.
	 *
	 * <p>
	 * The two schema tools of the dynamic layer answer without calling the API, so they
	 * do not pass through {@link #run}, and the property is documented as the largest
	 * result a tool returns. They read the value here and cut their own answers by it.
	 * @return the value of {@code gatool.results.max-characters}
	 */
	public int maxCharacters() {
		return this.maxCharacters;
	}

	// Returns what the model reads after the sentence that says an answer of the API
	// passed a size limit: how to ask for less for a query, and that the write may have
	// been applied for a mutation.
	//
	// Both size limits are checked after the API answered, so a mutation has run by then,
	// and advice to ask for less would invite the model to send the write a second time.
	private static String afterAnAnswerTooLarge(ToolOperation operation, boolean fewerFieldsHelp) {
		if (operation.operationType() != OperationType.QUERY) {
			return " The API answered the call, and this tool writes, so the write may have been applied already. "
					+ "Read the current state before you send it again.";
		}
		StringBuilder advice = new StringBuilder();
		if (fewerFieldsHelp) {
			advice.append(" Ask for fewer fields or a smaller page.");
		}
		List<String> pagination = operation.paginationVariables();
		if (!pagination.isEmpty()) {
			advice.append(" Ask for a smaller page with ").append(String.join(", ", pagination)).append('.');
		}
		return advice.toString();
	}

	// The bearer pattern and the configured credential are blanked before a body reaches
	// the model or the log. The length is cut separately, in cut(String), because an API
	// that answers an error with a whole HTML page would otherwise fill the model's
	// context with markup.
	private String redact(String body) {
		return this.redaction.apply(BEARER_TOKEN.matcher(body.strip()).replaceAll("Bearer [redacted]"));
	}

	// Returns the envelope with the credential taken out of it, so the text and the
	// structured content both reach the model without it.
	//
	// A 200 carrying errors needs the same care as a refusal: an API that names a
	// rejected header in errors[].message would send the key to the model, and to the
	// structured content beside it. The errors member is the API's own diagnostic text,
	// so the bearer pattern applies there. Under data only the exact configured
	// credential is replaced, because a pattern loose enough to catch any token would
	// also cut a result the operation asked for.
	private Map<String, @Nullable Object> withoutCredentials(Map<String, @Nullable Object> envelope) {
		Map<String, @Nullable Object> scrubbed = new LinkedHashMap<>();
		for (Map.Entry<String, @Nullable Object> entry : envelope.entrySet()) {
			scrubbed.put(entry.getKey(), scrub(entry.getValue(), ERRORS.equals(entry.getKey())));
		}
		return scrubbed;
	}

	// Returns one value of the envelope with the credential taken out of every string
	// under it.
	private @Nullable Object scrub(@Nullable Object value, boolean diagnostic) {
		if (value instanceof String text) {
			String withoutCredential = this.redaction.apply(text);
			return diagnostic ? BEARER_TOKEN.matcher(withoutCredential).replaceAll("Bearer [redacted]")
					: withoutCredential;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, @Nullable Object> scrubbed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				scrubbed.put(String.valueOf(entry.getKey()), scrub(entry.getValue(), diagnostic));
			}
			return scrubbed;
		}
		if (value instanceof List<?> list) {
			List<@Nullable Object> scrubbed = new ArrayList<>(list.size());
			for (Object item : list) {
				scrubbed.add(scrub(item, diagnostic));
			}
			return scrubbed;
		}
		return value;
	}

	// The cut steps back one character when it would land between the two halves of a
	// surrogate pair, because a lone high surrogate is not a character. The JSON writer
	// either refuses it or writes a replacement, and the model reads a broken symbol.
	private static String cut(String body) {
		if (body.length() <= MAX_BODY_CHARACTERS) {
			return body;
		}
		int end = MAX_BODY_CHARACTERS;
		if (Character.isHighSurrogate(body.charAt(end - 1))) {
			end--;
		}
		return body.substring(0, end) + "…";
	}

	// Builds the tool error text for a call the API refused, naming the status, what the
	// API said, and whether a later call may succeed.
	private String describeRefusal(ToolOperation operation, ApiRefusedException refusal) {
		StringBuilder message = new StringBuilder(describeTool(operation)).append(ANSWERED).append(refusal.status());
		switch (refusal.shape()) {
			case UNREADABLE_CONTENT_TYPE -> message.append(" with content type ")
				.append(refusal.unreadableContentType())
				.append(", which does not carry a GraphQL response.");
			case EMPTY_BODY -> message.append(" with an empty body, where a GraphQL response was expected.");
			case UNREADABLE_BODY -> message.append(" with a body that is not a GraphQL response: ")
				.append(cut(redact(String.valueOf(refusal.unreadableBodyReason()))))
				.append('.');
			case REFUSING_STATUS -> message.append('.');
		}
		String body = redact(refusal.body());
		if (!body.isEmpty()) {
			message.append(" The API said: ").append(cut(body));
		}
		switch (refusal.shape()) {
			case UNREADABLE_CONTENT_TYPE, EMPTY_BODY -> {
				// The arguments are not what produced this, so the sentence below would
				// send a model to edit a call that was correct. A gateway or a sign-in
				// page in front of the API answers every call this way until someone
				// changes the deployment.
				message.append(" That is usually a gateway or a sign-in page in front of the GraphQL API, so the "
						+ "same call gets the same answer and different arguments will not help.");
				return message.toString();
			}
			case UNREADABLE_BODY -> {
				// The content type promised JSON and the body broke that promise, which
				// is a proxy or a maintenance page under the API's own content type.
				message.append(" The same call will get the same answer, so different arguments will not help.");
				return message.toString();
			}
			case REFUSING_STATUS -> {
				// Falls through to the sentence below, which reads the status.
			}
		}
		return message.append(afterARefusingStatus(operation, refusal)).toString();
	}

	// Returns the sentence that tells the model what a next call gets after a refusing
	// status: the same answer, or possibly a different one, and what a write has to check
	// first.
	private static String afterARefusingStatus(ToolOperation operation, ApiRefusedException refusal) {
		if (refusal.isRedirect()) {
			// The JDK would turn a redirected POST into a GET without a body on a 301,
			// 302
			// or 303, and a credential in a custom header would travel to whatever host
			// the Location names, so GATool's client leaves every redirect unfollowed.
			// The
			// URL is the operator's to set, and executeDocument() warned about it.
			return " That is a redirect, which GATool does not follow on a call, so the same call gets "
					+ "the same answer until gatool.api.url names the URL the API answers at.";
		}
		if (refusal.isRefusedCredential()) {
			// The credential is the operator's, so the model is told to stop editing the
			// call, and executeDocument() warned the operator about the same status.
			return " The credential GATool sends was refused, so the arguments are not the cause, and the "
					+ "same call gets the same answer until that credential or the API's access rules change.";
		}
		if (!refusal.isRetryable()) {
			return " The same call will get the same answer, so change the arguments or the operation.";
		}
		if (operation.operationType() == OperationType.QUERY) {
			return " A later call may succeed.";
		}
		if (refusal.status() == 408 || refusal.status() == 425) {
			// A 408 says the API gave up waiting for the request, and a 425 says it
			// would not risk running a request that might be a replay. Neither status
			// says the API read the request, so the model is not told the write may
			// have run, only asked to check the state before it writes again.
			return " A later call may succeed. This tool writes, so read the current state before you "
					+ "send it again.";
		}
		// A 5xx and a 429 do not say whether the API read the request before it
		// refused, and this operation writes. Inviting a repeat would invite
		// applying it twice.
		return " A later call may succeed, and this tool writes, so the first call may have been "
				+ "applied already. Read the current state before you send it again.";
	}

	// Builds the tool error text for a call that did not go out because the strategy
	// could not supply the credential, naming the strategy and what was missing.
	//
	// The strategy's own message names the property that selects it and where such a
	// call can come from, and the closing sentence is the one a refused credential
	// gets: the arguments are not the cause.
	private String describeMissingCredential(ToolOperation operation, CredentialUnavailableException failure) {
		return describeTool(operation) + " did not call the GraphQL API, because the " + failure.strategy()
				+ " strategy could not supply the credential GATool sends: " + cut(redact(failure.getMessage()))
				+ " The arguments are not the cause, and the same call gets the same answer until the strategy can "
				+ "supply the credential.";
	}

	// Builds the tool error text for a result longer than gatool.results.max-characters,
	// carrying the size, the limit and, for a query, the way to ask for less.
	//
	// The text itself stays behind, because cutting JSON in the middle leaves the model
	// something it cannot parse.
	private String describeTooLarge(ToolOperation operation, int size) {
		StringBuilder message = new StringBuilder("The result of ").append(operation.toolName())
			.append(" is ")
			.append(size)
			.append(" characters, and the limit is ")
			.append(this.maxCharacters)
			.append(". Cutting the text would leave invalid JSON, so GATool held the result back.");
		return message.append(afterAnAnswerTooLarge(operation, false)).toString();
	}

	// A 5xx, and every 2xx whose body is something other than a GraphQL response.
	private static boolean concernsTheOperator(ApiRefusedException refusal) {
		return refusal.shape() != ApiRefusedException.Shape.REFUSING_STATUS || refusal.status() >= 500;
	}

	// Returns what the next WARN line about one tool says about the answers left out
	// since the last one, or null while that last line is less than a minute old.
	private @Nullable String admitWarning(String tool) {
		return this.warnWindows.computeIfAbsent(tool, (name) -> new WarnWindow()).admit(this.clock.getAsLong());
	}

	// Builds the WARN line for an answer the operator has to act on: the status, the
	// shape of the answer and what the API said.
	private String describeForOperator(ToolOperation operation, ApiRefusedException refusal) {
		StringBuilder line = new StringBuilder(describeTool(operation)).append(ANSWERED).append(refusal.status());
		switch (refusal.shape()) {
			case UNREADABLE_CONTENT_TYPE -> line.append(" with content type ")
				.append(refusal.unreadableContentType())
				.append(" in place of a GraphQL response, which is usually a gateway or a sign-in page in "
						+ "front of the API.");
			case EMPTY_BODY -> line.append(" with an empty body in place of a GraphQL response, which is usually "
					+ "a gateway in front of the API.");
			case UNREADABLE_BODY -> line.append(" with a body that is not a GraphQL response (")
				.append(cut(redact(String.valueOf(refusal.unreadableBodyReason()))))
				.append("), which is usually a proxy or a maintenance page answering under the API's "
						+ "content type.");
			case REFUSING_STATUS -> {
				line.append(", which is the API, or a gateway in front of it, failing");
				String body = redact(refusal.body());
				if (!body.isEmpty()) {
					line.append(": ").append(cut(body));
				}
				line.append('.');
			}
		}
		return line.toString();
	}

	private GraphQlResponse executeDocument(ToolOperation operation, Map<String, @Nullable Object> arguments) {
		// The null argument rules run here, so MCP and in-process send
		// the same variables for the same arguments.
		Map<String, @Nullable Object> variables = NullArguments.prepare(arguments, operation.variablesWithDefaults(),
				operation.variableTypes(), this.sendExplicitNulls);
		try {
			return this.executor.execute(
					new GraphQlExecutionRequest(operation.printedDocument(), operation.operationName(), variables));
		}
		catch (ApiRefusedException ex) {
			// The API answered, so run() turns this into a tool error carrying what it
			// said. The sentences below belong to a call that failed in transport.
			logRefusal(operation, ex);
			throw ex;
		}
		catch (CredentialUnavailableException ex) {
			// The credential is the operator's to fix, so the line is a WARN, one per
			// call, and the cause, where the strategy met one, goes to DEBUG.
			logger.warn(describeTool(operation) + " did not call the GraphQL API, because the " + ex.strategy()
					+ " strategy could not supply the credential GATool sends: " + cut(redact(ex.getMessage())));
			if (logger.isDebugEnabled() && ex.getCause() != null) {
				logger.debug(describeTool(operation) + " could not get its credential", ex.getCause());
			}
			throw ex;
		}
		catch (RuntimeException ex) {
			ResponseTooLargeException describeTooLarge = findResponseTooLarge(ex);
			if (describeTooLarge != null) {
				// The HTTP client or the JSON reader may carry the refusal as a cause,
				// so the chain is searched before the message below is written.
				throw describeTooLarge;
			}
			// The log carries the cause, and the caller reads a message that names the
			// tool and keeps stack traces and exception class names out of the text.
			// The stack goes to DEBUG, because an API that stays down fails every call,
			// and a trace on each one fills the log with the same trace repeated. The
			// WARN
			// line names the tool, how far the call got and the cause, which is what an
			// operator reads first. The cause's message is redacted and cut like a body
			// the API sent, because the JDK names a whole header value in its message
			// when it refuses one, and that value is the credential.
			TransportFailure failure = TransportFailure.of(ex);
			String line = describeForOperator(operation, failure);
			logger.warn(line + ": " + cut(redact(String.valueOf(ex))));
			if (logger.isDebugEnabled()) {
				logger.debug(line, ex);
			}
			throw new ToolCallFailedException(describeTransportFailure(operation, failure), ex);
		}
	}

	// Logs a call the API refused: a WARN line for an answer the operator has to act on,
	// and the status and the body at DEBUG for every answer.
	//
	// An operator reads the status here, at DEBUG, because a refused call is
	// the API's own answer and the model already has it. A refused credential
	// and a redirect are the operator's to fix, so each is a WARN line, one per
	// call, without the body. A 5xx and a 2xx without a GraphQL response are the
	// operator's as well, and those warn once a minute per tool, further down.
	// The guard is what the Log interface asks for. The body an API sends with an
	// error can be a whole HTML page, and redacting and cutting it is work that
	// a default configuration, which prints INFO and above, throws away.
	private void logRefusal(ToolOperation operation, ApiRefusedException ex) {
		if (ex.isRefusedCredential()) {
			logger.warn(describeTool(operation) + ANSWERED + ex.status()
					+ ", which refuses the credential GATool sends. Check gatool.api.credentials, or the "
					+ "ApiCredentialStrategy bean, and the access rules of the API.");
		}
		else if (ex.isRedirect()) {
			// A redirect is the URL being wrong, which is the operator's to fix as a
			// credential is, and the model cannot act on it. The Location goes to the
			// log alone, redacted, because a sign-in redirect can carry a token in it.
			String location = ex.redirectLocation();
			logger.warn(describeTool(operation) + ANSWERED + ex.status() + ", a redirect"
					+ ((location != null) ? " to " + cut(redact(location)) : "")
					+ ", which GATool does not follow on a call. Set gatool.api.url to the URL the API "
					+ "answers at.");
		}
		else if (concernsTheOperator(ex)) {
			// A 5xx is the API, or a gateway in front of it, failing, and a 2xx
			// without a GraphQL response is a gateway, a sign-in page or a proxy in
			// front of it. The model cannot act on either and the operator can, so
			// each is a WARN line, one per tool per minute. A 4xx stays at DEBUG,
			// because the model reads it and can act on it: it corrects the call, or
			// sends the same one again after a 408, a 425 or a 429.
			String leftOut = admitWarning(operation.toolName());
			if (leftOut != null) {
				logger.warn(describeForOperator(operation, ex) + leftOut);
			}
		}
		if (logger.isDebugEnabled()) {
			logger.debug(describeTool(operation) + ANSWERED + ex.status() + ": " + cut(redact(ex.body())));
		}
	}

	private static String couldNotReach(ToolOperation operation) {
		return describeTool(operation) + " could not reach the GraphQL API";
	}

	// Builds the opening of the WARN line and of the DEBUG line for a call that failed in
	// transport, which says how far the call got wherever the cause of the failure tells.
	//
	// The line opens as the model's sentence does, with the deadline where GATool knows
	// it. After a read timeout the request has reached the API, so an opening that says
	// the API could not be reached would send an operator to the network where the API is
	// slow. Where the cause does not say how far the call got, the line says that the
	// call failed in transport and that the cause does not say whether it ran, because a
	// connection the API closed inside an answer had carried the request. A write gets
	// the same line as a read: the model is told to read the current state after every
	// failure of a write, and the operator is told what happened.
	private String describeForOperator(ToolOperation operation, TransportFailure failure) {
		return switch (failure) {
			case BEFORE_THE_REQUEST_LEFT -> couldNotReach(operation);
			case READ_TIMEOUT -> describeTheDeadlineThatPassed(operation);
			case UNCLASSIFIED -> failedInTransport(operation) + ", and the cause does not say whether the call ran";
		};
	}

	private static String failedInTransport(ToolOperation operation) {
		return describeTool(operation) + " failed in transport while calling the GraphQL API";
	}

	// Builds the text a model reads for a call that failed in transport, which says how
	// far the call got wherever the cause of the failure tells.
	//
	// A connection that did not open means the call did not run, and a later call may
	// succeed. After a read timeout the API had the request, and the same call is likely
	// to take as long, so a model invited to send it again would spend a second wait on
	// the same failure. Where the cause does not say how far the call got, the sentence
	// says that much and stops short of claiming it did not run.
	//
	// Every sentence opens as the operator's line for the same failure opens, so the
	// model and the operator read one account of one failure.
	private String describeTransportFailure(ToolOperation operation, TransportFailure failure) {
		if (operation.operationType() != OperationType.QUERY) {
			return describeTransportFailureOfAWrite(operation, failure);
		}
		return switch (failure) {
			case BEFORE_THE_REQUEST_LEFT ->
				couldNotReach(operation) + ". The call did not run, and a later call may succeed.";
			case READ_TIMEOUT -> describeReadTimeout(operation);
			case UNCLASSIFIED -> failedInTransport(operation)
					+ ". GATool cannot tell whether the call ran, and a later call may succeed.";
		};
	}

	// A write is followed by the same request whatever the cause: read the current
	// state before sending it again. After a connection that did not open the request
	// is kept as well, because a second write costs more than a read where the chain
	// of causes was read wrongly. What differs is the opening and the reason.
	private String describeTransportFailureOfAWrite(ToolOperation operation, TransportFailure failure) {
		String readTheState = "so read the current state before you send it again.";
		return switch (failure) {
			case BEFORE_THE_REQUEST_LEFT -> couldNotReach(operation) + ". This tool writes, " + readTheState;
			case READ_TIMEOUT -> describeTheDeadlineThatPassed(operation)
					+ ". This tool writes, and the write may have run, " + readTheState;
			case UNCLASSIFIED -> failedInTransport(operation)
					+ ". This tool writes, and GATool cannot tell whether the write ran, " + readTheState;
		};
	}

	// Builds the text a model reads for a query the API took longer to answer than the
	// read timeout: the deadline and the property that sets it where GATool knows them,
	// and the way to ask for less.
	//
	// The pagination variables are named where the operation has them, as they are
	// for a result above the size limit.
	private String describeReadTimeout(ToolOperation operation) {
		StringBuilder message = new StringBuilder(describeTheDeadlineThatPassed(operation))
			.append(". The same call is likely to take as long again, so ask for less: fewer fields");
		List<String> pagination = operation.paginationVariables();
		if (pagination.isEmpty()) {
			return message.append(" or a smaller page.").toString();
		}
		return message.append(", or a smaller page with ").append(String.join(", ", pagination)).append('.').toString();
	}

	// Says that the API took longer to answer than the read timeout, with the deadline
	// and the property that sets it where GATool knows them, as the model's sentence and
	// the operator's line both open.
	//
	// The opening is "called the GraphQL API", the one a refusal gets, because the
	// request did reach the API.
	private String describeTheDeadlineThatPassed(ToolOperation operation) {
		String tookLonger = describeTool(operation)
				+ " called the GraphQL API, and the API took longer to answer than the ";
		if (this.readTimeout != null) {
			return tookLonger + "read timeout of " + describe(this.readTimeout)
					+ ", which spring.http.clients.read-timeout sets";
		}
		return tookLonger + "client's read timeout";
	}

	// Whole seconds read as seconds, which is how a deadline is usually set, and a
	// finer value reads in milliseconds.
	private static String describe(Duration timeout) {
		long millis = timeout.toMillis();
		if (millis % 1000 != 0) {
			return millis + ((millis == 1) ? " millisecond" : " milliseconds");
		}
		long seconds = millis / 1000;
		return seconds + ((seconds == 1) ? " second" : " seconds");
	}

	/**
	 * What the runner does with a null argument, which
	 * {@code gatool.inputs.send-explicit-nulls} decides.
	 */
	// An enum where a boolean would do, because the constructors take this setting
	// beside the one below, and two booleans side by side compile when they are swapped.
	public enum NullInputs {

		/**
		 * A null for a variable with a default is left out, so the default applies.
		 */
		OMIT,

		/**
		 * Every null is sent as the caller wrote it.
		 */
		SEND

	}

	/**
	 * How the runner reports a response that holds data beside errors, which
	 * {@code gatool.results.partial-results-as-success} decides.
	 */
	public enum PartialResults {

		/**
		 * A response with errors is a tool error, with its data kept in the text.
		 */
		AS_ERROR,

		/**
		 * A response with usable data is a success, with its errors kept in the text.
		 */
		AS_SUCCESS

	}

	/**
	 * Collects the settings of a {@link ToolCallRunner}.
	 *
	 * <p>
	 * A builder names each setting where it is set, and a setting added later is one more
	 * method.
	 */
	public static final class Builder {

		private final GraphQlExecutor executor;

		private final GraphQlResultWriter writer;

		private @Nullable Integer maxCharacters;

		private NullInputs nullInputs = NullInputs.OMIT;

		private PartialResults partialResults = PartialResults.AS_ERROR;

		private UnaryOperator<String> redaction = UnaryOperator.identity();

		private @Nullable Duration readTimeout;

		private LongSupplier clock = System::nanoTime;

		private Builder(GraphQlExecutor executor, GraphQlResultWriter writer) {
			this.executor = executor;
			this.writer = writer;
		}

		/**
		 * Sets the largest result a tool returns. Required.
		 * @param maxCharacters the value of {@code gatool.results.max-characters}
		 * @return this builder
		 */
		public Builder maxCharacters(int maxCharacters) {
			this.maxCharacters = maxCharacters;
			return this;
		}

		/**
		 * Sets what the runner does with a null argument. A null for a variable with a
		 * default is left out unless this says otherwise.
		 * @param nullInputs the value of {@code gatool.inputs.send-explicit-nulls}
		 * @return this builder
		 */
		public Builder nullInputs(NullInputs nullInputs) {
			this.nullInputs = nullInputs;
			return this;
		}

		/**
		 * Sets how the runner reports a response that holds data beside errors, which is
		 * a tool error unless this says otherwise.
		 * @param partialResults the value of
		 * {@code gatool.results.partial-results-as-success}
		 * @return this builder
		 */
		public Builder partialResults(PartialResults partialResults) {
			this.partialResults = partialResults;
			return this;
		}

		/**
		 * Sets the function that blanks the configured credential in a text the model or
		 * the log reads. A runner without one leaves the text as it is.
		 * @param redaction replaces the credential value with a placeholder
		 * @return this builder
		 */
		public Builder redaction(UnaryOperator<String> redaction) {
			this.redaction = redaction;
			return this;
		}

		/**
		 * Sets the read deadline of the client behind the executor, which the runner
		 * names to the model when a query outlasts it.
		 * @param readTimeout the deadline, or {@code null} where the executor has none
		 * @return this builder
		 */
		public Builder readTimeout(@Nullable Duration readTimeout) {
			this.readTimeout = readTimeout;
			return this;
		}

		// For a test that moves time by hand.
		Builder clock(LongSupplier clock) {
			this.clock = clock;
			return this;
		}

		/**
		 * Builds the runner.
		 * @return the runner
		 * @throws IllegalStateException if {@code maxCharacters} is unset
		 */
		public ToolCallRunner build() {
			Integer limit = this.maxCharacters;
			if (limit == null) {
				throw new IllegalStateException(
						"A ToolCallRunner needs maxCharacters, and the builder was given none.");
			}
			return new ToolCallRunner(this, limit);
		}

	}

	/**
	 * The throttle of one tool's WARN lines: a line opens a window of a minute, and the
	 * answers that fall inside it are counted for the next line.
	 */
	private static final class WarnWindow {

		private boolean open;

		private long openedAt;

		private int suppressed;

		// Returns null while the window is open, and otherwise opens a new one and
		// returns what the line says about the answers left out since the last line.
		synchronized @Nullable String admit(long now) {
			if (this.open && now - this.openedAt < WARN_WINDOW_NANOS) {
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
			return " GATool left " + leftOut + " more such " + ((leftOut == 1) ? "answer" : "answers")
					+ " for this tool out of the log since its last line.";
		}

	}

}
