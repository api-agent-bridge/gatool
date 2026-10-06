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

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.gatool.core.search.CorpusFormat;

/**
 * Settings for development, which a production deployment leaves at their defaults.
 *
 * @author Željko Kozina
 */
public class GAToolDevProperties {

	/**
	 * Creates the group under {@code gatool.dev} with the default of each property.
	 */
	public GAToolDevProperties() {
		// Each property keeps the default its field declares.
	}

	private final Experimental experimental = new Experimental();

	/**
	 * Returns the group of properties under {@code gatool.dev.experimental}.
	 * @return the group
	 */
	public Experimental getExperimental() {
		return this.experimental;
	}

	/**
	 * Features whose shape may change between releases, kept behind this prefix until
	 * they settle.
	 */
	public static class Experimental {

		/**
		 * Creates the group under {@code gatool.dev.experimental} with the default of
		 * each property.
		 */
		public Experimental() {
			// Each property keeps the default its field declares.
		}

		/**
		 * Which tools to write from the schema, for root fields without an operation
		 * file.
		 */
		private ToolGeneration generateTools = ToolGeneration.NONE;

		/**
		 * Whether the generator writes a tool for a root field the schema deprecates,
		 * with the deprecation in its description. Applies only when generate-tools is
		 * all-root-queries or all-root-queries-and-mutations.
		 */
		private boolean generateToolsForDeprecatedRootFields = true;

		/**
		 * Whether a deprecated field joins a generated selection set. Applies only when
		 * generate-tools is all-root-queries or all-root-queries-and-mutations.
		 */
		private boolean generatedOperationsIncludeDeprecatedFields = true;

		/**
		 * Number of object levels a generated selection expands, from 1 to 5.
		 */
		private int generatedSelectionDepth = 1;

		private final DynamicOperations dynamicOperations = new DynamicOperations();

		/**
		 * Returns which tools to write from the schema, for root fields without an
		 * operation file.
		 * @return the value of {@code gatool.dev.experimental.generate-tools}
		 */
		public ToolGeneration getGenerateTools() {
			return this.generateTools;
		}

		/**
		 * Sets which tools to write from the schema, for root fields without an operation
		 * file.
		 * @param generateTools the value of
		 * {@code gatool.dev.experimental.generate-tools}
		 */
		public void setGenerateTools(ToolGeneration generateTools) {
			this.generateTools = generateTools;
		}

		/**
		 * Returns whether the generator writes a tool for a root field the schema
		 * deprecates, with the deprecation in its description.
		 * @return the value of
		 * {@code gatool.dev.experimental.generate-tools-for-deprecated-root-fields}
		 */
		public boolean isGenerateToolsForDeprecatedRootFields() {
			return this.generateToolsForDeprecatedRootFields;
		}

		/**
		 * Sets whether the generator writes a tool for a root field the schema
		 * deprecates, with the deprecation in its description.
		 * @param generateToolsForDeprecatedRootFields the value of
		 * {@code gatool.dev.experimental.generate-tools-for-deprecated-root-fields}
		 */
		public void setGenerateToolsForDeprecatedRootFields(boolean generateToolsForDeprecatedRootFields) {
			this.generateToolsForDeprecatedRootFields = generateToolsForDeprecatedRootFields;
		}

		/**
		 * Returns whether a deprecated field joins a generated selection set.
		 * @return the value of
		 * {@code gatool.dev.experimental.generated-operations-include-deprecated-fields}
		 */
		public boolean isGeneratedOperationsIncludeDeprecatedFields() {
			return this.generatedOperationsIncludeDeprecatedFields;
		}

		/**
		 * Sets whether a deprecated field joins a generated selection set.
		 * @param generatedOperationsIncludeDeprecatedFields the value of
		 * {@code gatool.dev.experimental.generated-operations-include-deprecated-fields}
		 */
		public void setGeneratedOperationsIncludeDeprecatedFields(boolean generatedOperationsIncludeDeprecatedFields) {
			this.generatedOperationsIncludeDeprecatedFields = generatedOperationsIncludeDeprecatedFields;
		}

