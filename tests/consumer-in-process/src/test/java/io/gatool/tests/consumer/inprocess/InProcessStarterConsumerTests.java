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

package io.gatool.tests.consumer.inprocess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

import io.gatool.boot.inprocess.GAToolCallbacks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application that adds the in-process tools starter alone.
 *
 * <p>
 * tests/skeleton adds both starters and the fixtures, so a starter that stops declaring
 * something its own auto-configuration needs still works there, on the other starter's
 * classpath, and the gap shows up only once somebody depends on one starter alone. This
 * module declares one starter, so the classpath under test is the one that user gets.
 *
 * <p>
 * It also carries the MCP Java SDK, without the MCP starter beside it, which is the shape
 * an application reaches by adding Spring AI's MCP client starter. The application starts
 * in that shape, because {@code GAToolMcpAutoConfiguration} lives in
 * {@code gatool-mcp-spring-boot}, which this classpath leaves out.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = { "gatool.api.url=http://127.0.0.1:1/graphql",
		"gatool.api.schema.location=classpath:greetings.graphqls" })
class InProcessStarterConsumerTests {

	@Autowired
	private GAToolCallbacks tools;

	@Autowired
	private ApplicationContext context;

	@Test
	void context_theToolsStarterAlone_shouldPublishTheToolsHandle() {
		ToolCallback[] callbacks = this.tools.toolCallbackProvider().getToolCallbacks();

		assertThat(callbacks).singleElement().satisfies((callback) -> {
			assertThat(callback.getToolDefinition().name()).isEqualTo("greeting");
			assertThat(callback.getToolDefinition().description()).isEqualTo("Greets someone by name.");
			assertThat(callback.getToolDefinition().inputSchema()).contains("\"name\"").contains("Who to greet.");
		});
	}

	@Test
	void context_theToolsStarterAlone_shouldLeaveTheMcpServerOut() {
		// The two starters are separate so that an application wanting in-process tools
		// keeps the MCP server away, and this is where that stays true.
		assertThat(this.context.getBeanNamesForType(Object.class))
			.noneMatch((name) -> name.toLowerCase().contains("mcpserver"));
	}

	@Test
	void context_theMcpSdkOnTheClasspathWithoutTheMcpStarter_shouldLeaveEveryMcpBeanOut() {
		// The context starting at all is most of this test, since the failure this pins
		// is a linkage error during condition evaluation. The rate limiter is the bean
		// that reaches for Caffeine, so its absence says the whole MCP side backed off.
		assertThat(this.context.getBeanNamesForType(Object.class))
			.noneMatch((name) -> name.startsWith("gaToolMcp") || name.equals("gaToolRateLimiter"));
	}

	@Test
	void startup_theInProcessStarterAlone_shouldStaySilentAboutMcpTools(CapturedOutput output) {
		// The warning exists for an MCP server that reads an empty folder. This
		// application does not run an MCP server, so the line would point at a property
		// of a server that is absent here.
		assertThat(output.getAll()).doesNotContain("GATool serves zero MCP tools");
	}

	@SpringBootApplication
	static class ConsumerApplication {

	}

}
