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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import io.gatool.boot.internal.search.EmbeddingSchemaSearch;
import io.gatool.boot.internal.search.LuceneSchemaSearch;
import io.gatool.core.search.CorpusEntry;
import io.gatool.core.search.SchemaSearch;

/**
 * Builds the ranking of the dynamic layer that
 * {@code gatool.dev.experimental.dynamic-operations.search-backend} names, on Lucene or
 * on the application's own {@link EmbeddingModel} bean.
 *
 * <p>
 * This class names {@link EmbeddingModel} in its signatures, which
 * {@link GAToolAutoConfiguration} cannot, because the JVM resolves every type a declared
 * method of the auto-configuration names while spring-ai-model is optional there. This
 * class is loaded when a method of it is called, which happens once the class is known to
 * be present.
 *
 * @author Željko Kozina
 */
final class SchemaSearchBuilding {

	private SchemaSearchBuilding() {
	}

	/**
	 * Returns the application's {@code EmbeddingModel} beans, looked up by class name so
	 * that the auto-configuration stays loadable without spring-ai-model.
	 * @param beanFactory the bean factory of the application
	 * @param classLoader the class loader the {@code EmbeddingModel} class is looked up
	 * in
	 * @return the provider of the application's {@code EmbeddingModel} beans
	 */
	// Spring resolves the generic type of an ObjectProvider parameter through the
	// method's signature, which fails with TypeNotPresentException when the type's jar
	// is missing, so the provider is asked of the bean factory instead. Without the
	// class, the provider answers the way it answers for an application without such
	// a bean.
	@SuppressWarnings("unchecked")
	static ObjectProvider<EmbeddingModel> embeddingModels(ConfigurableListableBeanFactory beanFactory,
			@Nullable ClassLoader classLoader) {
		if (!ClassUtils.isPresent(GAToolAutoConfiguration.EMBEDDING_MODEL, classLoader)) {
			ObjectProvider<Object> empty = new ObjectProvider<>() {

				@Override
				public Object getObject() {
					throw new NoSuchBeanDefinitionException(GAToolAutoConfiguration.EMBEDDING_MODEL,
							"spring-ai-model is missing from the classpath, so an EmbeddingModel bean cannot exist "
									+ "in this application");
				}

				@Override
				public Stream<Object> stream() {
					return Stream.empty();
				}

				@Override
				public Stream<Object> orderedStream() {
					return Stream.empty();
				}
			};
			return (ObjectProvider<EmbeddingModel>) (ObjectProvider<?>) empty;
		}
		return beanFactory.<EmbeddingModel>getBeanProvider(ResolvableType
			.forClass(ClassUtils.resolveClassName(GAToolAutoConfiguration.EMBEDDING_MODEL, classLoader)));
	}

	/**
	 * Builds the ranking the configured search backend names, and refuses bm25 when
	 * either Lucene jar is missing from the classpath.
	 * @param corpus the entries the search ranks, one per schema coordinate
	 * @param settings the dynamic operation settings, which name the search backend
	 * @param embeddingModel the application's embedding model beans, for the embedding
	 * backend
	 * @param classLoader the class loader the Lucene classes are looked up in
	 * @return the search the configured backend names
	 */
	// The class loader is the context's, the one Spring Boot's own class conditions read,
	// so a test that filters a Lucene class out of it meets the same refusal as a
	// deployment that left the jar out. One class of each jar is looked up: the searcher
	// from lucene-core, and the filter that splits an identifier into words from
	// lucene-analysis-common, without which the index builds and the first search fails
	// with a NoClassDefFoundError.
	static SchemaSearch buildDefaultSearch(List<CorpusEntry> corpus,
			GAToolDevProperties.Experimental.DynamicOperations settings, ObjectProvider<EmbeddingModel> embeddingModel,
			@Nullable ClassLoader classLoader) {
		if (settings.getSearchBackend() == SearchBackend.EMBEDDING) {
			return buildEmbeddingSearch(corpus, settings, embeddingModel);
		}
		List<String> missing = new ArrayList<>();
		if (!ClassUtils.isPresent("org.apache.lucene.search.IndexSearcher", classLoader)) {
			missing.add("org.apache.lucene:lucene-core");
		}
		if (!ClassUtils.isPresent("org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter", classLoader)) {
			missing.add("org.apache.lucene:lucene-analysis-common");
		}
		if (!missing.isEmpty()) {
			throw new InvalidConfigurationPropertyValueException("gatool.dev.experimental.generate-tools",
					ToolGeneration.DYNAMIC_THREE_STEP,
					"searchSchema ranks with Lucene, and " + String.join(" and ", missing)
							+ ((missing.size() == 1) ? " is" : " are")
							+ " missing from the classpath. The starters leave both jars out, because this layer "
							+ "is off by default, so add org.apache.lucene:lucene-core and "
							+ "org.apache.lucene:lucene-analysis-common to your build, or publish a SchemaSearch "
							+ "bean of your own.");
		}
		return new LuceneSchemaSearch(corpus);
	}