		/**
		 * Returns the number of object levels a generated selection expands, from 1 to 5.
		 * @return the value of {@code gatool.dev.experimental.generated-selection-depth}
		 */
		public int getGeneratedSelectionDepth() {
			return this.generatedSelectionDepth;
		}

		/**
		 * Sets the number of object levels a generated selection expands, from 1 to 5.
		 * @param generatedSelectionDepth the value of
		 * {@code gatool.dev.experimental.generated-selection-depth}
		 */
		public void setGeneratedSelectionDepth(int generatedSelectionDepth) {
			this.generatedSelectionDepth = generatedSelectionDepth;
		}

		/**
		 * Returns the group of properties under
		 * {@code gatool.dev.experimental.dynamic-operations}.
		 * @return the group
		 */
		public DynamicOperations getDynamicOperations() {
			return this.dynamicOperations;
		}

		/**
		 * Settings of the three tools that let a model search the schema and run GraphQL
		 * it wrote, which generate-tools switches on.
		 */
		public static class DynamicOperations {

			/**
			 * Creates the group under {@code gatool.dev.experimental.dynamic-operations}
			 * with the default of each property.
			 */
			public DynamicOperations() {
				// Each property keeps the default its field declares.
			}

			/**
			 * How each schema field is written for the search index.
			 */
			private CorpusFormat corpusFormat = CorpusFormat.SDL;

			/**
			 * What ranks a search, either bm25 or embedding.
			 */
			private SearchBackend searchBackend = SearchBackend.BM25;

			/**
			 * Tokens of search results one call returns. A value below 1 returns every
			 * ranked hit, which is how the bound is switched off.
			 */
			private int searchTokenBudget = 2000;

			/**
			 * Coordinates a search ranks before the budget cuts the list.
			 */
			private int rankedHits = 50;

			/**
			 * Whether a mutation the model wrote runs.
			 */
			private boolean allowMutations;

			/**
			 * Whether executeGraphql validates the document and returns it without
			 * calling the API, so a test or a benchmark reads the operation the model
			 * wrote. The tool description then says it returns the operation for a person
			 * to run.
			 */
			private boolean validateOnly;

			/**
			 * The whole description of the searchSchema tool, replacing the one GATool
			 * writes.
			 */
			private @Nullable String searchSchemaDescription;

			/**
			 * The whole description of the introspectType tool, replacing the one GATool
			 * writes.
			 */
			private @Nullable String introspectTypeDescription;

			/**
			 * The whole description of the executeGraphql tool, replacing the one GATool
			 * writes.
			 */
			private @Nullable String executeGraphqlDescription;

			// The three defaults below are provisional, and the API's own limits stay the
			// final word.

			/**
			 * Deepest field nesting executeGraphql sends, counting a root field as one
			 * level. Zero switches the check off.
			 */
			private int maxDepth = 15;

			/**
			 * Most field selections executeGraphql sends, with every fragment spread
			 * counted where it is spread. Zero switches the check off.
			 */
			private int maxFields = 500;

			/**
			 * Most aliased fields executeGraphql sends, which is how a document asks for
			 * one field many times. Zero switches the check off.
			 */
			private int maxAliases = 30;

			/**
			 * Whether a deprecated field is searchable, readable and routed through, and
			 * marked with the reason the schema gives when it is. Off by default, because
			 * a large schema carries many and each one spends search budget on a field
			 * the API asks callers to stop using. The two generation properties above
			 * stay on instead, because a generated tool states the deprecation in its
			 * description.
			 */
			private boolean includeDeprecatedFields;

