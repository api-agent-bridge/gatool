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

import java.lang.reflect.Field;

import org.jspecify.annotations.Nullable;

/**
 * Reads whether an MCP server was built to run a synchronous tool handler on the thread
 * that called it.
 *
 * <p>
 * The MCP Java SDK subscribes a synchronous tool handler on Reactor's bounded elastic
 * scheduler unless the server was built with {@code immediateExecution(true)}. Both
 * server classes keep that setting in a private field without an accessor, so reflection
 * is the one way to read it. The SDK jar is an automatic module, so the read succeeds on
 * the class path.
 *
 * <p>
 * A read that fails answers {@code null}, and the caller warns and leaves the server as
 * it is: the refusal a call meets when it arrives off the request thread still holds.
 * {@link #FIELD} is pinned by a test against the SDK version GATool builds with, so a
 * later SDK that renames the field fails GATool's build.
 *
 * @author Željko Kozina
 */
public final class ImmediateExecution {

	/**
	 * The name of the field both SDK server classes keep the setting in.
	 */
	public static final String FIELD = "immediateExecution";

	private static final String UNKNOWN_VERSION = "unknown";

	private ImmediateExecution() {
	}

	/**
	 * Returns whether the server runs a synchronous tool handler on the calling thread.
	 * @param server an {@code McpSyncServer} or an {@code McpStatelessSyncServer}
	 * @return the setting, or {@code null} where the field could not be read
	 */
	public static @Nullable Boolean of(Object server) {
		try {
			Field field = server.getClass().getDeclaredField(FIELD);
			if (field.getType() != boolean.class) {
				return null;
			}
			field.setAccessible(true);
			return field.getBoolean(server);
		}
		// A later SDK can rename the field, move it to a supertype or close its
		// package, and each of those arrives as one of these.
		catch (ReflectiveOperationException | RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Returns the version of the MCP Java SDK the server class came from, for the warning
	 * a failed read writes.
	 * @param serverType the SDK class whose jar carries the version
	 * @return the version, or {@code unknown} where the jar left it out
	 */
	public static String versionOf(Class<?> serverType) {
		Package sdk = serverType.getPackage();
		String version = (sdk != null) ? sdk.getImplementationVersion() : null;
		return (version != null) ? version : UNKNOWN_VERSION;
	}

}
