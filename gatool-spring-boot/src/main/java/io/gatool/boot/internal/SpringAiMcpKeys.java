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

package io.gatool.boot.internal;

import org.springframework.core.env.Environment;

/**
 * The Spring AI MCP server key that the shared auto-configuration reads.
 *
 * <p>
 * Three startup stops in the credentials auto-configuration turn on whether the
 * application serves MCP over stdio, where the caller is the process that started the
 * server and arrives without a token, and the MCP module's own auto-configurations read
 * the same fact. The key and its default live here, in the module both sides depend on,
 * so the core reads the {@link Environment} alone and stays free of every class of the
 * MCP module.
 *
 * @author Željko Kozina
 */
public final class SpringAiMcpKeys {

	/**
	 * The key under which Spring AI's MCP server switches to the stdio transport.
	 */
	public static final String STDIO = "spring.ai.mcp.server.stdio";

	private SpringAiMcpKeys() {
	}

	/**
	 * Whether the application serves MCP over stdio.
	 *
	 * <p>
	 * The primitive return keeps the unboxing out of the callers.
	 * @param environment the environment to read
	 * @return true where {@code spring.ai.mcp.server.stdio} is set
	 */
	public static boolean servesStdio(Environment environment) {
		return environment.getProperty(STDIO, Boolean.class, Boolean.FALSE);
	}

}