			/**
			 * Text put in front of a question before it is embedded, which some embedding
			 * models expect. The text is joined to the question as it is, because models
			 * differ in the separator they were trained with, so a trailing space or line
			 * break belongs in the value. A plain YAML scalar drops a trailing space and
			 * a quoted one keeps it; a properties file keeps it either way.
			 */
			private String questionPrefix = "";

			/**
			 * Text put in front of each schema field before it is embedded, joined as it
			 * is, the same way as the query prefix.
			 */
			private String fieldPrefix = "";

			/**
			 * Schema fields sent to the embedding model in one call.
			 */
			private int embedBatchSize = 128;

			/**
			 * Where embedded fields are cached, so a large schema is embedded once. The
			 * default is vectors, inside a folder named gatool- and the account the JVM
			 * runs as, under the JVM's temporary directory, so the cache lives as long as
			 * that directory does. GATool makes the default folder for its owner alone
			 * and uses it only while that account owns it and group and others cannot
			 * write to it. A team that wants the cache to survive, or to live on a volume
			 * of its own, sets this property, and GATool uses that directory as it is.
			 * Blank embeds at every startup.
			 */
			private String vectorCacheDirectory = GAToolProperties.underTemporaryDirectory("vectors");

			/**
			 * Scopes a caller needs for the three dynamic tools, all of them, on top of
			 * the baseline scopes. Unset leaves them at the baseline alone, which a
			 * credential without a user behind it refuses at startup, because
			 * executeGraphql then runs whatever a caller writes under the shared
			 * identity.
			 */
			private @Nullable List<String> requiredScopes;

			/**
			 * Returns how each schema field is written for the search index.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.corpus-format}
			 */
			public CorpusFormat getCorpusFormat() {
				return this.corpusFormat;
			}

			/**
			 * Sets how each schema field is written for the search index.
			 * @param corpusFormat the value of
			 * {@code gatool.dev.experimental.dynamic-operations.corpus-format}
			 */
			public void setCorpusFormat(CorpusFormat corpusFormat) {
				this.corpusFormat = corpusFormat;
			}

			/**
			 * Returns what ranks a search, either bm25 or embedding.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.search-backend}
			 */
			public SearchBackend getSearchBackend() {
				return this.searchBackend;
			}

			/**
			 * Sets what ranks a search, either bm25 or embedding.
			 * @param searchBackend the value of
			 * {@code gatool.dev.experimental.dynamic-operations.search-backend}
			 */
			public void setSearchBackend(SearchBackend searchBackend) {
				this.searchBackend = searchBackend;
			}

			/**
			 * Returns how many tokens of search results one call returns.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.search-token-budget}
			 */
			public int getSearchTokenBudget() {
				return this.searchTokenBudget;
			}

			/**
			 * Sets how many tokens of search results one call returns.
			 * @param searchTokenBudget the value of
			 * {@code gatool.dev.experimental.dynamic-operations.search-token-budget}
			 */
			public void setSearchTokenBudget(int searchTokenBudget) {
				this.searchTokenBudget = searchTokenBudget;
			}

			/**
			 * Returns how many coordinates a search ranks before the budget cuts the
			 * list.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.ranked-hits}
			 */
			public int getRankedHits() {
				return this.rankedHits;
			}

			/**
			 * Sets how many coordinates a search ranks before the budget cuts the list.
			 * @param rankedHits the value of
			 * {@code gatool.dev.experimental.dynamic-operations.ranked-hits}
			 */
			public void setRankedHits(int rankedHits) {
				this.rankedHits = rankedHits;
			}

			/**
			 * Returns whether a mutation the model wrote runs.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.allow-mutations}
			 */
			public boolean isAllowMutations() {
				return this.allowMutations;
			}

			/**
			 * Sets whether a mutation the model wrote runs.
			 * @param allowMutations the value of
			 * {@code gatool.dev.experimental.dynamic-operations.allow-mutations}
			 */
			public void setAllowMutations(boolean allowMutations) {
				this.allowMutations = allowMutations;
			}

