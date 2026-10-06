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

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.gatool.core.internal.operation.OperationType;
import io.gatool.core.internal.operation.ToolExposureType;
import io.gatool.core.internal.operation.ToolOperation;

/**
 * One tool that the operation files would serve.
 *
 * <p>
 * The exposure types arrive as their labels, so a test reads "MCP" and "in-process" and
 * keeps the core's own enum internal.
 *
 * <p>
 * The check creates every {@code CheckedTool}, and a caller reads one. The type is a
 * class whose constructor stays hidden, so a value added later is one more accessor and
 * callers are left unchanged.
 *
 * @author Željko Kozina
 */
public final class CheckedTool {

	private final String name;

	private final @Nullable String description;

	private final String inputSchema;

	private final String location;

	private final Set<String> exposureTypeLabels;

	private final String document;

	private final List<String> sharedFragmentFiles;

	private final @Nullable String title;

	private final @Nullable String outputSchema;

	private final boolean readOnly;

	private final @Nullable Boolean openWorld;

	private final List<String> scopes;

	private CheckedTool(ToolOperation operation) {
		this.name = operation.toolName();
		this.description = operation.description();
		this.inputSchema = operation.inputSchema();
		this.location = operation.location();
		this.exposureTypeLabels = operation.toolExposureTypes()
			.stream()
			.map(ToolExposureType::label)
			.collect(Collectors.toUnmodifiableSet());
		this.document = operation.printedDocument();
		this.sharedFragmentFiles = List.copyOf(operation.sharedFragmentFiles());
		this.title = operation.title();
		this.outputSchema = operation.outputSchema();
		this.readOnly = operation.operationType() == OperationType.QUERY;
		this.openWorld = operation.openWorld();
		List<String> declared = operation.scopes();
		this.scopes = (declared != null) ? List.copyOf(declared) : List.of();
	}

	static CheckedTool of(ToolOperation operation) {
		return new CheckedTool(operation);
	}

	/**
	 * Returns the tool name agents would call.
	 * @return the name
	 */
	public String name() {
		return this.name;
	}

	/**
	 * Returns what the tool does.
	 * @return the description, or {@code null} where the file and the schema both leave
	 * the tool undescribed
	 */
	public @Nullable String description() {
		return this.description;
	}

	/**
	 * Returns the schema of the variables.
	 * @return the JSON Schema 2020-12 object for the variables, as text
	 */
	public String inputSchema() {
		return this.inputSchema;
	}

	/**
	 * Returns where the operation file is.
	 * @return the URL of the operation file
	 */
	public String location() {
		return this.location;
	}

	/**
	 * Returns the labels of the exposure types that would publish the tool.
	 * @return the labels, such as "MCP" and "in-process"
	 */
	public Set<String> exposureTypeLabels() {
		return this.exposureTypeLabels;
	}

	/**
	 * Returns the text the tool sends to the API: the operation file as printed, with the
	 * shared fragments it spreads appended.
	 * @return the document
	 */
	public String document() {
		return this.document;
	}

	/**
	 * Returns the shared fragment files the document borrows from.
	 * @return the files, empty where the operation file defines every fragment it spreads
	 */
	public List<String> sharedFragmentFiles() {
		return this.sharedFragmentFiles;
	}

	/**
	 * Returns the title a client would show in place of the name.
	 * @return the title that {@code @gatool(title:)} sets, or {@code null} where the file
	 * leaves it out
	 */
	public @Nullable String title() {
		return this.title;
	}

	/**
	 * Returns the schema of what a call returns.
	 * @return the JSON Schema 2020-12 object, as text, or {@code null} where this tool
	 * would leave the output schema out of its tool definition
	 */
	public @Nullable String outputSchema() {
		return this.outputSchema;
	}

	/**
	 * Returns whether the tool only reads.
	 * @return true for a query
	 */
	public boolean readOnly() {
		return this.readOnly;
	}

	/**
	 * Returns whether the tool reaches an open-ended set of entities.
	 * @return the value {@code @gatool(openWorld:)} sets, or {@code null} where the file
	 * leaves it out
	 */
	public @Nullable Boolean openWorld() {
		return this.openWorld;
	}

	/**
	 * Returns the scopes a caller would need for this tool, on top of the baseline scopes
	 * every call needs.
	 * @return the scopes, empty where the file opens the tool to every caller with the
	 * baseline scopes or leaves the argument out
	 */
	public List<String> scopes() {
		return this.scopes;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		if (this == other) {
			return true;
		}
		return other instanceof CheckedTool that && this.readOnly == that.readOnly && this.name.equals(that.name)
				&& Objects.equals(this.description, that.description) && this.inputSchema.equals(that.inputSchema)
				&& this.location.equals(that.location) && this.exposureTypeLabels.equals(that.exposureTypeLabels)
				&& this.document.equals(that.document) && this.sharedFragmentFiles.equals(that.sharedFragmentFiles)
				&& Objects.equals(this.title, that.title) && Objects.equals(this.outputSchema, that.outputSchema)
				&& Objects.equals(this.openWorld, that.openWorld) && this.scopes.equals(that.scopes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.name, this.description, this.inputSchema, this.location, this.exposureTypeLabels,
				this.document, this.sharedFragmentFiles, this.title, this.outputSchema, this.readOnly, this.openWorld,
				this.scopes);
	}

	@Override
	public String toString() {
		return "CheckedTool[name=" + this.name + ", location=" + this.location + "]";
	}

}
