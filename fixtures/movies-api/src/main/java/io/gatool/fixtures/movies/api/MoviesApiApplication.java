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

package io.gatool.fixtures.movies.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.PropertySource;

/**
 * The movie API that the tests run as a separate application.
 *
 * <p>
 * The settings sit in {@code movies-api.properties} and arrive through
 * {@link PropertySource}, so this jar ships without a file named
 * {@code application.properties}. A test module that depends on this fixture therefore
 * keeps its own {@code application.properties} to itself.
 *
 * @author Željko Kozina
 */
@SpringBootApplication
@PropertySource("classpath:movies-api.properties")
public class MoviesApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(MoviesApiApplication.class, args);
	}

}
