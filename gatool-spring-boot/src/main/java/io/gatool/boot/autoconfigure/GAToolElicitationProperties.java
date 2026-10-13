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

package io.gatool.boot.autoconfigure;

/**
 * Settings for the question a tool marked {@code @gatool(elicit:)} asks the user before
 * each call, under {@code gatool.elicitation}.
 *
 * <p>
 * Like the other properties classes, the contract is the properties themselves: the
 * names, types and defaults. The getters and setters exist for Spring Boot's binder and
 * may change in any release.
 *
 * @author Željko Kozina
 */
public class GAToolElicitationProperties {

	/**
	 * Creates the group under {@code gatool.elicitation} with the default of each
	 * property.
	 */
	public GAToolElicitationProperties() {
	}

	/**
	 * The sentence the model reads after the user declined. {tool} is replaced by the
	 * tool name.
	 */
	private String declinedAnswer = "The user declined the call to {tool}, so the API was not called. "
			+ "Ask the user what to do next.";

	/**
	 * The sentence the model reads after the user dismissed the confirmation without
	 * answering. {tool} is replaced by the tool name.
	 */
	private String cancelledAnswer = "The user dismissed the confirmation for {tool} without answering, so the "
			+ "API was not called. Ask the user whether to try again.";

	/**
	 * Whether a deployment that cannot ask the user, which is the stateless server, the
	 * in-process starter, and stdio while gatool.mcp.stdio.elicitation is false, starts
	 * with the tools marked elicit running without asking. While this is false, startup
	 * fails and names those tools.
	 */
	private boolean ignoreWhereUnsupported;

	/**
	 * Returns the sentence the model reads after a decline.
	 * @return the sentence, with {tool} standing for the tool name
	 */
	public String getDeclinedAnswer() {
		return this.declinedAnswer;
	}

	/**
	 * Sets the sentence the model reads after a decline.
	 * @param declinedAnswer the sentence, with {tool} standing for the tool name
	 */
	public void setDeclinedAnswer(String declinedAnswer) {
		this.declinedAnswer = declinedAnswer;
	}

	/**
	 * Returns the sentence the model reads after a cancel.
	 * @return the sentence, with {tool} standing for the tool name
	 */
	public String getCancelledAnswer() {
		return this.cancelledAnswer;
	}

	/**
	 * Sets the sentence the model reads after a cancel.
	 * @param cancelledAnswer the sentence, with {tool} standing for the tool name
	 */
	public void setCancelledAnswer(String cancelledAnswer) {
		this.cancelledAnswer = cancelledAnswer;
	}

	/**
	 * Returns whether a deployment that cannot ask serves the tools marked elicit without
	 * asking.
	 * @return {@code true} to serve them without asking, {@code false} to stop startup
	 */
	public boolean isIgnoreWhereUnsupported() {
		return this.ignoreWhereUnsupported;
	}

	/**
	 * Sets whether a deployment that cannot ask serves the tools marked elicit without
	 * asking.
	 * @param ignoreWhereUnsupported {@code true} to serve them without asking
	 */
	public void setIgnoreWhereUnsupported(boolean ignoreWhereUnsupported) {
		this.ignoreWhereUnsupported = ignoreWhereUnsupported;
	}

}
