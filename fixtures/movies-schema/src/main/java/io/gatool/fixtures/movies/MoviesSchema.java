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

package io.gatool.fixtures.movies;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Gives access to the movie schema file.
 *
 * @author Željko Kozina
 */
public final class MoviesSchema {

	/** The classpath location of the schema file. */
	public static final String RESOURCE = "io/gatool/fixtures/movies/movies.graphqls";

	private MoviesSchema() {
	}

	/**
	 * Reads the schema file.
	 * @return the schema definition language text of the movie schema
	 */
	public static String sdl() {
		try (InputStream schema = MoviesSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
			if (schema == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the classpath");
			}
			return new String(schema.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
