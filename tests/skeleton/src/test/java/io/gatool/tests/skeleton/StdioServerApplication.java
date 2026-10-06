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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application the stdio tests launch as a process of its own.
 *
 * <p>
 * stdio carries JSON-RPC on stdout, so this has to run in a second JVM: a server sharing
 * the test JVM's streams could not be spoken to the way a client speaks to it, and the
 * console logging that would corrupt the stream is exactly what these tests watch for.
 */
@SpringBootApplication
public class StdioServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(StdioServerApplication.class, args);
	}

}
