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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.boot.internal.schema.SchemaUrlReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message that names {@code gatool.api.schema.location} prints it through
 * {@code SchemaUrlReader.printable}, which keeps the scheme, the host, the port and the
 * path. Two URLs need care there: one the URI parser refuses outright, such as a password
 * holding {@code |}, where the userinfo and the query still have to go, and one whose
 * host the parser refuses, such as a host with an underscore, where the authority still
 * has to be printed. The failure for a URL the client cannot build a request from names
 * the URL beside the header, because the request is built from both.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchemaUrlPrintableTests {

	private static final String QUERY_KEY = "QUERYKEY";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://127.0.0.1:1/graphql");

	@Test
	void startup_passwordHoldingACharacterTheUriParserRefuses_shouldKeepItOutOfTheFailureAndTheLog(@TempDir Path cache,
			CapturedOutput output) {
		String location = "http://user:s3cr|et@127.0.0.1:1/schema?key=" + QUERY_KEY;

		this.contextRunner
			.withPropertyValues("gatool.api.schema.location=" + location,
					"gatool.api.schema.cache-directory=" + cache.toAbsolutePath())
			.run((context) -> {
				assertThat(context).hasFailed();
				String messages = messagesOf(context.getStartupFailure());
				List<String> lines = output.getAll().lines().filter((line) -> line.contains("s3cr|et")).toList();

				assertThat(messages).contains("http://127.0.0.1:1/schema")
					.contains("the URL or the header")
					.doesNotContain(QUERY_KEY)
					.doesNotContain("s3cr|et");
				assertThat(lines).as("log lines naming the password").isEmpty();
			});
	}

	@Test
	void printable_hostOrUrlTheUriParserRefuses_shouldKeepTheHostAndThePathWithoutTheUserinfo() {
		assertThat(SchemaUrlReader.printable("https://user:pw@my_host.example.com/schema"))
			.isEqualTo("https://my_host.example.com/schema");
		assertThat(SchemaUrlReader.printable("https://user:s3cr|et@registry.example.com/schema?key=abc"))
			.isEqualTo("https://registry.example.com/schema");
		// The last @ ahead of the path ends the userinfo, so a password holding one
		// goes with it.
		assertThat(SchemaUrlReader.printable("https://user:p@ss|w@registry.example.com:8443/schema?key=abc"))
			.isEqualTo("https://registry.example.com:8443/schema");
	}

	private static String messagesOf(Throwable failure) {
		List<String> messages = new ArrayList<>();
		for (Throwable cause = failure; cause != null && messages.size() < 10; cause = cause.getCause()) {
			messages.add(cause.getClass().getSimpleName() + ": " + cause.getMessage());
		}
		return String.join(" <- ", messages);
	}

}
