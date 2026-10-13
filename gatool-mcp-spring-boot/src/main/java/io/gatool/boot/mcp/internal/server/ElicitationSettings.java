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

import java.time.Duration;

/**
 * What the approval step needs: whether this transport asks at all, the two sentences the
 * model reads after a decline and a cancel, and the request timeout Spring AI gave the
 * server, which bounds the wait and is named in the timeout answer.
 *
 * @param asks whether a tool marked elicit asks on this transport; off where the
 * deployment cannot ask and serves the tool without asking
 * @param declinedAnswer the sentence after a decline, with {tool} for the tool name
 * @param cancelledAnswer the sentence after a cancel, with {tool} for the tool name
 * @param requestTimeout the value of spring.ai.mcp.server.request-timeout
 * @author Željko Kozina
 */
public record ElicitationSettings(boolean asks, String declinedAnswer, String cancelledAnswer,
		Duration requestTimeout) {

	/**
	 * The request timeout of a transport that cannot ask: Spring AI's default of 20
	 * seconds for spring.ai.mcp.server.request-timeout. A plain constant keeps this
	 * package free of the Spring AI auto-configure jar, which an application may leave
	 * off its classpath. McpCallPolicies reads the default from Spring AI itself, and its
	 * tests compare the two.
	 */
	public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);

	static final String DEFAULT_DECLINED_ANSWER = "The user declined the call to {tool}, so the API was not called. "
			+ "Ask the user what to do next.";

	static final String DEFAULT_CANCELLED_ANSWER = "The user dismissed the confirmation for {tool} without answering, "
			+ "so the API was not called. Ask the user whether to try again.";

	/**
	 * Returns the settings of a transport that cannot ask, with the default sentences and
	 * Spring AI's default timeout.
	 * @return settings under which every tool runs without asking
	 */
	public static ElicitationSettings off() {
		return new ElicitationSettings(false, DEFAULT_DECLINED_ANSWER, DEFAULT_CANCELLED_ANSWER,
				DEFAULT_REQUEST_TIMEOUT);
	}

	/**
	 * Writes a duration the way a person reads it: in whole seconds from one second up,
	 * with "1 second" in the singular, and in milliseconds below one second. The timeout
	 * answer the model reads and the startup warning about a short timeout both name the
	 * request timeout this way.
	 * @param duration the duration to write
	 * @return the duration in words, such as 20 seconds, 1 second or 500 milliseconds
	 */
	// Whole seconds truncate as Duration.toSeconds() does, so 1500 milliseconds reads as
	// 1 second. Below one second toSeconds() is 0, and "0 seconds" reads as a timeout of
	// zero, so the milliseconds are named there.
	public static String inWords(Duration duration) {
		long seconds = duration.toSeconds();
		if (seconds >= 1) {
			return seconds + ((seconds == 1) ? " second" : " seconds");
		}
		long milliseconds = duration.toMillis();
		return milliseconds + ((milliseconds == 1) ? " millisecond" : " milliseconds");
	}

}
