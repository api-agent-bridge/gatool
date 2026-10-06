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

package io.gatool.core.check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import graphql.schema.GraphQLSchema;

import io.gatool.core.internal.operation.OperationCatalog;
import io.gatool.core.internal.operation.OperationCatalogFactory;
import io.gatool.core.internal.operation.OperationSource;
import io.gatool.core.internal.operation.SchemaAddition;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.schema.SdlSchemaFactory;

/**
 * Runs the startup checks over an application's operation files from a plain JUnit test.
 *
 * <p>
 * A test that calls {@link #check} makes a broken operation file fail the build that
 * changed it, instead of the deployment that follows. The check needs an SDL file and the
 * folders, so it runs without an issuer, a running application or a reachable schema URL.
 *
 * <p>
 * The check covers what startup checks once the schema and the files are in hand. Each
 * operation parses and validates against the schema, and the tool names follow the naming
 * strategy and stay apart. The input schema and the output schema are written with the
 * scalar fragments the settings carry for each of the two, and the warnings startup would
 * log are collected. The folders are read from disk, so the resolution of
 * {@code classpath*:} locations and jars stays with startup, and a setting the check
 * cannot see stays with {@link CheckSettings}.
 *
 * <p>
 * Spring stays out of this module, so the folders are walked with {@link Files} instead
 * of Spring's resource resolver. A folder that a test names is a directory on disk, where
 * an application also reads from the classpath and from jars. A listed folder that is
 * missing is a problem, because startup stops on a required location that is empty, and a
 * check that skipped the folder would pass on an application that cannot start.
 *
 * @author Željko Kozina
 */
public final class OperationFileCheck {

	// The running starter takes this pair from Spring for GraphQL's public
	// ResourceDocumentSource.FILE_EXTENSIONS. This module bans org.springframework, and
	// the enforcer keeps that ban, so the two extensions are written out here. A test in
	// gatool-spring-boot compares the two lists, so they stay in step.
	static final List<String> EXTENSIONS = List.of(".graphql", ".gql");

	// The running starter's MCP adapter names each schema with a $id keyword built from a
	// SHA-256 digest before tools/list sends it, and the warning about a large tool
	// counts those characters against the estimate. This module bans the MCP Java SDK,
	// and the enforcer keeps that ban, so the number is written out here. A test in
	// gatool-mcp-spring-boot compares it with the adapter's own constant, so they stay in
	// step.
	static final int MCP_SCHEMA_ID_CHARACTERS = 98;

	private OperationFileCheck() {
	}

	/**
	 * Checks the operation files of both exposure types against a schema file, with the
	 * settings the application uses.
	 *
	 * <p>
	 * This is the overload for an application that sets
	 * {@code gatool.results.publish-output-schema}, {@code gatool.inputs.scalar-schemas}
	 * or {@code gatool.results.scalar-schemas}, because the schema built from the file
	 * here is wired the way startup wires it, and the settings reach the writers the way
	 * the properties reach them. The overload that takes a built {@link GraphQLSchema}
	 * serves a test that already holds one.
	 * @param schemaFile the schema definition language file of the GraphQL API
	 * @param mcpFolders the folders that hold the MCP operation files
	 * @param inProcessFolders the folders that hold the in-process operation files
	 * @param settings the application settings this check has to match
	 * @return the result, which carries every problem, every warning and the tools that
	 * would be served; a schema file that is not a valid schema is reported as a problem
	 * @throws UncheckedIOException if a folder or a file cannot be read
	 */
	public static CheckResult check(Path schemaFile, List<Path> mcpFolders, List<Path> inProcessFolders,
			CheckSettings settings) {
		GraphQLSchema schema;
		try {
			schema = SdlSchemaFactory.schemaFrom(read(schemaFile), schemaFile.toString());
		}
		catch (IllegalStateException ex) {
			// Startup stops on a schema file it cannot build, so the check reports it the
			// way it reports a broken operation file, in the message that names the file
			// and every schema error. Letting the exception escape would make a test
			// print a stack trace where the other stops print a list.
			return new CheckResult(List.of(), List.of(String.valueOf(ex.getMessage())), List.of());
		}
		return check(schema, mcpFolders, inProcessFolders, settings);
	}