	// Returns the ranking that embeds the schema corpus with the application's own
	// EmbeddingModel bean.
	//
	// The model comes from the application, because choosing one is choosing a provider,
	// an account and a bill. GATool does not ship an embedding dependency, and the
	// refusal names what to add.
	private static SchemaSearch buildEmbeddingSearch(List<CorpusEntry> corpus,
			GAToolDevProperties.Experimental.DynamicOperations settings,
			ObjectProvider<EmbeddingModel> embeddingModel) {
		// getIfUnique answers a primary bean among several, and null where the
		// application publishes zero, or several without a primary. getIfAvailable throws
		// Spring's own NoUniqueBeanDefinitionException for several, which leaves both the
		// property and the way out unnamed. Two model starters each publish an
		// EmbeddingModel, so several is ordinary.
		EmbeddingModel model = embeddingModel.getIfUnique();
		if (model == null) {
			// The lambda takes an Object, because javac writes a lambda as a method of
			// this class, and one whose parameter is EmbeddingModel fails the whole
			// auto-configuration with a NoClassDefFoundError where spring-ai-model is
			// absent.
			List<String> candidates = embeddingModel.stream().map((Object bean) -> bean.getClass().getName()).toList();
			if (!candidates.isEmpty()) {
				throw new InvalidConfigurationPropertyValueException(
						"gatool.dev.experimental.dynamic-operations.search-backend", settings.getSearchBackend(),
						"this ranks with one EmbeddingModel bean, and this application publishes " + candidates.size()
								+ ": " + String.join(", ", candidates) + ". Mark the one to "
								+ "rank with @Primary, or publish a SchemaSearch bean of your own.");
			}
			throw new InvalidConfigurationPropertyValueException(
					"gatool.dev.experimental.dynamic-operations.search-backend", settings.getSearchBackend(),
					"this ranks with an EmbeddingModel bean, which this application does not publish. Add a "
							+ "Spring AI model starter such as spring-ai-starter-model-ollama and configure it, "
							+ "publish a bean of your own, or use bm25, which ranks without a model.");
		}
		int batchSize = settings.getEmbedBatchSize();
		if (batchSize < 1) {
			throw new InvalidConfigurationPropertyValueException(
					"gatool.dev.experimental.dynamic-operations.embed-batch-size", batchSize,
					"GATool sends at least one schema field to the model in a call.");
		}
		Path cacheDirectory = CacheDirectories.resolve(settings.getVectorCacheDirectory(),
				GAToolProperties.underTemporaryDirectory("vectors"),
				"gatool.dev.experimental.dynamic-operations.vector-cache-directory",
				"the schema's fields are embedded at every startup");
		return new EmbeddingSchemaSearch(corpus, model, settings.getQuestionPrefix(), settings.getFieldPrefix(),
				batchSize, cacheDirectory);
	}

}
