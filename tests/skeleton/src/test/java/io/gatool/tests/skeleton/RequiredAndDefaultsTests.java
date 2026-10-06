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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.gatool.boot.GAToolCatalog;
import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.model.GATool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole grid of nullability, default and presence, at both levels.
 *
 * <p>
 * One rule decides {@code required}: a variable joins the list when its type is Non-Null
 * and it does not declare a default, and a field joins its Input Object's list on the
 * same terms. The rule is one line in each place and the cases it has to get right are
 * many, so this walks every cell of the grid, and it asserts what the GraphQL API
 * receives as well as what the schema says.
 */
class RequiredAndDefaultsTests {

	private static final String SDL = """
			input Page {
			  size: Int = 20
			  cursor: String
			  label: String!
			}

			type Query {
			  search(
			    text: String!
			    limit: Int = 10
			    label: String
			    note: String
			    page: Page
			    tags: [String!]
			  ): String!
			}
			""";

	// A schema of its own, because search takes each variable at one place. Here one
	// variable fills a Non-Null argument that declares a default and a nullable argument
	// without one, and another is used only inside a member of a union.
	private static final String VOTES_SDL = """
			enum Locale {
			  en
			  de
			}

			union VoteOrUser = Vote | User

			type Vote {
			  id: ID!
			  publishedBy(locales: [Locale!]): User
			  stage(current: Boolean! = false): String
			}

			type User {
			  name: String
			}

			type Query {
			  votes(first: Int, locales: [Locale!]! = [en]): [Vote!]!
			  newest(first: Int): VoteOrUser
			}
			""";

	// A schema of its own, because search is called without a directive. Here the
	// schema declares two directives for a field: one gives its argument a default, and
	// the other leaves its argument without one.
	private static final String DIRECTIVES_SDL = """
			directive @audience(status: String = "public") on FIELD

			directive @window(size: Int) on FIELD

			type Query {
			  search(text: String!, limit: Int = 10): String!
			}
			""";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AtomicReference<String> LAST_BODY = new AtomicReference<>("");

