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

package io.gatool.core.internal.operation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.schema.GraphQLInputType;
import org.jspecify.annotations.Nullable;

/**
 * A validated operation that becomes a tool.
 *
 * <p>
 * An operation is built through {@link #builder()}, which names each value where it is
 * set.
 *
 * @author Željko Kozina
 */
public final class ToolOperation {

	private final String toolName;

	private final String operationName;

	private final OperationType operationType;

	private final @Nullable String description;

	private final @Nullable String title;

	private final String printedDocument;

	private final String inputSchema;

	private final String location;

	private final Set<ToolExposureType> toolExposureTypes;

	private final List<String> paginationVariables;

	private final Set<String> variablesWithDefaults;

	private final Map<String, GraphQLInputType> variableTypes;

	private final @Nullable Boolean openWorld;

	private final @Nullable String outputSchema;

	private final List<String> sharedFragmentFiles;

	private final @Nullable List<String> scopes;

	private ToolOperation(Builder builder) {
		this.toolName = require(builder.toolName, "toolName");
		this.operationName = require(builder.operationName, "operationName");
		this.operationType = require(builder.operationType, "operationType");
		this.description = builder.description;
		this.title = builder.title;
		this.printedDocument = require(builder.printedDocument, "printedDocument");
		this.inputSchema = require(builder.inputSchema, "inputSchema");
		this.location = require(builder.location, "location");
		this.toolExposureTypes = builder.toolExposureTypes;
		this.paginationVariables = builder.paginationVariables;
		this.variablesWithDefaults = builder.variablesWithDefaults;
		this.variableTypes = builder.variableTypes;
		this.openWorld = builder.openWorld;
		this.outputSchema = builder.outputSchema;
		this.sharedFragmentFiles = builder.sharedFragmentFiles;
		this.scopes = builder.scopes;
	}

	private static <T> T require(@Nullable T value, String what) {
		if (value == null) {
			throw new IllegalStateException("A ToolOperation needs a " + what + ", and the builder was given none.");
		}
		return value;
	}

	/**
	 * Starts an operation.
	 * @return a builder with every value unset and every collection empty
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the final tool name.
	 * @return the value the builder was given
	 */
	public String toolName() {
		return this.toolName;
	}

	/**
	 * Returns the name of the GraphQL operation.
	 * @return the value the builder was given
	 */
	public String operationName() {
		return this.operationName;
	}

	/**
	 * Returns whether the operation is a query or a mutation.
	 * @return the value the builder was given
	 */
	public OperationType operationType() {
		return this.operationType;
	}

	/**
	 * Returns the tool description, or {@code null} when the file and the schema lack
	 * one.
	 * @return the value the builder was given
	 */
	public @Nullable String description() {
		return this.description;
	}

	/**
	 * Returns the title for the MCP tool definition, which {@code @gatool} sets, or
	 * {@code null} where the file leaves it out.
	 * @return the value the builder was given
	 */
	public @Nullable String title() {
		return this.title;
	}

	/**
	 * Returns the printed document that the executor sends.
	 * @return the value the builder was given
	 */
	public String printedDocument() {
		return this.printedDocument;
	}

	/**
	 * Returns the JSON Schema 2020-12 object for the variables, as text.
	 * @return the value the builder was given
	 */
	public String inputSchema() {
		return this.inputSchema;
	}

	/**
	 * Returns the URL of the operation file.
	 * @return the value the builder was given
	 */
	public String location() {
		return this.location;
	}

	/**
	 * Returns the exposure types that publish the tool.
	 * @return the value the builder was given
	 */
	public Set<ToolExposureType> toolExposureTypes() {
		return this.toolExposureTypes;
	}

	/**
	 * Returns the variables that ask for a smaller page, which a result above the size
	 * limit names to the model.
	 * @return the value the builder was given
	 */
	public List<String> paginationVariables() {
		return this.paginationVariables;
	}

	/**
	 * Returns the variables that have a default waiting for them, whether the operation
	 * declares it or the schema declares it on every argument and input field the
	 * variable fills, or on a Non-Null one among them. The null rules protect these
	 * variables from a model that sends {@code null} for every value it would leave out.
	 * @return the value the builder was given
	 */
	public Set<String> variablesWithDefaults() {
		return this.variablesWithDefaults;
	}

	/**
	 * Returns the input type of each variable, which the null rules read to reach the
	 * input fields inside a value.
	 * @return the value the builder was given
	 */
	public Map<String, GraphQLInputType> variableTypes() {
		return this.variableTypes;
	}

	/**
	 * Returns whether the tool reaches an open-ended set of entities, which
	 * {@code @gatool(openWorld:)} sets, or {@code null} where the file leaves it out.
	 * @return the value the builder was given
	 */
	public @Nullable Boolean openWorld() {
		return this.openWorld;
	}

	/**
	 * Returns the JSON Schema 2020-12 object describing what a call returns, or
	 * {@code null} where this tool leaves the output schema out of its tool definition.
	 * @return the value the builder was given
	 */
	public @Nullable String outputSchema() {
		return this.outputSchema;
	}

	/**
	 * Returns the shared fragment files whose fragments the document borrows, empty where
	 * the operation file defines every fragment it spreads.
	 * @return the value the builder was given
	 */
	public List<String> sharedFragmentFiles() {
		return this.sharedFragmentFiles;
	}

	/**
	 * Returns the scopes a caller needs for this tool, all of them, which
	 * {@code @gatool(scopes:)} lists; empty where the file opens the tool to every caller
	 * with the baseline scopes, or {@code null} where the file leaves the argument out.
	 * @return the value the builder was given
	 */
	public @Nullable List<String> scopes() {
		return this.scopes;
	}

