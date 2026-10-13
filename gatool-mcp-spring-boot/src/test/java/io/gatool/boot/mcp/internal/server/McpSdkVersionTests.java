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

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP Java SDK versions the stdio customizer is written for.
 *
 * <p>
 * Over stdio the SDK sends every answer through one queue that takes one answer at a
 * time, and an answer emitted beside another is dropped, which closes the transport.
 * GATool works around that by running each stdio tool call on the thread that reads
 * stdin. The SDK repaired the queue on its main branch for
 * https://github.com/modelcontextprotocol/java-sdk/issues/686, and 2.0.0 and 2.0.1 are
 * the releases from before that repair. This test fails GATool's build on a later
 * release, so the workaround is met while the SDK is upgraded.
 *
 * <p>
 * The version is read from the {@code Implementation-Version} entry of the
 * {@code mcp-core} jar, which its manifest carries, through the package of an SDK class.
 * A classpath that leaves that entry out, such as a shaded jar or a classes directory,
 * meets a message of its own.
 */
class McpSdkVersionTests {

	@Test
	void mcpCoreVersion_shouldBeOneThisWorkaroundIsWrittenFor() {
		String version = StdioServerTransportProvider.class.getPackage().getImplementationVersion();

		assertThat(version)
			.as("The mcp-core on this classpath leaves Implementation-Version out of its manifest, "
					+ "which a shaded jar and a classes directory both do, so this test cannot tell which MCP Java "
					+ "SDK release the build runs against. Build against the published jar, or read the version from "
					+ "the build.")
			.isNotNull()
			.as("The MCP Java SDK release found here is later than 2.0.1, so it carries the repair "
					+ "of https://github.com/modelcontextprotocol/java-sdk/issues/686 and its queue takes an answer "
					+ "emitted beside another. Stdio can then run each tool call on the SDK's thread pool and ask "
					+ "the user, so the workaround and gatool.mcp.stdio.elicitation leave in one change. "
					+ "In GAToolMcpAutoConfiguration, take out the bean gaToolStdioMcpSyncServerCustomizer with its "
					+ "@ConditionalOnProperty on gatool.mcp.stdio.elicitation. Keep its startup check "
					+ "gaToolStdioImmediateExecutionStartupCheck: an application's own customizer that sets "
					+ "immediateExecution(true) on stdio still holds each tool call on the thread that reads stdin, "
					+ "where the user's answer arrives, so the check keeps requiring immediateExecution(false) "
					+ "there. Have it require that where a tool is marked elicit, which today the property "
					+ "decides, and take out its requireImmediateExecution branch. In ImmediateExecutionCheck, take "
					+ "out overStdio with describeStdioWorkerThreadServer, actOnStdioWorkerThreadServer and "
					+ "GATOOL_STDIO_SERVER_CUSTOMIZER, keep requireWorkerThreadExecution and overStdioWhileAsking, "
					+ "and take the property out of the sentences overStdioWhileAsking writes. In "
					+ "gaToolMcpStatefulToolSpecifications, take out the asks rule, so stdio asks the way stateful "
					+ "HTTP does, and with it the stdio call of McpStartupStops.stopWhereToolsElicitWithoutAChannel "
					+ "and McpStartupStops.stdioElicitationFix. Take the property out of GAToolMcpProperties and take "
					+ "McpStartupWarnings.warnOnStdioElicitation out. McpStartupStops."
					+ "stopOnStdioElicitationInAWebApplication names the property as well: Spring AI's own "
					+ "customizer still runs each call on the thread that reads stdin in a servlet application that "
					+ "serves stdio, so keep that stop and drop the property from it. Have it fire where a tool is "
					+ "marked elicit, which today the property alone decides, so a servlet application that serves "
					+ "stdio without an elicit tool keeps starting. Take the property's row and its sentences out of "
					+ "the README, add a CHANGELOG entry that says the property is gone, and take this test out.")
			.isIn("2.0.0", "2.0.1");
	}

}