			/**
			 * Returns whether executeGraphql validates the document and returns it
			 * without calling the API, so a test or a benchmark reads the operation the
			 * model wrote.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.validate-only}
			 */
			public boolean isValidateOnly() {
				return this.validateOnly;
			}

			/**
			 * Sets whether executeGraphql validates the document and returns it without
			 * calling the API, so a test or a benchmark reads the operation the model
			 * wrote.
			 * @param validateOnly the value of
			 * {@code gatool.dev.experimental.dynamic-operations.validate-only}
			 */
			public void setValidateOnly(boolean validateOnly) {
				this.validateOnly = validateOnly;
			}

			/**
			 * Returns the deepest field nesting executeGraphql sends, counting a root
			 * field as one level.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.max-depth}
			 */
			public int getMaxDepth() {
				return this.maxDepth;
			}

			/**
			 * Sets the deepest field nesting executeGraphql sends, counting a root field
			 * as one level.
			 * @param maxDepth the value of
			 * {@code gatool.dev.experimental.dynamic-operations.max-depth}
			 */
			public void setMaxDepth(int maxDepth) {
				this.maxDepth = maxDepth;
			}

			/**
			 * Returns the most field selections executeGraphql sends, with every fragment
			 * spread counted where it is spread.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.max-fields}
			 */
			public int getMaxFields() {
				return this.maxFields;
			}

			/**
			 * Sets the most field selections executeGraphql sends, with every fragment
			 * spread counted where it is spread.
			 * @param maxFields the value of
			 * {@code gatool.dev.experimental.dynamic-operations.max-fields}
			 */
			public void setMaxFields(int maxFields) {
				this.maxFields = maxFields;
			}

			/**
			 * Returns the most aliased fields executeGraphql sends, which is how a
			 * document asks for one field many times.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.max-aliases}
			 */
			public int getMaxAliases() {
				return this.maxAliases;
			}

			/**
			 * Sets the most aliased fields executeGraphql sends, which is how a document
			 * asks for one field many times.
			 * @param maxAliases the value of
			 * {@code gatool.dev.experimental.dynamic-operations.max-aliases}
			 */
			public void setMaxAliases(int maxAliases) {
				this.maxAliases = maxAliases;
			}

			/**
			 * Returns whether a deprecated field is searchable, readable and routed
			 * through, and marked with the reason the schema gives when it is.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields}
			 */
			public boolean isIncludeDeprecatedFields() {
				return this.includeDeprecatedFields;
			}

			/**
			 * Sets whether a deprecated field is searchable, readable and routed through,
			 * and marked with the reason the schema gives when it is.
			 * @param includeDeprecatedFields the value of
			 * {@code gatool.dev.experimental.dynamic-operations.include-deprecated-fields}
			 */
			public void setIncludeDeprecatedFields(boolean includeDeprecatedFields) {
				this.includeDeprecatedFields = includeDeprecatedFields;
			}

			/**
			 * Returns the text put in front of a question before it is embedded, which
			 * some embedding models expect.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.question-prefix}
			 */
			public String getQuestionPrefix() {
				return this.questionPrefix;
			}

			/**
			 * Sets the text put in front of a question before it is embedded, which some
			 * embedding models expect.
			 * @param questionPrefix the value of
			 * {@code gatool.dev.experimental.dynamic-operations.question-prefix}
			 */
			public void setQuestionPrefix(String questionPrefix) {
				this.questionPrefix = questionPrefix;
			}

			/**
			 * Returns the text put in front of each schema field before it is embedded,
			 * joined as it is, the same way as the query prefix.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.field-prefix}
			 */
			public String getFieldPrefix() {
				return this.fieldPrefix;
			}

			/**
			 * Sets the text put in front of each schema field before it is embedded,
			 * joined as it is, the same way as the query prefix.
			 * @param fieldPrefix the value of
			 * {@code gatool.dev.experimental.dynamic-operations.field-prefix}
			 */
			public void setFieldPrefix(String fieldPrefix) {
				this.fieldPrefix = fieldPrefix;
			}

