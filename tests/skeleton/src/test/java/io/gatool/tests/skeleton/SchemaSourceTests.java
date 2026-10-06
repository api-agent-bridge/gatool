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

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.DefaultExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.fixtures.movies.MoviesSchema;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which schema the operation files are validated against, and saying so.
 *
 * <p>
 * An application that serves GraphQL and bridges a second API is an ordinary gateway.
 * Falling back to the application's own schema whenever
 * {@code gatool.api.schema.location} is unset, without looking at {@code gatool.api.url},
 * would validate the operation files against one API and send every call to another: the
 * tools would be published with full input schemas and every call would fail at runtime.
 * Startup names the schema, so the choice is visible.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchemaSourceTests {

	private static final String SCHEMA = "gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls";

	private static final String REMOTE_API = "gatool.api.url=http://localhost:1/graphql";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@Test
	void startup_remoteApiAndTheApplicationsOwnSchema_shouldStopAndNameBoth() {
		this.contextRunner.withPropertyValues(REMOTE_API)
			.withBean(GraphQlSource.class, SchemaSourceTests::localSource)
			.run((context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.hasMessageContaining("gatool.api.schema.location")
					.hasMessageContaining("http://localhost:1/graphql")
					.hasMessageContaining("cannot tell which one the operation files describe");
			});
	}

	@Test
	void startup_remoteApiWithItsSchemaNamed_shouldStartAndNameTheFile(CapturedOutput output) {
		this.contextRunner.withPropertyValues(REMOTE_API, SCHEMA)
			.withBean(GraphQlSource.class, SchemaSourceTests::localSource)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(GAToolCatalog.class).mcpTools()).isNotEmpty();
				// Naming the file is what makes the choice visible in a log.
				assertThat(output.getAll()).contains("GATool validates every operation file against")
					.contains("movies.graphqls");
			});
	}

	@Test
	void startup_embeddedModeWithoutARemoteApi_shouldUseTheApplicationsSchemaAndSaySo(CapturedOutput output) {
		this.contextRunner.withBean(GraphQlSource.class, SchemaSourceTests::localSource)
			.withBean(ExecutionGraphQlService.class, () -> new DefaultExecutionGraphQlService(localSource()))
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(output.getAll())
					.contains("GATool validates every operation file against the schema this application serves");
			});
	}

	@Test
	void startup_noSchemaAnywhere_shouldStopNamingBothWays() {
		this.contextRunner.withPropertyValues(REMOTE_API).run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("gatool.api.schema.location")
				.hasMessageContaining("add Spring for GraphQL to this application");
		});
	}

	// A GraphQlSource carrying the movies schema, which is what an application that
	// serves its own GraphQL API publishes.
	private static GraphQlSource localSource() {
		GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(MoviesSchema.sdl()),
				RuntimeWiring.MOCKED_WIRING);
		return new GraphQlSource() {

			@Override
			public GraphQL graphQl() {
				return GraphQL.newGraphQL(schema).build();
			}

			@Override
			public GraphQLSchema schema() {
				return schema;
			}
		};
	}

}