	/**
	 * Returns a rough token count for what one exposure type sends an agent for this
	 * tool.
	 *
	 * <p>
	 * Both exposure types send the name, the description and the input schema. The MCP
	 * tool list carries the title as well, and the output schema where the tool publishes
	 * one. A model provider reached in process is sent the first three alone.
	 * @param toolExposureType the exposure type the agent reads the tool through
	 * @param charactersAddedToEachSchema what the adapter of that exposure type adds to
	 * the text of each schema it publishes, as {@link SchemaAddition} carries it
	 * @return the estimate, which the startup listing of that exposure type prints for
	 * the tool and the catalog compares before it warns about a large one
	 */
	// Four characters to a token is the rough rule Anthropic's glossary gives, and a
	// tokeniser of one model's own would tie GATool to that vendor for a number that is
	// only a guide. The estimate sits on the tool, so the listing of an exposure type and
	// the warning about it read one number and cannot disagree.
	//
	// The estimate counts the text GATool fills a tool with, as each exposure type sends
	// it.
	//
	// The member names of a tool entry and the MCP annotations stay out, and so does the
	// name the MCP adapter repeats as the title of a tool whose file leaves it out. For a
	// tool with a name of 8 characters, they come to between 144 and 201 characters of
	// its tools/list entry, which is 36 to 50 tokens, depending on the title, the open
	// world hint and the output schema. That amount stays about the same whatever the
	// file holds, so it moves every estimate alike. In process the format around a tool
	// is the model provider's, which GATool cannot read, so counting it over MCP alone
	// would put the two listings on two bases.
	public int estimatedTokens(ToolExposureType toolExposureType, int charactersAddedToEachSchema) {
		int characters = this.toolName.length() + lengthOf(this.description) + this.inputSchema.length()
				+ charactersAddedToEachSchema;
		if (toolExposureType == ToolExposureType.MCP) {
			characters += lengthOf(this.title);
			if (this.outputSchema != null) {
				characters += this.outputSchema.length() + charactersAddedToEachSchema;
			}
		}
		return characters / 4;
	}

	/**
	 * Returns the share of the estimate the output schema takes over one exposure type,
	 * which is zero where the tool leaves the output schema out or the exposure type does
	 * not send it.
	 * @param toolExposureType the exposure type the agent reads the tool through
	 * @param charactersAddedToEachSchema what the adapter of that exposure type adds to
	 * the text of each schema it publishes
	 * @return the tokens of the output schema, which the warning about a large tool names
	 */
	public int estimatedOutputSchemaTokens(ToolExposureType toolExposureType, int charactersAddedToEachSchema) {
		if (toolExposureType != ToolExposureType.MCP || this.outputSchema == null) {
			return 0;
		}
		return (this.outputSchema.length() + charactersAddedToEachSchema) / 4;
	}

	private static int lengthOf(@Nullable String text) {
		return (text != null) ? text.length() : 0;
	}

	/**
	 * Collects the values of a {@link ToolOperation}. The tool name, the operation name,
	 * the operation type, the printed document, the input schema and the location are
	 * required, and {@link #build()} says which one is missing.
	 */
	public static final class Builder {

		private @Nullable String toolName;

		private @Nullable String operationName;

		private @Nullable OperationType operationType;

		private @Nullable String description;

		private @Nullable String title;

		private @Nullable String printedDocument;

		private @Nullable String inputSchema;

		private @Nullable String location;

		private Set<ToolExposureType> toolExposureTypes = Set.of();

		private List<String> paginationVariables = List.of();

		private Set<String> variablesWithDefaults = Set.of();

		private Map<String, GraphQLInputType> variableTypes = Map.of();

		private @Nullable Boolean openWorld;

		private @Nullable String outputSchema;

		private List<String> sharedFragmentFiles = List.of();

		private @Nullable List<String> scopes;

		private Builder() {
		}

		public Builder toolName(String toolName) {
			this.toolName = toolName;
			return this;
		}

		public Builder operationName(String operationName) {
			this.operationName = operationName;
			return this;
		}

		public Builder operationType(OperationType operationType) {
			this.operationType = operationType;
			return this;
		}

		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		public Builder title(@Nullable String title) {
			this.title = title;
			return this;
		}

		public Builder printedDocument(String printedDocument) {
			this.printedDocument = printedDocument;
			return this;
		}

		public Builder inputSchema(String inputSchema) {
			this.inputSchema = inputSchema;
			return this;
		}

		public Builder location(String location) {
			this.location = location;
			return this;
		}

		public Builder toolExposureTypes(Set<ToolExposureType> toolExposureTypes) {
			this.toolExposureTypes = Set.copyOf(toolExposureTypes);
			return this;
		}

		public Builder paginationVariables(List<String> paginationVariables) {
			this.paginationVariables = List.copyOf(paginationVariables);
			return this;
		}

		public Builder variablesWithDefaults(Set<String> variablesWithDefaults) {
			this.variablesWithDefaults = Set.copyOf(variablesWithDefaults);
			return this;
		}

		public Builder variableTypes(Map<String, GraphQLInputType> variableTypes) {
			this.variableTypes = Map.copyOf(variableTypes);
			return this;
		}

		public Builder openWorld(@Nullable Boolean openWorld) {
			this.openWorld = openWorld;
			return this;
		}

		public Builder outputSchema(@Nullable String outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		public Builder sharedFragmentFiles(List<String> sharedFragmentFiles) {
			this.sharedFragmentFiles = List.copyOf(sharedFragmentFiles);
			return this;
		}

		public Builder scopes(@Nullable List<String> scopes) {
			this.scopes = (scopes != null) ? List.copyOf(scopes) : null;
			return this;
		}

		public ToolOperation build() {
			return new ToolOperation(this);
		}

	}

}