			/**
			 * Returns how many schema fields are sent to the embedding model in one call.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.embed-batch-size}
			 */
			public int getEmbedBatchSize() {
				return this.embedBatchSize;
			}

			/**
			 * Sets how many schema fields are sent to the embedding model in one call.
			 * @param embedBatchSize the value of
			 * {@code gatool.dev.experimental.dynamic-operations.embed-batch-size}
			 */
			public void setEmbedBatchSize(int embedBatchSize) {
				this.embedBatchSize = embedBatchSize;
			}

			/**
			 * Returns where embedded fields are cached, so a large schema is embedded
			 * once.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.vector-cache-directory}
			 */
			public String getVectorCacheDirectory() {
				return this.vectorCacheDirectory;
			}

			/**
			 * Returns the scopes a caller needs for the three dynamic tools, all of them,
			 * on top of the baseline scopes.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.required-scopes}
			 */
			public @Nullable List<String> getRequiredScopes() {
				return this.requiredScopes;
			}

			/**
			 * Sets the scopes a caller needs for the three dynamic tools, all of them, on
			 * top of the baseline scopes.
			 * @param requiredScopes the value of
			 * {@code gatool.dev.experimental.dynamic-operations.required-scopes}
			 */
			public void setRequiredScopes(@Nullable List<String> requiredScopes) {
				this.requiredScopes = requiredScopes;
			}

			/**
			 * Sets where embedded fields are cached, so a large schema is embedded once.
			 * @param vectorCacheDirectory the value of
			 * {@code gatool.dev.experimental.dynamic-operations.vector-cache-directory}
			 */
			public void setVectorCacheDirectory(String vectorCacheDirectory) {
				this.vectorCacheDirectory = vectorCacheDirectory;
			}

			/**
			 * Returns the description an application set for the searchSchema tool.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.search-schema-description},
			 * or null while GATool writes the description
			 */
			public @Nullable String getSearchSchemaDescription() {
				return this.searchSchemaDescription;
			}

			/**
			 * Sets the whole description of the searchSchema tool, replacing the one
			 * GATool writes.
			 * @param searchSchemaDescription the value of
			 * {@code gatool.dev.experimental.dynamic-operations.search-schema-description}
			 */
			public void setSearchSchemaDescription(@Nullable String searchSchemaDescription) {
				this.searchSchemaDescription = searchSchemaDescription;
			}

			/**
			 * Returns the description an application set for the introspectType tool.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.introspect-type-description},
			 * or null while GATool writes the description
			 */
			public @Nullable String getIntrospectTypeDescription() {
				return this.introspectTypeDescription;
			}

			/**
			 * Sets the whole description of the introspectType tool, replacing the one
			 * GATool writes.
			 * @param introspectTypeDescription the value of
			 * {@code gatool.dev.experimental.dynamic-operations.introspect-type-description}
			 */
			public void setIntrospectTypeDescription(@Nullable String introspectTypeDescription) {
				this.introspectTypeDescription = introspectTypeDescription;
			}

			/**
			 * Returns the description an application set for the executeGraphql tool.
			 * @return the value of
			 * {@code gatool.dev.experimental.dynamic-operations.execute-graphql-description},
			 * or null while GATool writes the description
			 */
			public @Nullable String getExecuteGraphqlDescription() {
				return this.executeGraphqlDescription;
			}

			/**
			 * Sets the whole description of the executeGraphql tool, replacing the one
			 * GATool writes.
			 * @param executeGraphqlDescription the value of
			 * {@code gatool.dev.experimental.dynamic-operations.execute-graphql-description}
			 */
			public void setExecuteGraphqlDescription(@Nullable String executeGraphqlDescription) {
				this.executeGraphqlDescription = executeGraphqlDescription;
			}

		}

	}

}
