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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A decimal with more digits than binary64 holds reaches the model as the API sent it.
 * The remote executor reads every 2xx body with GATool's own copy of Boot's mapper, which
 * reads a JSON float as a {@code BigDecimal}, so
 * {@code spring.jackson.deserialization.use-big-decimal-for-floats} is unnecessary for
 * this and the application's own mapper stays as it is.
 *
 * <p>
 * An MCP argument reaches the API the same way. Spring AI's transport parses the request
 * body with the {@code mcpServerJsonMapper} bean before GATool sees the call, and that
 * bean also enables {@code USE_BIG_DECIMAL_FOR_FLOATS}, so a decimal argument keeps every
 * digit the model sent. A float past the range of a double reaches the API as a number,
 * where a mapper that reads floats as doubles would send the string {@code "Infinity"}.
 *
 * <p>
 * The API records the {@code amount} variable as it arrived on the wire and echoes it
 * back beside decimals of its own, so one call shows both directions. The MCP response is
 * read as text, because an SDK client would parse the numbers itself and hide the exact
 * form the API received.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.schema.location=classpath:decimal-precision/price.graphqls",
				"gatool.mcp.operations.locations=classpath:decimal-precision/ops/",
				"gatool.in-process.operations.locations=optional:classpath*:gatool/none/" })
class DecimalPrecisionOverHttpTests {

	private static final String AMOUNT_SENT = "1234567890.123456789";

	private static final String AMOUNT_PAST_A_DOUBLE = "1E400";

	private static final String CALL = call(AMOUNT_SENT);

	private static final String CALL_PAST_A_DOUBLE = call(AMOUNT_PAST_A_DOUBLE);

	private static String call(String amount) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"price\","
				+ "\"arguments\":{\"amount\":" + amount + "}}}";
	}

	// The API's own decimals: a value past binary64, the echo of the amount it
	// received, a decimal binary64 cannot hold exactly, a notation, a whole number
	// past a long, an underflow and an ordinary money amount.
	private static final String API_ANSWER = "{\"data\":{\"price\":{\"value\":1234567890.123456789,\"echo\":%s,"
			+ "\"meta\":{\"dec\":0.10000000000000000555,\"exp\":1E+2,\"big\":123456789012345678901234567890,"
			+ "\"tiny\":1E-400,\"small\":19.99}}}}";

	private static final Pattern AMOUNT = Pattern.compile("\"amount\"\\s*:\\s*([^,}\\s]+)");

	private static final AtomicReference<String> RECEIVED_AMOUNT = new AtomicReference<>("unset");

	private static HttpServer api;

	@LocalServerPort
	private int port;

	@DynamicPropertySource
	static void apiUrl(DynamicPropertyRegistry registry) throws IOException {
		if (api == null) {
			api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			api.createContext("/graphql", (exchange) -> {
				String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				Matcher amount = AMOUNT.matcher(request);
				String received = amount.find() ? amount.group(1) : "absent";
				RECEIVED_AMOUNT.set(received);
				byte[] body = API_ANSWER.formatted(received).getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			});
			api.start();
		}
		registry.add("gatool.api.url", () -> "http://127.0.0.1:" + api.getAddress().getPort() + "/graphql");
	}

	@Test
	void callTool_aDecimalPastBinary64_shouldReachTheApiAndTheModelWithEveryDigit() throws Exception {
		HttpResponse<String> response = callPrice(CALL);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(RECEIVED_AMOUNT.get()).as("the amount the API received").isEqualTo("1234567890.123456789");
		assertThat(response.body()).as("the result the model receives")
			.contains(inText("value", "1234567890.123456789"))
			.contains(inText("echo", "1234567890.123456789"))
			.contains(inText("dec", "0.10000000000000000555"))
			.contains(inText("exp", "1E+2"))
			.contains(inText("tiny", "1E-400"))
			.contains(inText("big", "123456789012345678901234567890"))
			.contains(inText("small", "19.99"));
	}

	@Test
	void callTool_aFloatPastTheRangeOfADouble_shouldReachTheApiAsANumber() throws Exception {
		HttpResponse<String> response = callPrice(CALL_PAST_A_DOUBLE);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(RECEIVED_AMOUNT.get()).as("the amount the API received").isEqualTo("1E+400");
	}

	/**
	 * Returns one field of the result as it sits inside the JSON-RPC body, where the tool
	 * text is a JSON string and its quotes are escaped.
	 */
	private static String inText(String field, String value) {
		return "\\\"" + field + "\\\":" + value;
	}

	private HttpResponse<String> callPrice(String call) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/mcp"))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("MCP-Protocol-Version", "2025-11-25")
			.timeout(Duration.ofSeconds(20))
			.POST(HttpRequest.BodyPublishers.ofString(call))
			.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