	/**
	 * Checks the operation files against the schema, with the settings the application
	 * uses.
	 *
	 * <p>
	 * Every setting that changes the published schema belongs in {@link CheckSettings},
	 * because one the check cannot see makes it report a different input schema and a
	 * different set of warnings from the application it guards.
	 * @param schema the schema to validate against
	 * @param mcpFolders the folders holding the MCP operation files
	 * @param inProcessFolders the folders holding the in-process operation files
	 * @param settings the application settings this check has to match
	 * @return the result, which carries every problem, every warning and the tools that
	 * would be served
	 * @throws UncheckedIOException if a folder or a file cannot be read
	 */
	public static CheckResult check(GraphQLSchema schema, List<Path> mcpFolders, List<Path> inProcessFolders,
			CheckSettings settings) {
		Map<ToolExposureType, List<Path>> foldersByToolExposureType = new LinkedHashMap<>();
		foldersByToolExposureType.put(ToolExposureType.MCP, mcpFolders);
		foldersByToolExposureType.put(ToolExposureType.IN_PROCESS, inProcessFolders);
		List<String> problems = new ArrayList<>();
		// Startup passes the MCP adapter's own SchemaAddition bean, and the check cannot
		// reach that adapter, so it passes the same number under its own name. Every tool
		// MCP serves therefore costs what startup's estimate says it costs, and a tool
		// the check passes without a warning is one startup passes without a warning too.
		OperationCatalog catalog = OperationCatalogFactory.builder(schema, settings.namingStrategy())
			.outputSchemaByDefault(settings.publishesOutputSchema())
			.scalarSchemas(settings.scalarSchemas())
			.resultScalarSchemas(settings.resultScalarSchemas())
			.requestLimits(settings.requestLimits())
			.schemaAdditions(List.of(new SchemaAddition(ToolExposureType.MCP, MCP_SCHEMA_ID_CHARACTERS)))
			.build()
			.create(sources(foldersByToolExposureType, problems));
		catalog.problems().stream().map(Object::toString).forEach(problems::add);
		return new CheckResult(catalog.tools().stream().map(CheckedTool::of).toList(), problems,
				catalog.warnings().stream().map(Object::toString).toList());
	}

	// Returns one source per operation file found under the folders, each carrying the
	// exposure types that reach it, and adds a problem for each listed folder that is not
	// a directory.
	//
	// One file that both exposure types reach loads once and keeps both, which is the
	// rule the starter follows at startup, so a test sees the same tool name clash
	// checks.
	private static List<OperationSource> sources(Map<ToolExposureType, List<Path>> foldersByToolExposureType,
			List<String> problems) {
		Map<Path, Found> files = new LinkedHashMap<>();
		foldersByToolExposureType.forEach((toolExposureType, folders) -> folders
			.forEach((folder) -> collect(toolExposureType, folder, files, problems)));
		List<OperationSource> sources = new ArrayList<>();
		files.forEach((path, found) -> sources
			.add(new OperationSource(path.toUri().toString(), read(path), found.toolExposureTypes())));
		return sources;
	}

	private static void collect(ToolExposureType toolExposureType, Path folder, Map<Path, Found> files,
			List<String> problems) {
		if (!Files.isDirectory(folder)) {
			// A renamed or misspelled folder yields zero files, and zero files pass every
			// other check. Startup stops on a required location that holds zero operation
			// files, so the check stops here as well, naming the folder as the test wrote
			// it.
			problems.add("The folder " + folder + " is not a directory, so the check cannot read operation files "
					+ "from it. Startup stops on a location that holds zero operation files, so name a folder "
					+ "that exists.");
			return;
		}
		// Startup resolves a location with Spring's resolver, which follows a symbolic
		// link to a folder and sorts what it finds, so the walk does both: a fragments
		// folder linked into the operations folder is read, and two machines list the
		// tools in one order.
		try (Stream<Path> walk = Files.walk(folder, FileVisitOption.FOLLOW_LINKS)) {
			walk.filter(OperationFileCheck::isOperationFile)
				.sorted()
				.map(OperationFileCheck::realPathOf)
				.forEach((path) -> files
					.computeIfAbsent(path, (key) -> new Found(EnumSet.noneOf(ToolExposureType.class)))
					.toolExposureTypes()
					.add(toolExposureType));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The folder " + folder + " cannot be read", ex);
		}
		catch (UncheckedIOException ex) {
			// A walk that follows links meets a cycle as an exception from the stream
			// itself.
			throw new UncheckedIOException("The folder " + folder + " cannot be read", ex.getCause());
		}
	}

	private static boolean isOperationFile(Path path) {
		String name = path.getFileName().toString();
		return Files.isRegularFile(path) && EXTENSIONS.stream().anyMatch(name::endsWith);
	}

	// A file reached through a symbolic link counts once, as it does at startup.
	private static Path realPathOf(Path path) {
		try {
			return path.toRealPath();
		}
		catch (IOException ex) {
			return path.toAbsolutePath();
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("The file " + file + " cannot be read", ex);
		}
	}

	private record Found(Set<ToolExposureType> toolExposureTypes) {
	}

}
