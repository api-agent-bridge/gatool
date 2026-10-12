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

import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.core.model.GATool;

/**
 * Asks the user, through MCP elicitation, whether a tool marked {@code elicit} may run.
 *
 * <p>
 * The form carries the message alone, so the client shows the question with Accept,
 * Decline and Cancel, and Accept approves. The capability check comes first, as Spring
 * AI's own request context does it, so a client that cannot ask reads a tool error
 * instead of an exception from the SDK.
 *
 * @author Željko Kozina
 */
final class UserApproval {

	private static final Log logger = LogFactory.getLog(UserApproval.class);

	/**
	 * The form GATool sends: an object without fields, so the client asks for a decision
	 * alone.
	 */
	static final Map<String, Object> EMPTY_FORM = Map.of("type", "object", "properties", Map.of());

	/** How many characters of the arguments follow the question. */
	static final int ARGUMENTS_LIMIT = 1_000;

	static final String CUT_MARK = " [cut]";

	private static final String TOOL_PLACEHOLDER = "{tool}";

	private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer(?:\\s|%20)++[a-z0-9._~+/=%-]+");

	private static final JsonMapper WRITER = JsonMapper.builder().build();

	private UserApproval() {
	}

	/**
	 * Asks the user and returns the decision.
	 * @param tool the tool, whose {@code elicit()} is the question
	 * @param arguments the arguments the model sent, shown under the question
	 * @param exchange the exchange of this call, or null where the handler ran without
	 * one
	 * @param settings the sentences and the timeout
	 * @return the verdict
	 */
	static Verdict ask(GATool tool, Map<String, @Nullable Object> arguments, @Nullable McpSyncServerExchange exchange,
			ElicitationSettings settings) {
		String question = tool.elicit();
		if (question == null) {
			return Verdict.ACCEPTED;
		}
		if (exchange == null || !canAskInFormMode(exchange)) {
			return new Verdict(Decision.UNSUPPORTED, "The tool " + tool.name()
					+ " asks the user before it runs, and this client did not declare the form elicitation "
					+ "capability, so the API was not called. Connect with a client that supports MCP form elicitation.",
					"elicitation-unsupported");
		}
		McpSchema.ElicitResult result;
		try {
			result = exchange
				.createElicitation(new McpSchema.ElicitFormRequest(message(question, arguments), EMPTY_FORM, null));
		}
		catch (RuntimeException failure) {
			if (isTimeout(failure)) {
				return new Verdict(Decision.TIMED_OUT,
						"The user did not answer the confirmation for " + tool.name() + " within "
								+ settings.requestTimeout().toSeconds()
								+ " seconds (spring.ai.mcp.server.request-timeout), so the API was not called.",
						"elicitation-timeout");
			}
			logger.error("The confirmation for " + tool.name() + " failed before the user could answer.", failure);
			return new Verdict(Decision.FAILED, "The confirmation for " + tool.name()
					+ " failed before the user could answer, so the API was not called.", "elicitation-failed");
		}
		return switch (result.action()) {
			case ACCEPT -> Verdict.ACCEPTED;
			case DECLINE -> new Verdict(Decision.DECLINED,
					answer(settings.declinedAnswer(), tool.name(), ElicitationSettings.DEFAULT_DECLINED_ANSWER),
					"elicitation-declined");
			case CANCEL -> new Verdict(Decision.CANCELLED,
					answer(settings.cancelledAnswer(), tool.name(), ElicitationSettings.DEFAULT_CANCELLED_ANSWER),
					"elicitation-cancelled");
		};
	}

	/**
	 * Builds the message the user reads: the question, then a blank line and the
	 * arguments as compact JSON, cut at {@link #ARGUMENTS_LIMIT} characters. The
	 * arguments come from the model, so a bearer token among them is redacted, and
	 * arguments Jackson cannot write leave the question alone.
	 * @param question the question the directive wrote
	 * @param arguments the arguments the model sent
	 * @return the message
	 */
	static String message(String question, Map<String, @Nullable Object> arguments) {
		if (arguments.isEmpty()) {
			return question;
		}
		String json;
		try {
			json = WRITER.writeValueAsString(arguments);
		}
		catch (RuntimeException failure) {
			logger.warn("The arguments of a call could not be written for the user to read, so the question is "
					+ "shown alone.", failure);
			return question;
		}
		json = BEARER_TOKEN.matcher(json).replaceAll("Bearer [redacted]");
		if (json.length() > ARGUMENTS_LIMIT) {
			json = json.substring(0, ARGUMENTS_LIMIT) + CUT_MARK;
		}
		return question + "\n\n" + json;
	}

	// A blank template reads as the default, so a property left blank still gives the
	// model a sentence.
	static String answer(String template, String toolName, String fallback) {
		String text = template.isBlank() ? fallback : template;
		return text.replace(TOOL_PLACEHOLDER, toolName);
	}

	// Mirrors the SDK's rule in McpAsyncServerExchange, where elicitation: {} is
	// equivalent to elicitation: { form: {} }, so a client that declared URL mode
	// alone cannot be asked in form mode.
	private static boolean canAskInFormMode(McpSyncServerExchange exchange) {
		McpSchema.ClientCapabilities capabilities = exchange.getClientCapabilities();
		if (capabilities == null || capabilities.elicitation() == null) {
			return false;
		}
		McpSchema.ClientCapabilities.Elicitation elicitation = capabilities.elicitation();
		return elicitation.form() != null || elicitation.url() == null;
	}

	// The sync exchange blocks on a Mono with the request timeout, and Reactor wraps the
	// checked TimeoutException it raises, so the cause chain is what says timeout.
	private static boolean isTimeout(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof TimeoutException) {
				return true;
			}
		}
		return false;
	}

	/** What the user, the client or the wait decided. */
	enum Decision {

		ACCEPTED, DECLINED, CANCELLED, UNSUPPORTED, TIMED_OUT, FAILED

	}

	/**
	 * One decision with what the model reads and the outcome tag of the observation. Both
	 * are empty after an accept.
	 *
	 * @param decision what the user, the client or the wait decided
	 * @param answer what the model reads
	 * @param outcome the outcome tag of the observation
	 */
	record Verdict(Decision decision, String answer, String outcome) {

		static final Verdict ACCEPTED = new Verdict(Decision.ACCEPTED, "", "");

	}

}
