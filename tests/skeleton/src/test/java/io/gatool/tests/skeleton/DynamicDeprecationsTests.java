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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.ToolCallOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code include-deprecated-fields} hides and shows, on a schema that deprecates one
 * of everything GraphQL lets a schema deprecate: a field, an argument, an enum value and
 * an input field.
 *
 * <p>
 * The property acts on all four the same way: hidden from both schema tools while it is
 * off, and written with the schema's own reason while it is on. {@code executeGraphql}
 * validates against the whole schema either way, and the last test covers the operation
 * file side, where startup warns once per file.
 */
@ExtendWith(OutputCaptureExtension.class)
class DynamicDeprecationsTests {

	private static final String PREFIX = "gatool.dev.experimental.dynamic-operations.";

	private static final String INCLUDE_DEPRECATED = PREFIX + "include-deprecated-fields=true";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:gatool/deprecations/catalog.graphqls",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/");

	// The default MCP location holds an operation file written against the movie schema,
	// so the dynamic runner points the location at an empty folder, which the switch
	// allows.
	private final ApplicationContextRunner dynamicContextRunner = this.contextRunner.withPropertyValues(
			"gatool.dev.experimental.generate-tools=dynamic-three-step",
			"gatool.mcp.operations.locations=optional:classpath*:gatool/none/", PREFIX + "validate-only=true");

	@Test
	void searchSchema_aDeprecatedField_shouldStayOutByDefaultAndAppearWithItsReasonWhenIncluded() {
		this.dynamicContextRunner
			.run((context) -> assertThat(search(context, "the number of files").text()).doesNotContain("Folder.size"));

		this.dynamicContextRunner.withPropertyValues(INCLUDE_DEPRECATED)
			.run((context) -> assertThat(search(context, "the number of files").text()).contains("Folder.size:\n")
				.contains("size: Int! @deprecated(reason: \"Use fileCount.\")  # on type Folder"));
	}

	@Test
	void introspectType_aDeprecatedField_shouldStayOutByDefaultAndCarryItsReasonWhenIncluded() {
		this.dynamicContextRunner
			.run((context) -> assertThat(introspect(context, "Folder").text()).contains("  name: String!\n")
				.doesNotContain("size: Int!")
				.doesNotContain("archive"));

		this.dynamicContextRunner.withPropertyValues(INCLUDE_DEPRECATED)
			.run((context) -> assertThat(introspect(context, "Folder").text())
				.contains("  size: Int! @deprecated(reason: \"Use fileCount.\")\n")
				.contains("  archive: Archive @deprecated(reason: \"Archives are gone.\")\n"));
	}

	@Test
	void introspectType_aTypeReachedThroughADeprecatedFieldAlone_shouldBeOutOfReachUntilIncluded() {
		// A field the corpus leaves out is one the model cannot see, so the type behind
		// it is one the model cannot reach, and the refusal says the schema declares it.
		// It names the query alone, because mutations are off in this context and a
		// mutation then cannot reach any type.
		this.dynamicContextRunner.run((context) -> {
			ToolCallOutcome outcome = introspect(context, "Archive");

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).startsWith(
					"This schema declares 'Archive', and no query reaches it, so " + "these tools leave it out.");
		});

		this.dynamicContextRunner.withPropertyValues(INCLUDE_DEPRECATED).run((context) -> {
			ToolCallOutcome outcome = introspect(context, "Archive");

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).contains("type Archive {\n  id: ID!\n}");
		});
	}

	@Test
	void introspectType_aTypeNothingReaches_shouldSayTheSchemaDeclaresItAndNothingReachesIt() {
		this.dynamicContextRunner.run((context) -> {
			ToolCallOutcome outcome = introspect(context, "Orphan");

			assertThat(outcome.isError()).isTrue();
			assertThat(outcome.text()).isEqualTo("This schema declares 'Orphan', and no query reaches it, so these "
					+ "tools leave it out. Search for what you need: a coordinate names its type before the dot.");
		});
	}

	@Test
	void executeGraphql_aDeprecatedFieldWhileHidden_shouldStillPassValidation() {
		// The two schema tools hide the field; the execute tool validates against the
		// whole schema, so a document naming the field is accepted and, in validate-only
		// mode, returned as written.
		this.dynamicContextRunner.run((context) -> {
			ToolCallOutcome outcome = execute(context, "{ folder(name: \"docs\") { size } }");

			assertThat(outcome.isError()).isFalse();
			assertThat(outcome.text()).contains("{ folder(name: \\\"docs\\\") { size } }");
		});
	}

	@Test
	void bothSchemaTools_aDeprecatedArgument_shouldStayOutByDefaultAndCarryItsReasonWhenIncluded() {
		// A deprecated argument written bare reads as current, and a model passes it.
		this.dynamicContextRunner.run((context) -> {
			assertThat(introspect(context, "Query").text())
				.contains("  folder(name: String!, order: SortOrder = NAME, first: Int): Folder\n")
				.doesNotContain("limit");
			assertThat(search(context, "Looks up one folder by name").text())
				.contains("folder(name: String!, order: SortOrder, first: Int): Folder  # on type Query")
				.doesNotContain("limit");
		});

		this.dynamicContextRunner.withPropertyValues(INCLUDE_DEPRECATED).run((context) -> {
			assertThat(introspect(context, "Query").text())
				.contains("  folder(name: String!, order: SortOrder = NAME, limit: Int @deprecated(reason: "
						+ "\"Use first.\"), first: Int): Folder\n");
			assertThat(search(context, "Looks up one folder by name").text())
				.contains("folder(name: String!, order: SortOrder, limit: Int @deprecated(reason: \"Use first.\"), "
						+ "first: Int): Folder  # on type Query");
		});
	}

	@Test
	void introspectType_aDeprecatedEnumValue_shouldStayOutByDefaultAndCarryItsReasonWhenIncluded() {
		this.dynamicContextRunner.run((context) -> assertThat(introspect(context, "SortOrder").text())
			.isEqualTo("\"\"\"How folders are ordered.\"\"\"\nenum SortOrder {\n  NAME\n  CREATED\n}"));

		this.dynamicContextRunner.withPropertyValues(INCLUDE_DEPRECATED)
			.run((context) -> assertThat(introspect(context, "SortOrder").text())
				.isEqualTo("\"\"\"How folders are ordered.\"\"\"\nenum SortOrder {\n  NAME\n  CREATED\n"
						+ "  SIZE @deprecated(reason: \"Use CREATED.\")\n}"));
	}

	@Test
	void introspectType_aDeprecatedInputField_shouldStayOutByDefaultAndCarryItsReasonWhenIncluded() {
		this.dynamicContextRunner.run((context) -> assertThat(introspect(context, "FolderFilterInput").text())
			.isEqualTo("\"\"\"Narrows the folders a query returns.\"\"\"\ninput FolderFilterInput {\n"
					+ "  name: String\n}"));

		this.dynamicContextRunner.withPropertyValues(INCLUDE_DEPRECATED)
			.run((context) -> assertThat(introspect(context, "FolderFilterInput").text())
				.isEqualTo("\"\"\"Narrows the folders a query returns.\"\"\"\ninput FolderFilterInput {\n"
						+ "  name: String\n  owner: String @deprecated(reason: \"Use name.\")\n}"));
	}

	@Test
	void executeGraphql_aDeprecatedArgumentEnumValueAndInputField_shouldPassValidation() {
		this.dynamicContextRunner.run((context) -> {
			ToolCallOutcome byValue = execute(context, "{ folder(name: \"docs\", order: SIZE, limit: 1) { name } }");
			ToolCallOutcome byOwner = execute(context, "{ folders(filter: { owner: \"ana\" }) { name } }");

			assertThat(byValue.isError()).isFalse();
			assertThat(byOwner.isError()).isFalse();
		});
	}

	@Test
	void startup_operationFilesUsingDeprecatedValues_shouldWarnOncePerFileNamingTheReason(CapturedOutput output) {
		// Three operation files: one sends a deprecated argument, one a deprecated enum
		// value, one a deprecated input field. Each earns its own line naming what the
		// schema deprecated and the reason the schema gives.
		this.contextRunner.withPropertyValues("gatool.mcp.operations.locations=classpath:gatool/deprecations/")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).extracting((tool) -> tool.name())
					.containsExactlyInAnyOrder("foldersBySize", "foldersByOwner", "foldersLimited");
			});

		assertThat(output.getAll())
			.contains("FoldersLimited.graphql selects the argument limit of folder (Use first.), which the schema "
					+ "marks deprecated.")
			.contains("FoldersBySize.graphql selects the value SIZE of SortOrder (Use CREATED.), which the schema "
					+ "marks deprecated.")
			.contains("FoldersByOwner.graphql selects the input field owner of FolderFilterInput (Use name.), "
					+ "which the schema marks deprecated.");
	}

	private static ToolCallOutcome search(AssertableApplicationContext context, String question) {
		return call(context, "searchSchema", Map.of("question", question));
	}

	private static ToolCallOutcome introspect(AssertableApplicationContext context, String name) {
		return call(context, "introspectType", Map.of("name", name));
	}

	private static ToolCallOutcome execute(AssertableApplicationContext context, String document) {
		return call(context, "executeGraphql", Map.of("document", document));
	}

	private static ToolCallOutcome call(AssertableApplicationContext context, String name,
			Map<String, Object> arguments) {
		return context.getBean(GAToolCatalog.class)
			.mcpTools()
			.stream()
			.filter((tool) -> name.equals(tool.name()))
			.findFirst()
			.map((tool) -> tool.call(arguments))
			.orElseThrow(() -> new AssertionError("no tool named " + name));
	}

}
