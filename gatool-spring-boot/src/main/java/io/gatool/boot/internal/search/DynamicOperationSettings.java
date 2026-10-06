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

package io.gatool.boot.internal.search;

import org.jspecify.annotations.Nullable;

/**
 * What the three tools of the dynamic layer read from
 * {@code gatool.dev.experimental.dynamic-operations}.
 *
 * @param searchTokenBudget tokens of search results one call returns, where zero and
 * below mean every ranked hit
 * @param rankedHits coordinates a search ranks before the budget cuts the list
 * @param allowMutations whether a mutation the model wrote runs
 * @param includeDeprecated whether a deprecated field is searchable, readable and routed
 * through
 * @param validateOnly whether {@code executeGraphql} returns the validated document with
 * its variables in place of calling the API
 * @param limits the bounds on a document the execute tool sends
 * @param descriptions the description an application set for each of the three tools
 * @author Željko Kozina
 */
public record DynamicOperationSettings(int searchTokenBudget, int rankedHits, boolean allowMutations,
		boolean includeDeprecated, boolean validateOnly, Limits limits, Descriptions descriptions) {

	/**
	 * Creates the settings with the description GATool writes for each tool.
	 * @param searchTokenBudget tokens of search results one call returns
	 * @param rankedHits coordinates a search ranks before the budget cuts the list
	 * @param allowMutations whether a mutation the model wrote runs
	 * @param includeDeprecated whether a deprecated field is searchable, readable and
	 * routed through
	 * @param validateOnly whether {@code executeGraphql} returns the validated document
	 * @param limits the bounds on a document the execute tool sends
	 */
	public DynamicOperationSettings(int searchTokenBudget, int rankedHits, boolean allowMutations,
			boolean includeDeprecated, boolean validateOnly, Limits limits) {
		this(searchTokenBudget, rankedHits, allowMutations, includeDeprecated, validateOnly, limits,
				new Descriptions(null, null, null));
	}

	/**
	 * Returns the deepest field nesting the execute tool sends.
	 * @return the bound, where zero and below switch the check off
	 */
	public int maxDepth() {
		return this.limits.maxDepth();
	}

	/**
	 * Returns the most field selections the execute tool sends.
	 * @return the bound, where zero and below switch the check off
	 */
	public int maxFields() {
		return this.limits.maxFields();
	}

	/**
	 * Returns the most aliased fields the execute tool sends.
	 * @return the bound, where zero and below switch the check off
	 */
	public int maxAliases() {
		return this.limits.maxAliases();
	}

	/**
	 * The bounds on a document the execute tool sends. Zero and below switch a check off.
	 *
	 * <p>
	 * The three sat beside the five settings above as three more integers, which made a
	 * list of eight values passed by position.
	 *
	 * @param maxDepth deepest field nesting the execute tool sends
	 * @param maxFields most field selections the execute tool sends, spreads counted
	 * where spread
	 * @param maxAliases most aliased fields the execute tool sends
	 */
	public record Limits(int maxDepth, int maxFields, int maxAliases) {
	}

	/**
	 * The description an application set for each of the three tools. A tool whose entry
	 * is null or blank keeps the description GATool writes.
	 *
	 * @param searchSchema the whole description of {@code searchSchema}
	 * @param introspectType the whole description of {@code introspectType}
	 * @param executeGraphql the whole description of {@code executeGraphql}
	 */
	public record Descriptions(@Nullable String searchSchema, @Nullable String introspectType,
			@Nullable String executeGraphql) {
	}
}
