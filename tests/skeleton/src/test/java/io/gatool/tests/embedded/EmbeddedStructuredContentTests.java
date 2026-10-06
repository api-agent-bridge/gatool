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

package io.gatool.tests.embedded;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.Value;
import graphql.scalars.ExtendedScalars;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.GraphQLScalarType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In embedded mode the text and the structured content of one call agree, which is what a
 * tool publishing an output schema promises.
 *
 * <p>
 * The text is written by {@code GraphQlResultWriter} with a copy of Boot's mapper, which
 * carries the application's {@code JacksonModule} beans and keeps nulls. The structured
 * content is read back from the text, so the two halves carry the same values whichever
 * mapper the transport writes with. Serialising the envelope map with the transport's
 * {@code mcpServerJsonMapper} bean would turn a {@code Long} the application writes as a
 * string into a number, drop the scale of a {@code BigDecimal} and leave a null field
 * out.
 *
 * <p>
 * The schema's {@code Money} and {@code Instant} scalars hand the Java object to the
 * result, and a {@code JacksonModule} bean writes a {@code Long} as a string, the way an
 * application does for JavaScript clients. The test sits outside the skeleton package,
 * because the application the stdio tests launch scans that package, and these beans must
 * stay out of it.
 */
@SpringBootTest(classes = EmbeddedStructuredContentTests.PriceApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"spring.graphql.schema.locations=classpath:embedded-structured-content/",
				"gatool.mcp.operations.locations=classpath:embedded-structured-content/ops/",
				// The skeleton's default in-process folder holds movie operations, which
				// this schema cannot validate, so both lists read the same folder.
				"gatool.in-process.operations.locations=classpath:embedded-structured-content/ops/",
				"gatool.results.publish-output-schema=true",
				// The security auto-configurations stay out, so POST /graphql answers
				// without a credential and shows the JSON the application itself serves.
				"spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.web.servlet"
						+ ".ServletWebSecurityAutoConfiguration,"
						+ "org.springframework.boot.security.autoconfigure.web.servlet"
						+ ".SecurityFilterAutoConfiguration" })
class EmbeddedStructuredContentTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	private int port;

	@Test
	void toolCall_scalarsReturningJavaObjects_shouldWriteTheSameValuesAsTextAndAsStructuredContent() {
		String response = client().post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("MCP-Protocol-Version", "2025-11-25")
			.body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"priceCard\","
					+ "\"arguments\":{}}}")
			.retrieve()
			.body(String.class);
		String graphql = client().post()
			.uri("/graphql")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"query\":\"{ priceCard { price at version } }\"}")
			.retrieve()
			.body(String.class);

		JsonNode result = JSON.readTree(response).path("result");
		String text = result.path("content").path(0).path("text").asString();
		JsonNode structured = result.path("structuredContent");
		JsonNode textAsTree = JSON.readTree(text);

		assertThat(result.path("isError").asBoolean()).isFalse();
		// The text follows the application's own JSON: the module writes the Long as a
		// string, the BigDecimal keeps its scale and the null field stays.
		assertThat(text).contains("\"version\":\"9007199254740993\"")
			.contains("\"amount\":12.50")
			.contains("\"note\":null");
		assertThat(JSON.readTree(graphql).path("data")).isEqualTo(textAsTree.path("data"));
		// The structured content carries the same string, the same null and the same
		// scale, because it is read back from the text.
		assertThat(structured).isEqualTo(textAsTree);
		assertThat(structured.path("data").path("priceCard").path("version").isString()).isTrue();
		assertThat(structured.path("data").path("priceCard").path("price").has("note")).isTrue();
		assertThat(response).contains("\"amount\":12.50,\"currency\"");
		assertThat(structured.path("data").path("priceCard").path("at").asString()).isEqualTo("2026-09-25T10:15:30Z");
	}

	private RestClient client() {
		return RestClient.builder().baseUrl("http://127.0.0.1:" + this.port).build();
	}

	record Money(BigDecimal amount, String currency, @Nullable String note) {
	}

	record PriceCard(Money price, Instant at, Long version) {
	}

	@Controller
	static class PriceController {

		@QueryMapping
		PriceCard priceCard() {
			return new PriceCard(new Money(new BigDecimal("12.50"), "EUR", null), Instant.parse("2026-09-25T10:15:30Z"),
					9007199254740993L);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Wiring {

		/** A scalar whose coercing hands the record itself to the result. */
		@Bean
		RuntimeWiringConfigurer priceScalars() {
			GraphQLScalarType money = GraphQLScalarType.newScalar()
				.name("Money")
				.coercing(passThrough(Money.class))
				.build();
			GraphQLScalarType instant = GraphQLScalarType.newScalar()
				.name("Instant")
				.coercing(passThrough(Instant.class))
				.build();
			return (wiring) -> wiring.scalar(money).scalar(instant).scalar(ExtendedScalars.GraphQLLong);
		}

		/**
		 * The kind of module an application registers for JavaScript clients: a Long as a
		 * string.
		 */
		@Bean
		JacksonModule longsAsStrings() {
			return new SimpleModule("longs-as-strings").addSerializer(Long.class, ToStringSerializer.instance);
		}

		private static <T> Coercing<T, T> passThrough(Class<T> type) {
			return new Coercing<>() {

				@Override
				public @Nullable T serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale) {
					return type.cast(dataFetcherResult);
				}

				@Override
				public @Nullable T parseValue(Object input, GraphQLContext graphQLContext, Locale locale) {
					throw new CoercingParseValueException("input values are outside this test");
				}

				@Override
				public @Nullable T parseLiteral(Value<?> input, CoercedVariables variables,
						GraphQLContext graphQLContext, Locale locale) {
					throw new CoercingParseLiteralException("literals are outside this test");
				}
			};
		}

	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ PriceController.class, Wiring.class })
	static class PriceApplication {

	}

}
