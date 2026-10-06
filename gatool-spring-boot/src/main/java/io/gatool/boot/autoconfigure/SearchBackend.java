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
 * What ranks a {@code searchSchema} call, for
 * {@code gatool.dev.experimental.dynamic-operations.search-backend}.
 *
 * <p>
 * The two differ in what they find and in what they ask of an application. Matching words
 * finds a field whose declaration uses the words a question used. Matching meaning finds
 * one that answers the question in other words, and it needs a model to do it.
 *
 * @author Željko Kozina
 */
public enum SearchBackend {

	/**
	 * Word statistics over a Lucene index, which runs without a service, a key or a
	 * model. The default.
	 */
	BM25,

	/**
	 * Cosine similarity over vectors from the application's own {@code EmbeddingModel}
	 * bean, which arrives with a Spring AI model starter such as
	 * {@code spring-ai-starter-model-ollama}. Startup stops when this is set and the
	 * application lacks that bean.
	 */
	EMBEDDING

}
