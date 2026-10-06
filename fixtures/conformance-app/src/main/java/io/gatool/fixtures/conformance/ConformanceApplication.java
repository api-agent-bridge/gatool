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

package io.gatool.fixtures.conformance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runs GATool's MCP server for the official MCP conformance suite.
 *
 * <p>
 * The suite calls a server over Streamable HTTP and checks each answer against revision
 * 2025-11-25. It names the tool each scenario calls, such as {@code test_image_content},
 * so {@link ConformanceTools} declares those tools with the results the scenarios
 * describe. One operation file adds a GATool tool beside them, which gives
 * {@code tools-list} a tool built from GraphQL.
 *
 * <p>
 * The application listens on loopback and accepts MCP calls without authentication,
 * because the suite's server command sends its requests without a bearer token.
 *
 * @author Željko Kozina
 */
@SpringBootApplication
public class ConformanceApplication {

	/**
	 * Starts the fixture.
	 * @param args the command line arguments
	 */
	public static void main(String[] args) {
		SpringApplication.run(ConformanceApplication.class, args);
	}

}
