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
					+ "emitted beside another. Take the bean gaToolStdioMcpSyncServerCustomizer out of "
					+ "GAToolMcpAutoConfiguration, take its startup check over stdio out beside it, and take this "
					+ "test out.")
			.isIn("2.0.0", "2.0.1");
	}

}
