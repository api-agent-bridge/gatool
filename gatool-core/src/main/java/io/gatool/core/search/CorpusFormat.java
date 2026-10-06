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

package io.gatool.core.search;

/**
 * How one schema coordinate is rendered into the text a search index holds and a search
 * result shows.
 *
 * <p>
 * The choice matters as much as the ranking, because it decides what words a question can
 * match. A rendering carrying the field's description and its arguments gives a search
 * far more to work with than the coordinate alone.
 *
 * <p>
 * The corpus stays on the server, so its size costs memory there. What a model reads is
 * the ranked slice inside the search budget, and a longer rendering means fewer
 * coordinates fit in it.
 *
 * @author Željko Kozina
 */
public enum CorpusFormat {

	/**
	 * The coordinate alone, {@code Movie.rating}. Kept for the comparison, since word
	 * statistics have very little to match on.
	 */
	RAW,

	/**
	 * One sentence naming the field, its owner type, its return type and its description.
	 */
	GLOSS,

	/**
	 * The field definition in schema syntax, with its description, its arguments and its
	 * return type. The default, because it is the syntax a model already reads and the
	 * only one of the three that names arguments.
	 */
	SDL

}
