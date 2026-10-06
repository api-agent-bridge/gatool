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

package io.gatool.core.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * One tool, as GATool describes it, and as the MCP adapter and the Spring AI adapter each
 * turn into their own framework type.
 *
 * <p>
 * A tool is built through {@link #builder()}:
 *
 * <pre>{@code
 *   GATool tool = GATool.builder()
 *       .name("topRatedMovies")
 *       .description("Returns the highest-rated movies, best first.")
 *       .inputSchema(schema)
 *       .readOnly(true)
 *       .callHandler(arguments -> new ToolCallOutcome(text, false))
 *       .build();
 * }</pre>
 *
 * <p>
 * A builder names each value where it is set, and a value added later is one more method.
 * Spring AI's {@code ToolDefinition} and the MCP SDK's {@code Tool} are created the same
 * way.
 *
 * <p>
 * The callHandler is a {@link Function} of the Java library. Two other shapes are
 * possible: an interface of GATool's own, and a framework type such as Spring AI's
 * {@code ToolCallback}. A framework type would put Spring on the compile path of
 * {@code gatool-core}, which the module's enforcer rule bans, and an interface of
 * GATool's own would add a name for a shape the Java library already has. A plain
 * function keeps the core free of both frameworks, so an adapter for another agent
 * library reads this same model.
 *
 * @author Željko Kozina
 */
public final class GATool {

	private final String name;

	private final @Nullable String title;

	private final @Nullable String description;

	private final String inputSchema;

	private final boolean readOnly;

	private final @Nullable Boolean openWorld;

	private final @Nullable String outputSchema;

	private final Function<Map<String, @Nullable Object>, ToolCallOutcome> callHandler;

	private final @Nullable List<String> scopes;

	private GATool(Builder builder, String name, String inputSchema,
			Function<Map<String, @Nullable Object>, ToolCallOutcome> callHandler) {
		this.name = name;
		this.title = builder.title;
		this.description = builder.description;
		this.inputSchema = inputSchema;
		this.readOnly = builder.readOnly;
		this.openWorld = builder.openWorld;
		this.outputSchema = builder.outputSchema;
		this.callHandler = callHandler;
		this.scopes = builder.scopes;
	}

	/**
	 * Starts a tool.
	 * @return a builder with every value unset and {@code readOnly} false
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Starts a tool from the values of this one, for a copy that differs in a value or
	 * two.
	 * @return a builder holding every value of this tool
	 */
	public Builder toBuilder() {
		return new Builder().name(this.name)
			.title(this.title)
			.description(this.description)
			.inputSchema(this.inputSchema)
			.readOnly(this.readOnly)
			.openWorld(this.openWorld)
			.outputSchema(this.outputSchema)
			.callHandler(this.callHandler)
			.scopes(this.scopes);
	}

	/**
	 * Returns the tool name that agents call.
	 * @return the name
	 */
	public String name() {
		return this.name;
	}

	/**
	 * Returns the title a client shows in place of the name.
	 * @return the title that {@code @gatool(title:)} sets, or {@code null} where the file
	 * leaves it out
	 */
	public @Nullable String title() {
		return this.title;
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
	 * Returns the schema of the arguments.
	 * @return the JSON Schema 2020-12 object for the arguments, as text
	 */
	public String inputSchema() {
		return this.inputSchema;
	}

	/**
	 * Returns whether the tool only reads, which becomes {@code readOnlyHint}.
	 * @return true for a tool that only reads
	 */
	public boolean readOnly() {
		return this.readOnly;
	}

	/**
	 * Returns whether the tool reaches an open-ended set of entities, which becomes
	 * {@code openWorldHint}.
	 * @return the hint, or {@code null} where the operation file leaves it unsaid and the
	 * hint stays out
	 */
	public @Nullable Boolean openWorld() {
		return this.openWorld;
	}

	/**
	 * Returns the schema of what a call returns. MCP binds a server to a schema it
	 * publishes, so a tool carrying one returns structured content that conforms to it.
	 * @return the JSON Schema 2020-12 object, as text, or {@code null} where this tool
	 * does not publish one
	 */
	public @Nullable String outputSchema() {
		return this.outputSchema;
	}

	/**
	 * Returns the function that runs one call and returns its outcome.
	 * @return the call handler
	 */
	public Function<Map<String, @Nullable Object>, ToolCallOutcome> callHandler() {
		return this.callHandler;
	}

	/**
	 * Returns the scopes a caller needs for this tool, all of them, on top of the
	 * baseline scopes every call needs.
	 * @return the scopes, where an empty list opens the tool to every caller with the
	 * baseline scopes and {@code null} says the tool leaves its scopes undeclared
	 */
	public @Nullable List<String> scopes() {
		return this.scopes;
	}

	/**
	 * Runs one tool call.
	 * @param arguments the arguments the caller sent
	 * @return the text of the result and whether it is an error
	 */
	public ToolCallOutcome call(Map<String, @Nullable Object> arguments) {
		return this.callHandler.apply(arguments);
	}

	@Override
	public String toString() {
		return "GATool[name=" + this.name + ", readOnly=" + this.readOnly + ", scopes=" + this.scopes + "]";
	}

	/**
	 * Collects the values of a {@link GATool}. The name, the input schema and the call
	 * handler are required, and {@link #build()} says which one is missing.
	 */
	public static final class Builder {

		private @Nullable String name;

		private @Nullable String title;

		private @Nullable String description;

		private @Nullable String inputSchema;

		private boolean readOnly;

		private @Nullable Boolean openWorld;

		private @Nullable String outputSchema;

		private @Nullable Function<Map<String, @Nullable Object>, ToolCallOutcome> callHandler;

		private @Nullable List<String> scopes;

		private Builder() {
		}

		/**
		 * Sets the tool name that agents call. Required.
		 * @param name the name
		 * @return this builder
		 */
		public Builder name(String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets the title a client shows in place of the name.
		 * @param title the title, or {@code null} to leave it out
		 * @return this builder
		 */
		public Builder title(@Nullable String title) {
			this.title = title;
			return this;
		}

		/**
		 * Sets what the tool does.
		 * @param description the description, or {@code null} for a tool without one
		 * @return this builder
		 */
		public Builder description(@Nullable String description) {
			this.description = description;
			return this;
		}

		/**
		 * Sets the JSON Schema 2020-12 object for the arguments, as text. Required.
		 * @param inputSchema the schema
		 * @return this builder
		 */
		public Builder inputSchema(String inputSchema) {
			this.inputSchema = inputSchema;
			return this;
		}

		/**
		 * Sets whether the tool only reads, which becomes {@code readOnlyHint}. A tool
		 * that leaves this unset counts as one that writes, which is the careful reading
		 * of a tool that does not say.
		 * @param readOnly true for a tool that only reads
		 * @return this builder
		 */
		public Builder readOnly(boolean readOnly) {
			this.readOnly = readOnly;
			return this;
		}

		/**
		 * Sets whether the tool reaches an open-ended set of entities, which becomes
		 * {@code openWorldHint}.
		 * @param openWorld the hint, or {@code null} to leave it out
		 * @return this builder
		 */
		public Builder openWorld(@Nullable Boolean openWorld) {
			this.openWorld = openWorld;
			return this;
		}

		/**
		 * Sets the JSON Schema 2020-12 object describing what a call returns, as text.
		 * @param outputSchema the schema, or {@code null} for a tool that does not
		 * publish one
		 * @return this builder
		 */
		public Builder outputSchema(@Nullable String outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		/**
		 * Sets the function that runs one call and returns its outcome. Required.
		 * @param callHandler the call handler
		 * @return this builder
		 */
		public Builder callHandler(Function<Map<String, @Nullable Object>, ToolCallOutcome> callHandler) {
			this.callHandler = callHandler;
			return this;
		}

		/**
		 * Sets the scopes a caller needs for this tool, on top of the baseline scopes.
		 * The list is copied, so the tool keeps the scopes it was given however the
		 * caller changes its own list afterwards.
		 * @param scopes the scopes, an empty list for a tool open to every caller with
		 * the baseline scopes, or {@code null} for a tool that leaves its scopes
		 * undeclared
		 * @return this builder
		 */
		public Builder scopes(@Nullable List<String> scopes) {
			this.scopes = (scopes != null) ? List.copyOf(scopes) : null;
			return this;
		}

		/**
		 * Builds the tool.
		 * @return the tool
		 * @throws IllegalStateException if the name, the input schema or the call handler
		 * is unset
		 */
		public GATool build() {
			return new GATool(this, require(this.name, "name"), require(this.inputSchema, "inputSchema"),
					require(this.callHandler, "callHandler"));
		}

		private static <T> T require(@Nullable T value, String what) {
			if (value == null) {
				throw new IllegalStateException("A GATool needs a " + what + ", and the builder was given none.");
			}
			return value;
		}

	}

}
