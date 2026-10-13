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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The stdio reports of the immediate-execution check, read off server beans that stand in
 * for the SDK's: one keeps the setting in the field the check reads, and one leaves the
 * field out, which is what an SDK that renamed the field looks like.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImmediateExecutionCheckTests {

	private static final String KEEP_THE_THREAD = ", or leave gatool.mcp.stdio.elicitation off, which keeps each "
			+ "tool call on the thread that reads stdin.";

	private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

	@Test
	void requireWorkerThreadExecution_serverBuiltElsewhereWithImmediateExecution_shouldStopNamingTheBuilder() {
		this.beanFactory.registerSingleton("stdioServer", new ServerWithTheSetting(true));

		assertThatExceptionOfType(McpSecurityException.class)
			.isThrownBy(() -> ImmediateExecutionCheck.requireWorkerThreadExecution(this.beanFactory,
					ServerWithTheSetting.class, ImmediateExecutionCheck::overStdioWhileAsking))
			.satisfies((stop) -> {
				assertThat(stop.getMessage()).startsWith("gatool.mcp.stdio.elicitation is true")
					.contains("The MCP server bean 'stdioServer' was built with immediateExecution(true)")
					.endsWith("This bean was built elsewhere, so the builder that built it set immediate execution.");
				assertThat(stop.action()).isEqualTo(
						"Remove immediateExecution(true) from the builder that builds 'stdioServer'" + KEEP_THE_THREAD);
			});
	}

	@Test
	void requireWorkerThreadExecution_severalApplicationCustomizers_shouldNameEachOfThem() {
		this.beanFactory.registerSingleton("stdioServer", new ServerWithTheSetting(true));
		this.beanFactory.registerSingleton("firstCustomizer",
				(McpSyncServerCustomizer) (server) -> server.immediateExecution(true));
		this.beanFactory.registerSingleton("secondCustomizer", (McpSyncServerCustomizer) (server) -> {
		});

		assertThatExceptionOfType(McpSecurityException.class)
			.isThrownBy(() -> ImmediateExecutionCheck.requireWorkerThreadExecution(this.beanFactory,
					ServerWithTheSetting.class, ImmediateExecutionCheck::overStdioWhileAsking))
			.satisfies((stop) -> {
				assertThat(stop.getMessage()).endsWith("This context holds the McpSyncServerCustomizer beans "
						+ "'firstCustomizer', 'secondCustomizer', one of which the server took.");
				assertThat(stop.action())
					.isEqualTo("Remove immediateExecution(true) from the customizer the server took" + KEEP_THE_THREAD);
			});
	}

	@Test
	void requireWorkerThreadExecution_serverWithoutImmediateExecution_shouldPass() {
		this.beanFactory.registerSingleton("stdioServer", new ServerWithTheSetting(false));

		assertThatNoException().isThrownBy(() -> ImmediateExecutionCheck.requireWorkerThreadExecution(this.beanFactory,
				ServerWithTheSetting.class, ImmediateExecutionCheck::overStdioWhileAsking));
	}

	@Test
	void requireWorkerThreadExecution_settingThatCannotBeRead_shouldWarnAndLetStartupGoOn(CapturedOutput output) {
		this.beanFactory.registerSingleton("stdioServer", new ServerWithoutTheSetting());

		assertThatNoException().isThrownBy(() -> ImmediateExecutionCheck.requireWorkerThreadExecution(this.beanFactory,
				ServerWithoutTheSetting.class, ImmediateExecutionCheck::overStdioWhileAsking));

		assertThat(output).contains("WARN")
			.contains("GATool could not read whether the MCP server bean 'stdioServer' was built with "
					+ "immediateExecution(true).")
			.contains("GATool therefore cannot tell at startup whether each tool call runs off the thread that "
					+ "reads stdin, which is where the user's answer arrives while gatool.mcp.stdio.elicitation "
					+ "is true. Leave immediateExecution(true) out of this application's McpSyncServerCustomizer "
					+ "bean while gatool.mcp.stdio.elicitation is true.");
	}

	@Test
	void requireImmediateExecution_stdioServerWithoutAnApplicationCustomizer_shouldSayToWriteFalse() {
		this.beanFactory.registerSingleton("mcpSyncServer", new ServerWithTheSetting(false));

		assertThatExceptionOfType(McpSecurityException.class)
			.isThrownBy(() -> ImmediateExecutionCheck.requireImmediateExecution(this.beanFactory,
					ServerWithTheSetting.class, ImmediateExecutionCheck::overStdio))
			.satisfies((stop) -> {
				assertThat(stop.getMessage()).endsWith("gatool.mcp.stdio.elicitation holds another word that binds "
						+ "false, such as off: GATool registers its own customizer only while that property is "
						+ "unset or is the word false.");
				assertThat(stop.action())
					.endsWith("Where gatool.mcp.stdio.elicitation holds another word for false, write false.");
			});
	}

	/**
	 * A server that keeps the setting in the field the check reads, as both SDK server
	 * classes do.
	 */
	static final class ServerWithTheSetting {

		final boolean immediateExecution;

		ServerWithTheSetting(boolean immediateExecution) {
			this.immediateExecution = immediateExecution;
		}

	}

	/**
	 * A server without that field, which is what an SDK that renamed it looks like.
	 */
	static final class ServerWithoutTheSetting {

	}

}
