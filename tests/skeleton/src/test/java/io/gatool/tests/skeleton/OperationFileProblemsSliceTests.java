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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.autoconfigure.OperationFileProblemsException;
import io.gatool.boot.mcp.autoconfigure.GAToolMcpAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every problem in an operation file, a fragment file, a location or a scalar fragment
 * that stops startup, each triggered through a real context and asserted on the line an
 * operator reads.
 *
 * <p>
 * Every one of them arrives as {@link OperationFileProblemsException}, so one restart
 * lists them all. The message names the file and says what to change, and each test
 * asserts that sentence.
 */
class OperationFileProblemsSliceTests {

	private static final String API_URL = "gatool.api.url=http://127.0.0.1:1/graphql";

	private static final String MOVIES_SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String NO_IN_PROCESS_FILES = "gatool.in-process.operations.locations=optional:classpath*:gatool/none/";

	private static final String SCALAR = "gatool.inputs.scalar-schemas.CountryCode.";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, GAToolAutoConfiguration.class,
				GAToolMcpAutoConfiguration.class))
		.withPropertyValues(API_URL, MOVIES_SCHEMA, NO_IN_PROCESS_FILES, "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true");

	@Test
	void startup_fileWithInvalidSyntax_shouldStopNamingTheFile(@TempDir Path folder) throws Exception {
		write(folder, "Broken.graphql", "query Broken { topRatedMovies { id ");

		problemsIn(folder).hasMessageContaining("Broken.graphql has invalid GraphQL syntax");
	}

	@Test
	void startup_fileHoldingATypeDefinition_shouldStopSayingWhatAFileHolds(@TempDir Path folder) throws Exception {
		write(folder, "Schema.graphql", "type Query { topRatedMovies: [Movie!]! }");

		problemsIn(folder).hasMessageContaining("Schema.graphql holds a type system definition, and operation "
				+ "files hold only an operation and fragments");
	}

	@Test
	void startup_fileHoldingTwoOperations_shouldStopSayingOneFileMakesOneTool(@TempDir Path folder) throws Exception {
		write(folder, "Two.graphql", """
				query First { topRatedMovies { id } }
				query Second { topRatedMovies { title } }
				""");

		problemsIn(folder).hasMessageContaining("Two.graphql holds 2 operations, and an operation file holds "
				+ "exactly one, because one file makes one tool");
	}

	@Test
	void startup_fileHoldingASubscription_shouldStopSayingAToolReturnsOneResult(@TempDir Path folder) throws Exception {
		write(folder, "Added.graphql", "subscription Added { reviewAdded(movieId: \"1\") { id } }");

		problemsIn(folder).hasMessageContaining("Added.graphql holds a subscription; a tool call returns a single "
				+ "result, so only a query or a mutation becomes a tool");
	}

	@Test
	void startup_fileUsingDefer_shouldStopSayingAToolReturnsOneResult(@TempDir Path folder) throws Exception {
		write(folder, "Deferred.graphql", """
				query Deferred { topRatedMovies { id ... @defer { title } } }
				""");

		problemsIn(folder).hasMessageContaining("Deferred.graphql uses @defer; a tool call returns a single result, "
				+ "so an operation that asks for its answer in pieces cannot become a tool");
	}

	@Test
	void startup_anonymousOperationInAFileNamedWithALeadingDigit_shouldStopAskingForAName(@TempDir Path folder)
			throws Exception {
		// A GraphQL name cannot start with a digit, so 2026Top fails the grammar and the
		// file name cannot name the operation.
		write(folder, "2026-top.graphql", "query { topRatedMovies { id } }");

		problemsIn(folder)
			.hasMessageContaining("2026-top.graphql holds an anonymous operation, and its file name "
					+ "does not yield a GraphQL name to take")
			.hasMessageContaining("Name the operation, or rename the file");
	}

	@Test
	void startup_directiveWrittenTwice_shouldStopSayingItBelongsOnTheOperationOnce(@TempDir Path folder)
			throws Exception {
		write(folder, "Twice.graphql", """
				query Twice @gatool(name: "a") @gatool(name: "b") { topRatedMovies { id } }
				""");

		problemsIn(folder).hasMessageContaining("Twice.graphql carries @gatool 2 times, and one file makes one "
				+ "tool, so the directive belongs on the operation once");
	}

	@Test
	void startup_directiveWithAnUnknownArgument_shouldStopListingTheArgumentsThisReleaseReads(@TempDir Path folder)
			throws Exception {
		write(folder, "Typo.graphql", """
				query Typo @gatool(nmae: "top") { topRatedMovies { id } }
				""");

		problemsIn(folder).hasMessageContaining("Typo.graphql sets @gatool(nmae:), and this release reads name, "
				+ "title, scopes, openWorld and outputSchema");
	}

	@Test
	void startup_booleanArgumentWrittenAsAString_shouldStopSayingTheArgumentsTakeLiterals(@TempDir Path folder)
			throws Exception {
		write(folder, "Open.graphql", """
				query Open @gatool(openWorld: "yes") { topRatedMovies { id } }
				""");

		problemsIn(folder).hasMessageContaining("Open.graphql sets @gatool(openWorld:) to something other than "
				+ "true or false, and the arguments of that directive take literal values");
	}

	@Test
	void startup_stringArgumentWrittenAsANumber_shouldStopSayingTheArgumentsTakeLiterals(@TempDir Path folder)
			throws Exception {
		write(folder, "Numbered.graphql", """
				query Numbered @gatool(name: 5) { topRatedMovies { id } }
				""");

		problemsIn(folder).hasMessageContaining("Numbered.graphql sets @gatool(name:) to something other than a "
				+ "string, and the arguments of that directive take literal values");
	}

	@Test
	void startup_operationSelectingAFieldTheSchemaLacks_shouldStopWithTheValidatorsLineAndColumn(@TempDir Path folder)
			throws Exception {
		write(folder, "Tagline.graphql", """
				query Tagline {
				  tagline
				}
				""");

		problemsIn(folder).hasMessageContaining("Tagline.graphql fails validation against the schema:")
			.hasMessageContaining("tagline")
			.hasMessageContaining("(line 2, column 3)");
	}

	@Test
	void startup_twoFilesGivingOneToolName_shouldStopNamingBothFiles(@TempDir Path folder) throws Exception {
		// Two operation names that differ only in case take one camel-case tool name, so
		// an MCP server would hold one of the two and the other would be unreachable.
		write(folder, "First.graphql", "query TopRated { topRatedMovies { id } }");
		write(folder, "Second.graphql", "query topRated { topRatedMovies { title } }");

		problemsIn(folder).hasMessageContaining("First.graphql shares the tool name 'topRated' with")
			.hasMessageContaining("Second.graphql among the MCP tools; rename one of the operations");
	}

	@Test
	void startup_directiveNameSharedWithAnotherFilesOperation_shouldStopNamingBothFiles(@TempDir Path folder)
			throws Exception {
		// @gatool(name:) wins over the strategy and faces the same clash rule.
		write(folder, "Named.graphql", "query Named @gatool(name: \"topRated\") { topRatedMovies { id } }");
		write(folder, "TopRated.graphql", "query TopRated { topRatedMovies { title } }");

		problemsIn(folder).hasMessageContaining("shares the tool name 'topRated' with")
			.hasMessageContaining("among the MCP tools; rename one of the operations");
	}

	@Test
	void startup_operationNamedWithSeparatorsAlone_shouldStopSayingItCannotBeNamed(@TempDir Path folder)
			throws Exception {
		// An underscore is a legal GraphQL name, and the camel-case strategy reads it as
		// a word break, so the tool name comes out empty.
		write(folder, "Underscore.graphql", "query _ { topRatedMovies { id } }");

		problemsIn(folder).hasMessageContaining("Underscore.graphql cannot be named as a tool: '_' needs at least "
				+ "one letter or digit to become a tool name");
	}

	@Test
	void startup_directiveNameOutsideThePortableSet_shouldStopNamingTheRule(@TempDir Path folder) throws Exception {
		write(folder, "Spaced.graphql", "query Spaced @gatool(name: \"top rated\") { topRatedMovies { id } }");

		problemsIn(folder).hasMessageContaining("Spaced.graphql gets the tool name 'top rated' for operation Spaced, "
				+ "and a tool name uses only letters, digits, '_' and '-', with at most 64 characters");
	}

	@Test
	void startup_sharedFragmentDefinedTwiceInOneFile_shouldStopSayingTwice(@TempDir Path folder) throws Exception {
		write(folder, "TopRated.graphql", "query TopRated { topRatedMovies { ...MovieCard } }");
		writeSharedFragment(folder, "Cards.graphql", """
				fragment MovieCard on Movie { id }
				fragment MovieCard on Movie { title }
				""");

		problemsIn(folder).hasMessageContaining(
				"Cards.graphql defines the fragment MovieCard twice among the MCP " + "tools' fragment files");
	}

	@Test
	void startup_fileOnBothSidesSpreadingAFragmentEachSideDefinesDifferently_shouldStopNamingBothDefinitions(
			@TempDir Path folder) throws Exception {
		// One operation file reached from both locations through a symbolic link counts
		// once and serves both exposure types. Each side's fragment folder defines
		// MovieCard its own way, so the file cannot take one of them.
		Path mcpFolder = Files.createDirectories(folder.resolve("mcp"));
		Path inProcessFolder = Files.createDirectories(folder.resolve("tools"));
		Path operation = mcpFolder.resolve("TopRated.graphql");
		Files.writeString(operation, "query TopRated { topRatedMovies { ...MovieCard } }");
		Files.createSymbolicLink(inProcessFolder.resolve("TopRated.graphql"), operation);
		writeSharedFragment(mcpFolder, "Cards.graphql", "fragment MovieCard on Movie { id }");
		writeSharedFragment(inProcessFolder, "Cards.graphql", "fragment MovieCard on Movie { title }");

		this.contextRunner
			.withPropertyValues("gatool.mcp.operations.locations=file:" + mcpFolder.toAbsolutePath() + "/",
					"gatool.in-process.operations.locations=file:" + inProcessFolder.toAbsolutePath() + "/")
			.run((context) -> failure(context.getStartupFailure())
				.hasMessageContaining("TopRated.graphql is published by both exposure types, and the fragment "
						+ "MovieCard is defined by")
				.hasMessageContaining("on one side and by")
				.hasMessageContaining("on the other; keep one of the two"));
	}

	@Test
	void startup_locationNamingASingleFile_shouldStopNamingTheFolderToUse() {
		this.contextRunner
			.withPropertyValues("gatool.mcp.operations.locations=classpath:gatool/mcp/TopRatedMovies.graphql")
			.run((context) -> failure(context.getStartupFailure())
				.hasMessageContaining("classpath:gatool/mcp/TopRatedMovies.graphql names a single file, and a "
						+ "location names a folder; use classpath:gatool/mcp/"));
	}

	@Test
	void startup_requiredLocationHoldingNoOperationFile_shouldStopNamingTheOptionalPrefix() {
		this.contextRunner.withPropertyValues("gatool.mcp.operations.locations=classpath*:gatool/none/")
			.run((context) -> failure(context.getStartupFailure())
				.hasMessageContaining("classpath*:gatool/none/ holds zero .graphql or .gql files; add an operation "
						+ "file, or start the location with optional: when the folder may stay empty"));
	}

	@Test
	void startup_scalarFragmentNamingASpecifiedScalar_shouldStopSayingGAToolMapsThoseItself(@TempDir Path folder) {
		scalarProblem(folder, "gatool.inputs.scalar-schemas.ID.type=string")
			.hasMessageContaining("The property gatool.inputs.scalar-schemas.ID names a scalar the GraphQL "
					+ "specification defines, and GATool maps those itself");
	}

	@Test
	void startup_scalarFragmentLongerThanTheLimit_shouldStopSayingWhereTheFragmentTravels(@TempDir Path folder) {
		// Two hundred enum values of twelve characters each pass the enum limit of 250
		// and push the whole fragment past 2048 characters.
		String[] values = IntStream.range(0, 200)
			.mapToObj((index) -> SCALAR + "enum[" + index + "]=" + String.format("value%07d", index))
			.toArray(String[]::new);

		scalarProblem(folder, values)
			.hasMessageContaining("The property gatool.inputs.scalar-schemas.CountryCode is longer than 2048 "
					+ "characters, and the schema travels in every tools/list");
	}

	@Test
	void startup_scalarFragmentsWithEveryRefusedKeywordShape_shouldStopNamingEachRule(@TempDir Path folder) {
		// One table for the closed keyword set, so a rule that stops firing fails the
		// case that names it. Every case reads the same scalar, and every message
		// opens with the property that carries the fragment.
		Map<String, String[]> cases = new LinkedHashMap<>();
		cases.put("takes one of boolean, integer, number, string for type, and this one is \"object\"",
				properties("type=object"));
		cases.put("takes one of date, date-time, duration, email, hostname, ipv4, ipv6, time, uri, uuid for format, "
				+ "and this one is \"phone\"", properties("type=string", "format=phone"));
		cases.put("carries format without type: string, and every format JSON Schema defines applies to a string",
				properties("type=integer", "format=date-time"));
		cases.put("takes a regular expression for pattern, and this one is an array",
				properties("type=string", "pattern[0]=^a$"));
		cases.put("has a pattern longer than 200 characters",
				properties("type=string", "pattern=^" + "a".repeat(200) + "$"));
		cases.put("has a pattern that does not compile", properties("type=string", "pattern=^[$"));
		cases.put("has a pattern using lookaround, a backreference, a named or atomic group, a possessive "
				+ "quantifier or a word boundary", properties("type=string", "pattern=^(?=a).*$"));
		cases.put("carries pattern without type: string, and pattern applies to a string alone",
				properties("type=integer", "pattern=^[0-9]+$"));
		cases.put("takes a non-empty array for enum, and this one is \"SI\"", properties("type=string", "enum=SI"));
		cases.put("has an enum of 251 values, and the limit is 250", enumOfSize(251));
		cases.put("has an enum holding an object, and a member is a string, a number or a boolean",
				properties("type=string", "enum[0].code=SI"));
		cases.put("has an enum listing \"SI\" twice", properties("type=string", "enum[0]=SI", "enum[1]=SI"));
		cases.put("has an enum holding \"SI\" beside type integer, so no value satisfies both",
				properties("type=integer", "enum[0]=SI"));
		cases.put("takes a non-empty string for description, and this one is \"\"",
				properties("type=string", "description="));
		cases.put("has a description longer than 1024 characters",
				properties("type=string", "description=" + "d".repeat(1025)));
		cases.put("carries a description inside an anyOf branch",
				properties("anyOf[0].type=string", "anyOf[0].description=inside", "anyOf[1].type=integer"));
		cases.put("nests anyOf inside an anyOf branch, and GATool writes one level",
				properties("anyOf[0].anyOf[0].type=string", "anyOf[1].type=integer"));
		cases.put("carries type beside anyOf, and the branches are what GATool publishes, so those would be dropped",
				properties("type=string", "anyOf[0].type=string", "anyOf[1].type=integer"));
		cases.put("takes an array of 2 to 8 branches for anyOf, and this one is an array",
				properties("anyOf[0].type=string"));
		cases.put("has an anyOf branch that is \"string\", and a branch is a JSON Schema object",
				properties("anyOf[0]=string", "anyOf[1]=integer"));

		cases.forEach((expected, values) -> scalarProblem(folder, values).as(expected)
			.hasMessageContaining("The property gatool.inputs.scalar-schemas.CountryCode " + expected));
	}

	private AbstractThrowableAssert<?, ? extends Throwable> scalarProblem(Path folder, String... properties) {
		Throwable[] failure = new Throwable[1];
		this.contextRunner
			.withPropertyValues("gatool.mcp.operations.locations=optional:file:" + folder.toAbsolutePath() + "/")
			.withPropertyValues(properties)
			.run((context) -> failure[0] = context.getStartupFailure());
		return failure(failure[0]);
	}

	private static String[] properties(String... fragmentKeys) {
		String[] values = new String[fragmentKeys.length];
		for (int index = 0; index < fragmentKeys.length; index++) {
			values[index] = SCALAR + fragmentKeys[index];
		}
		return values;
	}

	private static String[] enumOfSize(int size) {
		String[] values = new String[size + 1];
		values[0] = SCALAR + "type=string";
		for (int index = 0; index < size; index++) {
			values[index + 1] = SCALAR + "enum[" + index + "]=v" + index;
		}
		return values;
	}

	private AbstractThrowableAssert<?, ? extends Throwable> problemsIn(Path folder) {
		Throwable[] failure = new Throwable[1];
		this.contextRunner.withPropertyValues("gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/")
			.run((context) -> failure[0] = context.getStartupFailure());
		return failure(failure[0]);
	}

	// The exception carries every problem as one line each, and Boot wraps it in the bean
	// creation failure, so the root cause is the one to read.
	private static AbstractThrowableAssert<?, ? extends Throwable> failure(Throwable startupFailure) {
		assertThat(startupFailure).as("startup stopped").isNotNull();
		return assertThat(startupFailure).rootCause().isInstanceOf(OperationFileProblemsException.class);
	}

	private static void write(Path folder, String name, String text) throws Exception {
		Files.writeString(folder.resolve(name), text);
	}

	private static void writeSharedFragment(Path folder, String name, String text) throws Exception {
		Files.writeString(Files.createDirectories(folder.resolve("fragments")).resolve(name), text);
	}

}
