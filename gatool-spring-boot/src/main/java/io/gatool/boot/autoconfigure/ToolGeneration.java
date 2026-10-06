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

package io.gatool.boot.autoconfigure;

/**
 * Which tools GATool writes from the schema, for
 * {@code gatool.dev.experimental.generate-tools}.
 *
 * <p>
 * Two shapes sit here. The {@code ALL_ROOT} values write one tool per root field, so the
 * model chooses a tool and GATool holds the document. The {@code DYNAMIC} value publishes
 * a few generic tools instead and lets the model write the GraphQL, which costs less on a
 * schema large enough that publishing it whole dominates the prompt.
 *
 * <p>
 * Mutations take their own value instead of being included with queries, because a write
 * tool for every mutation is a wider step than a read tool for every query, and someone
 * exploring a schema usually wants the reads first.
 *
 * <p>
 * An {@code OFF} value is missing here, because YAML reads {@code off} as a boolean and
 * the value would not reach this enum.
 *
 * @author Željko Kozina
 */
public enum ToolGeneration {

	/**
	 * Only the operation files make tools, which is the default.
	 */
	NONE,

	/**
	 * Every query root field that lacks an operation file becomes a tool.
	 */
	ALL_ROOT_QUERIES,

	/**
	 * Every query and mutation root field that lacks an operation file becomes a tool, so
	 * a model can write as well as read.
	 */
	ALL_ROOT_QUERIES_AND_MUTATIONS,

	/**
	 * Three tools, {@code searchSchema}, {@code introspectType} and
	 * {@code executeGraphql}: the model searches for the fields it needs, reads those
	 * types, and runs a document it wrote. The search step is what makes a schema too
	 * large to read in one piece usable.
	 */
	DYNAMIC_THREE_STEP;

	/**
	 * Whether GATool writes an operation for each root field.
	 * @return true for the {@code ALL_ROOT} values
	 */
	boolean generatesPerRootField() {
		return this == ALL_ROOT_QUERIES || this == ALL_ROOT_QUERIES_AND_MUTATIONS;
	}

	/**
	 * Whether the model writes the GraphQL and GATool publishes generic tools.
	 * @return true for the {@code DYNAMIC} value
	 */
	boolean isDynamic() {
		return this == DYNAMIC_THREE_STEP;
	}

	boolean includesMutations() {
		return this == ALL_ROOT_QUERIES_AND_MUTATIONS;
	}

}
