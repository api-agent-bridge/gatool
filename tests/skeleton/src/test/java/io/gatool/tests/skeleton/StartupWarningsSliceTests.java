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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.inprocess.autoconfigure.GAToolInProcessAutoConfiguration;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every warning startup writes about the operation files, the schema and the tool beans,
 * each triggered through a real context and asserted on the sentence an operator reads.
 *
 * <p>
 * A warning keeps the application running, so each test asserts that the context started
 * and that the log carries the sentence. The stops that refuse startup are in
 * {@link OperationFileProblemsSliceTests} and {@link StartupSettingsSliceTests}.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupWarningsSliceTests {

	// Startup builds the client, and the API is first reached inside a tool call, so a
	// closed port serves every test in this class.
	private static final String API_URL = "gatool.api.url=http://127.0.0.1:1/graphql";

	private static final String MOVIES_SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	// This module ships an operation file under the default in-process location, and
	// most tests here want the MCP folder alone.
	private static final String NO_IN_PROCESS_FILES = "gatool.in-process.operations.locations=optional:classpath*:gatool/none/";

	private static final String STATELESS_PROTOCOL = "spring.ai.mcp.server.protocol=STATELESS";

	private static final String UNSAFE_SWITCH_ON = "gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(API_URL, MOVIES_SCHEMA, NO_IN_PROCESS_FILES, STATELESS_PROTOCOL, UNSAFE_SWITCH_ON);

	@Test
	void startup_operationWithoutACommentAndWithTwoRootFields_shouldWarnThatTheToolLacksADescription(
			@TempDir Path folder, CapturedOutput output) throws Exception {
		// The schema description is read only where the operation selects exactly one
		// root field, so a file that selects two and leaves the # lines out is left
		// without a description.
		write(folder, "TwoLists.graphql", """
				query TwoLists {
				  topRatedMovies { id }
				  search(text: "x") { __typename }
				}
				""");

		runOn(folder).run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("TwoLists.graphql")
			.contains("lacks a description; write # lines directly above the operation")
			.contains("agents choose tools by their descriptions");
	}

	@Test
	void startup_operationSelectingADeprecatedField_shouldWarnWithTheReasonTheSchemaGives(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		write(folder, "AllMovies.graphql", """
				# Lists every movie.
				query AllMovies { allMovies { id } }
				""");

		runOn(folder).run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("AllMovies.graphql")
			.contains("selects the field Query.allMovies (Unbounded. Use movies, which pages its results.)")
			.contains("which the schema marks deprecated");
	}

	@Test
	void startup_operationUsingACustomScalarWithoutAMapping_shouldWarnNamingTheScalarAndTheTool(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// CountryCode lacks a @specifiedBy page and a fragment under
		// gatool.inputs.scalar-schemas, so the model reads it as any JSON value.
		write(folder, "ByCountry.graphql", """
				# Reviews of movies directed in one country.
				query ByCountry($country: CountryCode) { reviews(country: $country) { id } }
				""");

		runOn(folder).run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("declares custom scalars that GATool describes to the model as any JSON value: "
					+ "CountryCode (used by byCountry)")
			.contains("gatool.inputs.scalar-schemas.<Name>");
	}

	@Test
	void startup_variableDefaultFailingTheConfiguredScalarSchema_shouldWarnAndLeaveTheDefaultOut(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// The fragment says CountryCode is a string, and the file declares a number, so
		// a model that read the default and sent it back would be refused before the
		// call reached the API.
		write(folder, "ByCountry.graphql", """
				# Reviews of movies directed in one country.
				query ByCountry($country: CountryCode = 5) { reviews(country: $country) { id } }
				""");

		runOn(folder).withPropertyValues("gatool.inputs.scalar-schemas.CountryCode.type=string")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("ByCountry.graphql")
			.contains("declares country = 5, and that value fails the schema GATool publishes for the argument, "
					+ "so the default is left out")
			.contains("gatool.inputs.scalar-schemas.<Name>");
	}

	@Test
	void startup_unionSelectedWithoutTypenameWhileTheOutputSchemaIsOn_shouldWarnToSelectIt(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// The output schema names the members by __typename, so a result without it
		// leaves a model unable to tell a Movie from a Person.
		write(folder, "Search.graphql", """
				# Finds movies and people by text.
				query Search($text: String!) {
				  search(text: $text) {
				    ... on Movie { id }
				    ... on Person { name }
				  }
				}
				""");

		runOn(folder).withPropertyValues("gatool.results.publish-output-schema=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("Search.graphql")
			.contains("selects the union or interface at 'search' without __typename")
			.contains("select __typename beside the fragments");
	}

	@Test
	void startup_toolCostingMoreThanFourThousandTokens_shouldWarnNamingTheToolAndTheCost(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// Four characters make a token, so three hundred lines of sixty characters cost
		// about four and a half thousand tokens before any call.
		String description = String.join("\n", Collections.nCopies(300, "# " + "x".repeat(60)));
		write(folder, "Wordy.graphql", description + "\nquery Wordy { topRatedMovies { id } }\n");

		runOn(folder).run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.containsPattern("Wordy\\.graphql becomes the tool wordy and costs about \\d+ tokens")
			.contains("which is a large share of a model's context before any call");
	}

	@Test
	void startup_documentAboveTheRequestLimitsTheApplicationSet_shouldWarnNamingTheProperty(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// The API applies limits of its own to a request, and GATool cannot read them
		// from it, so the application states them. A document above one is served, and
		// startup says which property it passed.
		write(folder, "TopRated.graphql",
				"# Returns the highest-rated movies.\nquery TopRated { topRatedMovies { id title rating } }\n");

		runOn(folder).withPropertyValues("gatool.api.request-limits.max-tokens=5")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.containsPattern("TopRated\\.graphql becomes the tool topRated and sends a document of more than 5 "
					+ "tokens, which passes gatool\\.api\\.request-limits\\.max-tokens");
	}

	@Test
	void startup_responseCapBelowTheResultLimit_shouldWarn(CapturedOutput output) {
		// A result of 60,000 characters needs at least 60,000 bytes from the API, so a
		// cap of 50KB refuses every result the result limit would have allowed, and the
		// operator reads which of the two properties decides.
		this.contextRunner
			.withPropertyValues("gatool.api.max-response-size=50KB", "gatool.results.max-characters=60000")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("gatool.api.max-response-size is 50KB")
			.contains("gatool.results.max-characters is 60000")
			.contains("the response cap decides every refusal");
	}

	@Test
	void startup_responseCapAboveTheResultLimit_shouldStayQuietAboutTheTwoLimits(CapturedOutput output) {
		this.contextRunner.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).doesNotContain("the response cap decides every refusal");
	}

	@Test
	void startup_documentWithinTheDefaultRequestLimits_shouldStayQuietAboutItsSize(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		write(folder, "TopRated.graphql",
				"# Returns the highest-rated movies.\nquery TopRated { topRatedMovies { id title rating } }\n");

		runOn(folder).run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).doesNotContain("gatool.api.request-limits");
	}

	@Test
	void startup_largeToolServedOnBothSides_shouldWarnOnceWithTheCostTheListingPrints(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		String description = String.join("\n", Collections.nCopies(300, "# " + "x".repeat(60)));
		write(folder, "Wordy.graphql", description + "\nquery Wordy { topRatedMovies { id } }\n");

		runOn(folder)
			.withPropertyValues("gatool.in-process.operations.locations=optional:file:" + folder.toAbsolutePath() + "/")
			.run((context) -> assertThat(context).hasNotFailed());

		// The catalog raises the warning once for the file, and startup logs what the
		// catalog raised, so a file on both sides is warned about once.
		assertThat(output.getAll()).containsOnlyOnce("costs about");
		// The warning names the exposure type its number is for, which is MCP for a
		// file both sides reach, and the listing of that exposure type prints the same
		// number for the tool.
		Matcher warned = Pattern.compile("costs about (\\d+) tokens as an MCP tool").matcher(output.getAll());
		assertThat(warned.find()).isTrue();
		assertThat(Integer.parseInt(warned.group(1))).isEqualTo(listedTokens(output, "MCP", "wordy"));
	}

	@Test
	void startup_largeToolServedInProcessAlone_shouldWarnWithTheCostTheInProcessListingPrints(@TempDir Path folder,
			@TempDir Path empty, CapturedOutput output) throws Exception {
		String description = String.join("\n", Collections.nCopies(300, "# " + "x".repeat(60)));
		write(folder, "Wordy.graphql", description + "\nquery Wordy { topRatedMovies { id } }\n");

		runOn(empty)
			.withPropertyValues("gatool.in-process.operations.locations=optional:file:" + folder.toAbsolutePath() + "/")
			.run((context) -> assertThat(context).hasNotFailed());

		Matcher warned = Pattern.compile("costs about (\\d+) tokens as an in-process tool").matcher(output.getAll());
		assertThat(warned.find()).isTrue();
		assertThat(Integer.parseInt(warned.group(1))).isEqualTo(listedTokens(output, "in-process", "wordy"));
	}

	@Test
	void startup_toolServedOnBothSides_shouldListForEachSideWhatThatSideSends(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// tools/list carries the title, the output schema and a $id inside each schema. A
		// model provider reached in process is sent the name, the description and the
		// input schema as the writer produced it. Each listing prints the number for what
		// its side sends, so the MCP line counts the output schema and both $id keywords.
		write(folder, "TopRated.graphql", """
				# Returns the highest-rated movies.
				query TopRated($first: Int = 3) @gatool(title: "Top rated movies") {
				  topRatedMovies(first: $first) { id title rating }
				}
				""");
		AtomicReference<McpSchema.Tool> published = new AtomicReference<>();
		AtomicReference<GATool> inProcess = new AtomicReference<>();

		runOn(folder)
			.withPropertyValues("gatool.in-process.operations.locations=optional:file:" + folder.toAbsolutePath() + "/",
					"gatool.results.publish-output-schema=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				published.set(publishedTools(context).getFirst());
				inProcess.set(context.getBean(GAToolCatalog.class).inProcessTools().getFirst());
			});

		McpSchema.Tool tool = published.get();
		int charactersOverMcp = tool.name().length() + "Top rated movies".length() + tool.description().length()
				+ JSON.writeValueAsString(tool.inputSchema()).length()
				+ JSON.writeValueAsString(tool.outputSchema()).length();
		int charactersInProcess = inProcess.get().name().length() + tool.description().length()
				+ inProcess.get().inputSchema().length();
		assertThat(tool.inputSchema()).containsKey("$id");
		assertThat(tool.outputSchema()).containsKey("$id");
		assertThat(listedTokens(output, "MCP", "topRated")).isEqualTo(charactersOverMcp / 4);
		assertThat(listedTokens(output, "in-process", "topRated")).isEqualTo(charactersInProcess / 4);
	}

	@Test
	void startup_toolWithoutAnOutputSchema_shouldCountTheIdOfItsInputSchemaInTheMcpListing(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// The MCP adapter names each schema with a $id of 98 characters, which tools/list
		// carries and the writer's text lacks. The in-process side sends the writer's
		// text, so the two listings of one tool differ by that keyword.
		write(folder, "TopRated.graphql", """
				# Returns the highest-rated movies.
				query TopRated($first: Int = 3) {
				  topRatedMovies(first: $first) { id title rating }
				}
				""");
		AtomicReference<McpSchema.Tool> published = new AtomicReference<>();
		AtomicReference<GATool> inProcess = new AtomicReference<>();

		runOn(folder)
			.withPropertyValues("gatool.in-process.operations.locations=optional:file:" + folder.toAbsolutePath() + "/")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				published.set(publishedTools(context).getFirst());
				inProcess.set(context.getBean(GAToolCatalog.class).inProcessTools().getFirst());
			});

		McpSchema.Tool tool = published.get();
		String publishedSchema = JSON.writeValueAsString(tool.inputSchema());
		int charactersInProcess = tool.name().length() + tool.description().length()
				+ inProcess.get().inputSchema().length();
		assertThat(publishedSchema).hasSize(inProcess.get().inputSchema().length() + 98);
		assertThat(listedTokens(output, "in-process", "topRated")).isEqualTo(charactersInProcess / 4);
		assertThat(listedTokens(output, "MCP", "topRated")).isEqualTo((charactersInProcess + 98) / 4);
	}

	@Test
	void startup_toolWhoseOutputSchemaTakesItPastFourThousandTokens_shouldWarnNamingTheOutputSchema(
			@TempDir Path folder, CapturedOutput output) throws Exception {
		// Three hundred aliases of one field make an output schema of about five thousand
		// tokens under a name, a description and an input schema of about forty.
		// tools/list carries that schema, so the estimate counts it and the warning names
		// it.
		write(folder, "Wide.graphql",
				"# Returns the highest-rated movies.\nquery Wide { topRatedMovies { " + aliases(300) + " } }\n");

		runOn(folder).withPropertyValues("gatool.results.publish-output-schema=true")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.containsPattern("Wide\\.graphql becomes the tool wide and costs about \\d+ tokens as an MCP tool")
			.containsPattern("Its output schema is about \\d+ of them");
		Matcher warned = Pattern.compile("costs about (\\d+) tokens").matcher(output.getAll());
		assertThat(warned.find()).isTrue();
		assertThat(Integer.parseInt(warned.group(1))).isEqualTo(listedTokens(output, "MCP", "wide"));
	}

	@Test
	void startup_jacksonConfiguredToDropNulls_shouldSayThatGAToolWritesVariablesWithAMapperOfItsOwn(
			CapturedOutput output) {
		// spring.jackson.default-property-inclusion reaches Boot's mapper, and a null
		// variable clears a value in GraphQL, so GATool copies the mapper and keeps the
		// null.
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues(API_URL, MOVIES_SCHEMA, NO_IN_PROCESS_FILES,
					"spring.jackson.default-property-inclusion=non_null")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("GATool writes the variables it sends with a mapper of its own")
			.contains("spring.jackson.default-property-inclusion")
			.contains("Every other HTTP client in this application keeps the mapper it had");
	}

	@Test
	void startup_generateToolsPerRootField_shouldWarnThatTheWholeRootReachesAModel(@TempDir Path folder,
			CapturedOutput output) {
		runOn(folder).withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("gatool.dev.experimental.generate-tools is ALL_ROOT_QUERIES, so every query root field")
			.contains("becomes a tool, expanded 1 level deep")
			.contains("That publishes the whole root of your API to a model")
			.contains("turn this off before you deploy");
	}

	@Test
	void startup_generatedRootFieldWithNothingSelectableAtDepthOne_shouldWarnNamingTheDepthThatReachesIt(
			@TempDir Path folder, CapturedOutput output) {
		// Query.movies returns MovieConnection, whose fields are objects, so a depth of
		// one leaves the selection empty and the warning says which depth would fill it.
		runOn(folder).withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("The schema has the root field 'movies', which GATool leaves without a generated tool")
			.contains("nothing under it can be selected within a depth of 1")
			.contains("A depth of 5 reaches it, or write an operation file for it");
	}

	@Test
	void startup_generatedToolSharingAWrittenToolsName_shouldWarnThatTheGeneratedOneIsLeftOut(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// The file selects Query.movie under the name movies, which the root field movies
		// would generate. The written file wins, and the log says which root field lost.
		write(folder, "Movies.graphql", """
				# One movie by id.
				query Movies { movie(by: {id: "1"}) { id } }
				""");

		runOn(folder)
			.withPropertyValues("gatool.dev.experimental.generate-tools=all-root-queries",
					"gatool.dev.experimental.generated-selection-depth=2")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("generated:query/movies is left out, because an operation file already publishes that "
					+ "name: 'movies'")
			.contains("@gatool(name:)");
	}

	@Test
	void startup_generatedRootFieldWhoseNameIsTooLongForATool_shouldWarnThatItIsLeftOut(@TempDir Path folder,
			CapturedOutput output) throws Exception {
		// A tool name stops at 64 characters, and this root field is 65 long, so the
		// generator cannot publish it and the warning asks for an operation file.
		String longName = "a".repeat(65);
		Files.writeString(folder.resolve("long.graphqls"), "type Query { " + longName + ": String  short: String }");

		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class))
			.withPropertyValues(API_URL, NO_IN_PROCESS_FILES,
					"gatool.api.schema.location=file:" + folder.toAbsolutePath() + "/long.graphqls",
					"gatool.mcp.operations.locations=optional:file:" + folder.toAbsolutePath() + "/",
					"gatool.dev.experimental.generate-tools=all-root-queries")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("generated:query/" + longName + " is left out, because its root field does not take a "
					+ "tool name GATool can publish")
			.contains("@gatool(name:)");
	}

	@Test
	void startup_scalarFragmentPublishingFormatUri_shouldWarnThatOpenAiRefusesIt(@TempDir Path folder,
			CapturedOutput output) {
		runOn(folder)
			.withPropertyValues("gatool.inputs.scalar-schemas.CountryCode.type=string",
					"gatool.inputs.scalar-schemas.CountryCode.format=uri")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("The property gatool.inputs.scalar-schemas.CountryCode publishes format: uri")
			.contains("OpenAI's strict subset leaves it out, which refuses the whole tool");
	}

	@Test
	void startup_scalarFragmentWithAnUnanchoredPattern_shouldWarnThatItMatchesAnywhere(@TempDir Path folder,
			CapturedOutput output) {
		runOn(folder)
			.withPropertyValues("gatool.inputs.scalar-schemas.CountryCode.type=string",
					"gatool.inputs.scalar-schemas.CountryCode.pattern=[A-Z]{2}")
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("The property gatool.inputs.scalar-schemas.CountryCode publishes a pattern that lacks ^ "
					+ "at its start or $ at its end")
			.contains("accepts any value holding a match");
	}

	@Test
	void startup_toolCallbackProviderThatFailsToListItsTools_shouldWarnAndKeepStarting(CapturedOutput output) {
		// Spring AI's MCP client provider lists its tools on a remote server, so a
		// provider that fails stays out of the clash check and the log names it.
		ToolCallbackProvider failing = () -> {
			throw new IllegalStateException("the remote server is down");
		};

		new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
							GAToolAutoConfiguration.class, GAToolInProcessAutoConfiguration.class))
			.withPropertyValues(API_URL, MOVIES_SCHEMA)
			.withBean("failingProvider", ToolCallbackProvider.class, () -> failing)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll()).contains("stayed out of the clash check, because listing its tools failed")
			.contains("the remote server is down");
	}

	@Test
	void startup_applicationToolNamedOutsideThePortableSet_shouldWarnNamingTheToolAndItsSource(CapturedOutput output) {
		// The MCP Java SDK accepts a dot in a tool name, and GATool keeps to the narrower
		// set every client reads, so the application's own name earns a warning.
		ToolCallback dotted = FunctionToolCallback.builder("weather.today", (String input) -> "sunny")
			.description("The weather.")
			.inputType(String.class)
			.build();

		new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
					RestClientAutoConfiguration.class, GAToolAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
					StatelessServerSpecificationFactoryAutoConfiguration.class,
					StatelessToolCallbackConverterAutoConfiguration.class, GAToolMcpAutoConfiguration.class))
			.withPropertyValues(API_URL, MOVIES_SCHEMA, STATELESS_PROTOCOL, UNSAFE_SWITCH_ON)
			.withBean("dottedCallback", ToolCallback.class, () -> dotted)
			.run((context) -> assertThat(context).hasNotFailed());

		assertThat(output.getAll())
			.contains("The tool weather.today, served by a ToolCallback bean, uses a name outside the portable set")
			.contains("at most 64 characters");
	}

	// The tools as tools/list publishes them, read from the specifications GATool hands
	// to Spring AI's server.
	@SuppressWarnings("unchecked")
	private static List<McpSchema.Tool> publishedTools(ApplicationContext context) {
		String name = context.getBeanNamesForType(ResolvableType.forClassWithGenerics(List.class,
				McpStatelessServerFeatures.SyncToolSpecification.class))[0];
		return ((List<McpStatelessServerFeatures.SyncToolSpecification>) context.getBean(name)).stream()
			.map(McpStatelessServerFeatures.SyncToolSpecification::tool)
			.toList();
	}

	// The number one listing prints for one tool. The listing of an exposure type opens
	// with "GATool serves", so the text is cut there and the line is read from the
	// part that names the exposure type.
	private static int listedTokens(CapturedOutput output, String exposureTypeLabel, String toolName) {
		for (String listing : output.getAll().split("GATool serves ")) {
			if (listing.matches("(?s)\\d+ " + Pattern.quote(exposureTypeLabel) + " tools?:.*")) {
				Matcher listed = Pattern
					.compile("  " + Pattern.quote(toolName) + " from [^\\n]+ \\(about (\\d+) tokens\\)")
					.matcher(listing);
				assertThat(listed.find()).as("the " + exposureTypeLabel + " listing names " + toolName).isTrue();
				return Integer.parseInt(listed.group(1));
			}
		}
		throw new AssertionError("The log lacks the " + exposureTypeLabel + " listing: " + output.getAll());
	}

	private static String aliases(int count) {
		StringBuilder selections = new StringBuilder();
		for (int index = 0; index < count; index++) {
			selections.append("title").append(index).append(": title ");
		}
		return selections.toString();
	}

	private ApplicationContextRunner runOn(Path folder) {
		return this.contextRunner
			.withPropertyValues("gatool.mcp.operations.locations=optional:file:" + folder.toAbsolutePath() + "/");
	}

	private static void write(Path folder, String name, String text) throws Exception {
		Files.writeString(folder.resolve(name), text);
	}

	private static void writeSharedFragment(Path folder, String name, String text) throws Exception {
		Files.writeString(Files.createDirectories(folder.resolve("fragments")).resolve(name), text);
	}

}
