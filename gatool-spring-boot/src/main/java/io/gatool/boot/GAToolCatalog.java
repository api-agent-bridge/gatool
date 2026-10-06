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

package io.gatool.boot;

import java.util.List;

import io.gatool.core.model.GATool;

/**
 * The tools that this application serves.
 *
 * <p>
 * Properties configure this bean in most cases. One that prefers Java declares a bean of
 * this type, built with {@link #builder()}, and that bean replaces the auto-configured
 * one.
 *
 * <p>
 * The accessors return public types alone, so the adapters read the catalog as a
 * {@code List<GATool>} and both exposure types share one tool model. Each exposure type
 * has its own list, because the two exposure types read their own operation locations.
 *
 * @author Željko Kozina
 */
public final class GAToolCatalog {

	private final List<GATool> mcpTools;

	private final List<GATool> inProcessTools;

	private GAToolCatalog(List<GATool> mcpTools, List<GATool> inProcessTools) {
		this.mcpTools = mcpTools;
		this.inProcessTools = inProcessTools;
	}

	/**
	 * Starts a builder.
	 * @return a builder for an application that configures GATool in Java
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns the tools that the MCP server serves.
	 * @return the MCP tools, in the order of their operation files
	 */
	public List<GATool> mcpTools() {
		return this.mcpTools;
	}

	/**
	 * Returns the in-process tools.
	 * @return the tools that Spring AI's {@code ChatClient} calls, in the order of their
	 * operation files
	 */
	public List<GATool> inProcessTools() {
		return this.inProcessTools;
	}

	/**
	 * Builds an {@link GAToolCatalog} instance.
	 */
	public static final class Builder {

		private List<GATool> mcpTools = List.of();

		private List<GATool> inProcessTools = List.of();

		private Builder() {
		}

		/**
		 * Sets the tools that the MCP server serves.
		 * @param mcpTools the MCP tools
		 * @return this builder
		 */
		public Builder mcpTools(List<GATool> mcpTools) {
			this.mcpTools = mcpTools;
			return this;
		}

		/**
		 * Sets the in-process tools.
		 * @param inProcessTools the tools that Spring AI's {@code ChatClient} calls
		 * @return this builder
		 */
		public Builder inProcessTools(List<GATool> inProcessTools) {
			this.inProcessTools = inProcessTools;
			return this;
		}

		/**
		 * Builds the instance.
		 * @return the configured instance
		 */
		public GAToolCatalog build() {
			return new GAToolCatalog(List.copyOf(this.mcpTools), List.copyOf(this.inProcessTools));
		}

	}

}
