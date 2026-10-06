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

package io.gatool.tests.skeleton;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three ceilings at their defaults, and each one switched off with zero.
 *
 * <p>
 * The folder schema nests as deeply as a document asks, because a folder has a parent, so
 * a document can pass the default depth of 15, the default field count of 500 and the
 * default alias count of 30. Validate-only mode returns an accepted document as written,
 * so the tests tell an accepted document from a refused one without an API.
 */
class DynamicOperationLimitsAtDefaultAndZeroTests {

	private static final String PREFIX = "gatool.dev.experimental.dynamic-operations.";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:gatool/deprecations/catalog.graphqls",
				// The default MCP location holds an operation file written against the
				// movie schema, so both locations point at an empty folder, which the
				// switch allows.
				"gatool.mcp.operations.locations=optional:classpath*:gatool/none/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/",
				"gatool.dev.experimental.generate-tools=dynamic-three-step", PREFIX + "validate-only=true");

	@Test
	void executeGraphql_eighteenLevelsDeep_shouldBeRefusedAtTheDefaultAndRunWithMaxDepthAtZero() {
		String document = nested(18);

		this.contextRunner.run((context) -> {
			ToolCallOutcome outcome = execute(context, document);

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).contains("it nests fields 18 levels deep, and the limit is 15");
		});

		this.contextRunner.withPropertyValues(PREFIX + "max-depth=0").run((context) -> {
			ToolCallOutcome outcome = execute(context, document);

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).contains("\"document\"");
		});
	}

	@Test
	void executeGraphql_fiveHundredAndThirteenFields_shouldBeRefusedAtTheDefaultAndRunWithMaxFieldsAtZero() {
		// Nine doublings of a one-field fragment make 512 leaves under one root field.
		String document = "{ folder(name: \"docs\") { ...L9 } }" + doublingFragments(9);

		this.contextRunner.run((context) -> {
			ToolCallOutcome outcome = execute(context, document);

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).contains(
					"it selects more fields than the limit of 500, counting every " + "fragment where it is spread");
		});

		this.contextRunner.withPropertyValues(PREFIX + "max-fields=0").run((context) -> {
			ToolCallOutcome outcome = execute(context, document);

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).contains("\"document\"");
		});
	}

	@Test
	void executeGraphql_maxFieldsAtZero_shouldStillStopAtTheCeilingGraphqlJavaRefusesByDefault() {
		// Seventeen doublings are 131072 leaves in a document under a kilobyte. With the
		// field limit off, the walk still stops at graphql-java's own default of 100,000
		// fields, so a document built to explode the count is refused before it costs
		// anything.
		String document = "{ folder(name: \"docs\") { ...L17 } }" + doublingFragments(17);

		this.contextRunner.withPropertyValues(PREFIX + "max-fields=0").run((context) -> {
			ToolCallOutcome outcome = execute(context, document);

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).contains("it selects more fields than the limit of 100000");
		});
	}

	@Test
	void executeGraphql_thirtyOneAliases_shouldBeRefusedAtTheDefaultAndRunWithMaxAliasesAtZero() {
		StringBuilder document = new StringBuilder("{");
		for (int index = 0; index < 31; index++) {
			document.append(" f").append(index).append(": folder(name: \"docs\") { name }");
		}
		document.append(" }");

		this.contextRunner.run((context) -> {
			ToolCallOutcome outcome = execute(context, document.toString());

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).contains("it uses 31 aliases, and the limit is 30");
		});

		this.contextRunner.withPropertyValues(PREFIX + "max-aliases=0").run((context) -> {
			ToolCallOutcome outcome = execute(context, document.toString());

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).contains("\"document\"");
		});
	}

	/**
	 * Writes a document whose deepest field sits at the given level: the root field, then
	 * parent fields, then a name.
	 */
	private static String nested(int depth) {
		StringBuilder document = new StringBuilder("{ folder(name: \"docs\") {");
		for (int level = 2; level < depth; level++) {
			document.append(" parent {");
		}
		document.append(" name");
		for (int level = 1; level < depth; level++) {
			document.append(" }");
		}
		return document.append(" }").toString();
	}

	/**
	 * Writes fragments L0 to Ln on Folder, where L0 selects a name and each later one
	 * spreads the one before it twice.
	 */
	private static String doublingFragments(int doublings) {
		StringBuilder fragments = new StringBuilder(" fragment L0 on Folder { name }");
		for (int level = 1; level <= doublings; level++) {
			fragments.append(" fragment L")
				.append(level)
				.append(" on Folder { ...L")
				.append(level - 1)
				.append(" ...L")
				.append(level - 1)
				.append(" }");
		}
		return fragments.toString();
	}

	private static ToolCallOutcome execute(AssertableApplicationContext context, String document) {
		return context.getBean(GAToolCatalog.class)
			.mcpTools()
			.stream()
			.filter((tool) -> "executeGraphql".equals(tool.name()))
			.findFirst()
			.map((tool) -> tool.call(Map.of("document", document)))
			.orElseThrow(() -> new AssertionError("no tool named executeGraphql"));
	}

}
