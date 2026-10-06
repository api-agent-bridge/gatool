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

package io.gatool.tests.skeleton;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.test.ToolContractSnapshot;

/**
 * The test the README's "Checking your tools in CI" section tells a team to write, run
 * here against the published starter so the recipe stays true.
 *
 * <p>
 * The field, the method and its one line are the README's, word for word. The README
 * shows a bare {@code @SpringBootTest}, because an application's {@code application.yml}
 * names the API and the schema and its one {@code @SpringBootApplication} is found on its
 * own. This module lacks both, so the annotation names the configuration class and the
 * properties that an application keeps in its files. The operation locations stay at
 * their defaults, {@code gatool/mcp/} and {@code gatool/in-process/}, and the snapshot at
 * {@code src/test/resources/gatool-contract.json} holds the two tools they carry.
 */
@SpringBootTest(classes = ToolContractTests.RecipeApplication.class,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class ToolContractTests {

	@Autowired
	GAToolCatalog catalog;

	@DynamicPropertySource
	static void movieApi(DynamicPropertyRegistry registry) {
		registry.add("gatool.api.url", MoviesApiServer::graphQlUrl);
	}

	@Test
	void tools_shouldMatchTheSnapshot() {
		ToolContractSnapshot.of(this.catalog).assertMatches(Path.of("src/test/resources/gatool-contract.json"));
	}

	@SpringBootApplication
	static class RecipeApplication {

	}

}
