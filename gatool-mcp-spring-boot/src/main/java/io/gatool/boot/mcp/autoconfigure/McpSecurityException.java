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

package io.gatool.boot.mcp.autoconfigure;

/**
 * Stops startup for a security setup GATool cannot serve, carrying what to change for the
 * failure analysis: a chain that lacks the resource server, a missing security filter, or
 * tools without scopes behind a credential without a user behind it.
 *
 * @author Željko Kozina
 */
public final class McpSecurityException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * What to change: the line to add, with the matcher it needs.
	 */
	private final String action;

	McpSecurityException(String message, String action) {
		super(message);
		this.action = action;
	}

	/**
	 * Returns what to change.
	 * @return the line to add, with the matcher it needs
	 */
	public String action() {
		return this.action;
	}

}