	private static HttpServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class));

	@BeforeAll
	static void startCapturingApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/graphql", (exchange) -> {
			LAST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = "{\"data\":{\"search\":\"ok\"}}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterAll
	static void stopCapturingApi() {
		server.stop(0);
	}

	@Test
	void required_theGridOfNullabilityAndDefault_shouldFollowGraphQlsOwnRule(@TempDir Path folder) throws Exception {
		JsonNode schema = schemaOf(folder, """
				query Search($text: String!, $withDefault: Int = 5, $nullable: String,
				        $nullableWithDefault: String = "x") {
				  search(text: $text, limit: $withDefault, label: $nullable, note: $nullableWithDefault)
				}
				""");

		// Non-Null without a default is the one obligation. A default makes the argument
		// optional, whichever way the type reads.
		assertThat(schema.path("required")).hasSize(1);
		assertThat(schema.path("required").get(0).asString()).isEqualTo("text");
	}

	@Test
	void required_insideAnInputObject_shouldFollowTheSameRule(@TempDir Path folder) throws Exception {
		JsonNode schema = schemaOf(folder, """
				query Search($text: String!, $page: Page) {
				  search(text: $text, page: $page)
				}
				""");

		JsonNode page = schema.path("properties").path("page").path("anyOf").get(0);
		// size declares a default and drops out, cursor is nullable, and label is
		// Non-Null without a default, so the outer object can be left out while the
		// inner field is obligatory once it is there.
		assertThat(page.path("required")).hasSize(1);
		assertThat(page.path("required").get(0).asString()).isEqualTo("label");
		assertThat(page.path("properties").path("size").path("default").asInt()).isEqualTo(20);
	}

	@Test
	void call_requiredArgumentProvided_shouldReachTheVariables(@TempDir Path folder) throws Exception {
		callWith(folder, Map.of("text", "arrival"));

		assertThat(variables()).containsEntry("text", "arrival");
	}

	@Test
	void call_optionalWithADefaultLeftOut_shouldLeaveTheVariableToTheApi(@TempDir Path folder) throws Exception {
		callWith(folder, Map.of("text", "arrival"));

		// The API applies the default the operation declares, so GATool leaves the
		// variable out and the two agree on one value.
		assertThat(variables()).doesNotContainKey("limit");
	}

	@Test
	void call_optionalWithADefaultProvided_shouldSendTheGivenValue(@TempDir Path folder) throws Exception {
		callWith(folder, map("text", "arrival", "limit", 3));

		assertThat(variables()).containsEntry("limit", 3);
	}

	@Test
	void call_nullForAVariableWithADefault_shouldLeaveTheDefaultInPlace(@TempDir Path folder) throws Exception {
		callWith(folder, map("text", "arrival", "limit", null));

		// Strict function calling makes a model send null for every value it would leave
		// out, and those nulls would replace the defaults the operation file declares.
		assertThat(variables()).doesNotContainKey("limit");
	}

	@Test
	void call_nullForAVariableTheSchemaGivesADefault_shouldLeaveTheDefaultInPlace(@TempDir Path folder)
			throws Exception {
		// This document does not declare a default for limit, and the schema declares one
		// on the argument it fills, so leaving the variable out makes the API apply its
		// 10. The schema's default is read as well as the document's, so the null is
		// dropped. A null that went through would give search null where an omitted
		// argument sees 10, and the call would succeed, so the model could not tell.
		write(folder, """
				query Search($text: String!, $limit: Int) {
				  search(text: $text, limit: $limit)
				}
				""");
		run(folder, (tool) -> tool.call(map("text", "arrival", "limit", null)));

		assertThat(variables()).doesNotContainKey("limit");
	}

	@Test
	void call_nullForAVariableFillingANonNullArgumentWithADefaultAndAnArgumentWithout_shouldLeaveTheVariableOut(
			@TempDir Path folder) throws Exception {
		// votes refuses the null every time, because its argument is Non-Null, so the
		// call works only with the variable left out: votes then takes its default and
		// publishedBy sees an absent argument.
		write(folder, VOTES_SDL, """
				query Votes($first: Int, $locales: [Locale!]) {
				  votes(first: $first, locales: $locales) { id publishedBy(locales: $locales) { name } }
				}
				""");
		run(folder, (tool) -> tool.call(map("first", 3, "locales", null)));

		assertThat(variables()).containsOnlyKeys("first");
	}

	@Test
	void call_nullForAVariableUsedOnlyInsideAMemberOfAUnion_shouldLeaveTheDefaultInPlace(@TempDir Path folder)
			throws Exception {
		// The argument sits under a union field, inside the inline fragment that names
		// the member, and its default protects the variable there as it does under an
		// object field.
		write(folder, VOTES_SDL, """
				query Newest($first: Int, $current: Boolean) {
				  newest(first: $first) {
				    __typename
				    ... on Vote { stage(current: $current) }
				  }
				}
				""");
		run(folder, (tool) -> tool.call(map("first", 3, "current", null)));

		assertThat(variables()).containsOnlyKeys("first");
	}

	@Test
	void call_nullForAVariableFillingADirectiveArgumentWithADefault_shouldLeaveTheDefaultInPlace(@TempDir Path folder)
			throws Exception {
		// The argument of the directive declares the default, so the variable left out
		// makes the API apply "public". Directive arguments are read as well as field
		// arguments, so the null is dropped.
		write(folder, DIRECTIVES_SDL, """
				query Search($text: String!, $status: String) {
				  search(text: $text) @audience(status: $status)
				}
				""");
		run(folder, (tool) -> tool.call(map("text", "arrival", "status", null)));

		assertThat(variables()).containsOnlyKeys("text");
	}

	@Test
	void call_nullForAVariableFillingAnArgumentWithADefaultAndADirectiveArgumentWithout_shouldCarryTheNull(
			@TempDir Path folder) throws Exception {
		// limit declares a default and size does not, and both take a null. Dropping the
		// null would turn the explicit null the model wrote for size into an absent
		// argument, so it goes to both places.
		write(folder, DIRECTIVES_SDL, """
				query Search($text: String!, $limit: Int) {
				  search(text: $text, limit: $limit) @window(size: $limit)
				}
				""");
		run(folder, (tool) -> tool.call(map("text", "arrival", "limit", null)));

		Map<String, Object> variables = variables();
		assertThat(variables).containsKey("limit");
		assertThat(variables.get("limit")).isNull();
	}

	@Test
	void call_nullForANullableVariableWithoutADefault_shouldCarryTheNull(@TempDir Path folder) throws Exception {
		callWith(folder, map("text", "arrival", "label", null));

		// GraphQL separates an explicit null from an absent variable, and a model
		// clearing a value is a real request.
		Map<String, Object> variables = variables();
		assertThat(variables).containsKey("label");
		assertThat(variables.get("label")).isNull();
	}

	@Test
	void call_optionalWithoutADefaultLeftOut_shouldStayOutOfTheVariables(@TempDir Path folder) throws Exception {
		callWith(folder, Map.of("text", "arrival"));

		assertThat(variables()).doesNotContainKey("label");
	}

	@Test
	void call_listVariable_shouldCarryTheListAsItArrived(@TempDir Path folder) throws Exception {
		callWith(folder, map("text", "arrival", "tags", List.of("scifi", "drama")));

		assertThat(variables()).containsEntry("tags", List.of("scifi", "drama"));
	}

	private JsonNode schemaOf(Path folder, String operation) throws Exception {
		write(folder, operation);
		AtomicReference<JsonNode> schema = new AtomicReference<>();
		run(folder, (tool) -> schema.set(JSON.readTree(tool.inputSchema())));
		return schema.get();
	}

	private void callWith(Path folder, Map<String, Object> arguments) throws Exception {
		write(folder, """
				query Search($text: String!, $limit: Int = 10, $label: String, $tags: [String!]) {
				  search(text: $text, limit: $limit, label: $label, tags: $tags)
				}
				""");
		run(folder, (tool) -> tool.call(arguments));
	}

	private void run(Path folder, Consumer<GATool> work) {
		this.contextRunner
			.withPropertyValues("gatool.api.url=http://127.0.0.1:" + server.getAddress().getPort() + "/graphql",
					"gatool.api.schema.location=file:" + schemaFile(folder),
					"gatool.mcp.operations.locations=file:" + folder.toAbsolutePath() + "/",
					// This module ships operation files of its own under the default
					// in-process location, and they describe another schema.
					"gatool.in-process.operations.locations=optional:classpath*:gatool/none/")
			.run((context) -> work.accept(context.getBean(GAToolCatalog.class).mcpTools().getFirst()));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> variables() {
		return (Map<String, Object>) JSON.readValue(LAST_BODY.get(), Map.class).get("variables");
	}

	// A HashMap, because a null value is one of the cases under test and Map.of
	// refuses one.
	private static Map<String, Object> map(String firstKey, Object firstValue, String secondKey, Object secondValue) {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(firstKey, firstValue);
		arguments.put(secondKey, secondValue);
		return arguments;
	}

	private static void write(Path folder, String operation) throws Exception {
		write(folder, SDL, operation);
	}

	private static void write(Path folder, String sdl, String operation) throws Exception {
		Files.writeString(folder.resolve("Search.graphql"), operation);
		Files.writeString(folder.resolve("search.graphqls"), sdl);
	}

	private static String schemaFile(Path folder) {
		return folder.toAbsolutePath() + "/search.graphqls";
	}

}
