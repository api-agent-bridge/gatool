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

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The private field the startup check reads, on the MCP Java SDK version GATool builds
 * with.
 *
 * <p>
 * GATool stops startup for an MCP server built without {@code immediateExecution(true)},
 * and the SDK keeps that setting in a private field of each server class without an
 * accessor. A later SDK that renames the field, moves it or changes its type would leave
 * the startup check warning and passing every server. These tests fail GATool's build
 * instead, so the rename is met while the SDK is upgraded.
 */
class ImmediateExecutionTests {

	@Test
	void field_onTheStatefulServerOfTheSdkGAToolBuildsWith_shouldBeADeclaredBoolean() throws Exception {
		Field field = McpSyncServer.class.getDeclaredField(ImmediateExecution.FIELD);

		assertThat(field.getType()).isEqualTo(boolean.class);
	}

	@Test
	void field_onTheStatelessServerOfTheSdkGAToolBuildsWith_shouldBeADeclaredBoolean() throws Exception {
		Field field = McpStatelessSyncServer.class.getDeclaredField(ImmediateExecution.FIELD);

		assertThat(field.getType()).isEqualTo(boolean.class);
	}

	@Test
	void of_anObjectWithTheField_shouldReadItsValue() {
		assertThat(ImmediateExecution.of(new Immediate(true))).isTrue();
		assertThat(ImmediateExecution.of(new Immediate(false))).isFalse();
	}

	@Test
	void of_anObjectWithoutTheField_shouldAnswerNullSoStartupWarnsAndCarriesOn() {
		assertThat(ImmediateExecution.of("a server of another shape")).isNull();
	}

	@Test
	void versionOf_theSdkServerClass_shouldNameTheVersionItsJarCarries() {
		// The jar's manifest carries Implementation-Version. A class loaded from a
		// directory leaves it out, and the check then says so rather than failing.
		assertThat(ImmediateExecution.versionOf(McpSyncServer.class)).isNotBlank();
	}

	private static final class Immediate {

		@SuppressWarnings("unused")
		private final boolean immediateExecution;

		Immediate(boolean immediateExecution) {
			this.immediateExecution = immediateExecution;
		}

	}

}
