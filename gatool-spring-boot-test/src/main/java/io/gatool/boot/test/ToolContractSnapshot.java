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

package io.gatool.boot.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonComparator;
import org.springframework.test.json.JsonCompareMode;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.gatool.boot.GAToolCatalog;
import io.gatool.core.model.GATool;

/**
 * Compares the tool contract an application publishes with a committed snapshot.
 *
 * <p>
 * Tool names are part of the public contract: agents call them by name and client
 * configurations record them. A patch release produces the same contract for the same
 * schema and operation files, so a test that holds this snapshot fails when a change
 * alters what agents see, a name, a description, an input schema, an output schema or an
 * annotation, and a deliberate change arrives with an updated snapshot in the same
 * commit, where review sees it.
 *
 * <p>
 * The contract is read from the {@link GAToolCatalog} bean, both sides, sorted by tool
 * name. The MCP {@code tools/list} and the Spring AI tool definitions both derive from
 * it, so the snapshot covers an application on either starter, and the test works without
 * an MCP client. It starts the application context, so an application that serves MCP
 * over stdio builds its stdio server during the test:
 *
 * <pre>
 * &#64;SpringBootTest
 * class ToolContractTests {
 *

 *     &#64;Autowired
 *     GAToolCatalog catalog;
 *
 *
&#64;Test
 *     void tools_shouldMatchTheSnapshot() {
 *         ToolContractSnapshot.of(catalog).assertMatches(Path.of("src/test/resources/gatool-contract.json"));
 *     }
 * }
 * </pre>
 *
 * <p>
 * Each tool is written with its name, title, description, input schema, output schema,
 * read-only flag, open-world flag and scopes, the scopes in the order the operation file
 * declares them, so a file that reorders them shows the change in review. The file is
 * written with {@code \n} line endings on every platform, so a snapshot committed on one
 * operating system compares equal on another and a diff shows the field that changed.
 *
 * <p>
 * A missing snapshot is written and the assertion then fails, asking for the file to be
 * reviewed and committed, so a run in CI without the file fails, where a silent pass
 * would hide the gap. A deliberate change is accepted by running with
 * {@code -Dgatool.snapshot.update=true}, which rewrites the file for review. Maven passes
 * that flag from the command line into the test JVM. Gradle keeps the command line and
 * the test JVM apart, so a Gradle build forwards it on the test task,
 * {@code systemProperty("gatool.snapshot.update", System.getProperty("gatool.snapshot.update"))},
 * or sets it there outright. The relative path in the example resolves against the
 * working directory of the test JVM, which Maven and Gradle both set to the module's
 * directory. The comparison uses spring-test's {@link JsonAssert} in strict mode, the
 * comparator this project's own build check uses.
 *
 * @author Željko Kozina
 */
public final class ToolContractSnapshot {

	/**
	 * The JVM system property that rewrites the snapshot on the next run, passed as
	 * {@code -Dgatool.snapshot.update=true}. It shares the {@code gatool.} prefix with
	 * the starter's configuration properties, and it is read straight from the system
	 * properties, outside Spring Boot's environment.
	 */
	public static final String UPDATE_PROPERTY = "gatool.snapshot.update";

	// Indented, so a committed snapshot reads in review and a diff shows one field, and
	// with a line feed as the line ending on every platform, because Jackson's default
	// indenter takes the platform's, and a snapshot committed on Windows would then
	// differ byte for byte from one written on Linux.
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(SerializationFeature.INDENT_OUTPUT)
		.defaultPrettyPrinter(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")))
		.build();

	// Strict mode holds array order and refuses an unexpected field, which the sort
	// makes safe and the contract wants.
	private static final JsonComparator COMPARATOR = JsonAssert.comparator(JsonCompareMode.STRICT);

	private final String contract;

	private ToolContractSnapshot(String contract) {
		this.contract = contract;
	}

	/**
	 * Reads the contract an application publishes.
	 * @param catalog the bean that holds the tools of both sides
	 * @return the snapshot, ready to compare or to print
	 */
	public static ToolContractSnapshot of(GAToolCatalog catalog) {
		ObjectNode root = JSON.createObjectNode();
		write(root.putArray("mcp"), catalog.mcpTools());
		write(root.putArray("inProcess"), catalog.inProcessTools());
		return new ToolContractSnapshot(JSON.writeValueAsString(root));
	}

	/**
	 * Returns the contract as JSON text.
	 * @return the tools of both sides, sorted by name, as the snapshot file holds them
	 */
	public String contract() {
		return this.contract;
	}

	/**
	 * Asserts that the contract matches the snapshot file.
	 * @param snapshotFile the committed snapshot, written by this call when it is missing
	 * or when {@link #UPDATE_PROPERTY} is set
	 * @throws AssertionError if the file was missing and has now been written, or if the
	 * contract differs from it
	 */
	public void assertMatches(Path snapshotFile) {
		if (Boolean.getBoolean(UPDATE_PROPERTY)) {
			writeTo(snapshotFile);
			return;
		}
		if (Files.notExists(snapshotFile)) {
			writeTo(snapshotFile);
			throw new AssertionError("No snapshot at " + snapshotFile + ", so this run wrote one. Review it, commit "
					+ "it, and run the test again.");
		}
		try {
			COMPARATOR.assertIsMatch(read(snapshotFile), this.contract);
		}
		catch (AssertionError ex) {
			throw new AssertionError("The tool contract differs from " + snapshotFile + ": " + ex.getMessage()
					+ System.lineSeparator() + "A deliberate change is accepted by running with -D" + UPDATE_PROPERTY
					+ "=true, which rewrites the file for review.", ex);
		}
	}

	private static void write(ArrayNode array, List<GATool> tools) {
		tools.stream().sorted(Comparator.comparing(GATool::name)).forEach((tool) -> {
			ObjectNode node = array.addObject();
			node.put("name", tool.name());
			putText(node, "title", tool.title());
			putText(node, "description", tool.description());
			node.set("inputSchema", JSON.readTree(tool.inputSchema()));
			String outputSchema = tool.outputSchema();
			if (outputSchema != null) {
				node.set("outputSchema", JSON.readTree(outputSchema));
			}
			else {
				node.putNull("outputSchema");
			}
			node.put("readOnly", tool.readOnly());
			Boolean openWorld = tool.openWorld();
			if (openWorld != null) {
				node.put("openWorld", openWorld.booleanValue());
			}
			else {
				node.putNull("openWorld");
			}
			// The scopes are part of the contract: a client that holds a token for one
			// set of scopes stops working when a tool starts asking for another. Null is
			// a tool that leaves scopes undeclared, and an empty list one open to every
			// caller with the baseline scopes.
			List<String> scopes = tool.scopes();
			if (scopes != null) {
				ArrayNode scopeArray = node.putArray("scopes");
				scopes.forEach(scopeArray::add);
			}
			else {
				node.putNull("scopes");
			}
		});
	}

	private static void putText(ObjectNode node, String name, @Nullable String value) {
		if (value != null) {
			node.put(name, value);
		}
		else {
			node.putNull(name);
		}
	}

	/**
	 * Writes the contract to a file, creating the folders above it.
	 * @param snapshotFile where the snapshot goes, as {@link #assertMatches} writes it
	 * when the file is missing
	 */
	public void writeTo(Path snapshotFile) {
		try {
			Path parent = snapshotFile.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(snapshotFile, this.contract + "\n", StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not write the snapshot to " + snapshotFile, ex);
		}
	}

	private static String read(Path snapshotFile) {
		try {
			return Files.readString(snapshotFile, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read the snapshot at " + snapshotFile, ex);
		}
	}

}
