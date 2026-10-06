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

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A server that a test starts holds its port on 127.0.0.1.
 *
 * <p>
 * Tomcat binds every address while {@code server.address} is unset. macOS accepts that
 * bind on a port another program holds on 127.0.0.1 alone, and it delivers a call to
 * 127.0.0.1 on that port to the other program. An IDE holds about fourteen such ports, so
 * a test server can be given one of them, and the test then reads the IDE's web page
 * where it expects the MCP endpoint. A bind that names 127.0.0.1 is refused on a port
 * held there, so the operating system hands out the next free one.
 *
 * <p>
 * {@code application.properties} in this module's test resources sets the address.
 * {@code SpringApplication} reads the file in each {@code @SpringBootTest} context, in
 * each application a test builds itself and in the hosts that run in a JVM of their own,
 * whose classpath carries the test resources. The context runners of the slice tests
 * leave the file unread, and they run without a port. Each test here binds the server's
 * port on 127.0.0.1 a second time, which the operating system refuses while the server
 * holds the port there, and which macOS accepts beside a server bound to every address.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.ai.mcp.server.protocol=STATELESS",
				"gatool.mcp.security.unsafe.allow-mcp-calls-without-authentication=true",
				"gatool.api.url=http://127.0.0.1:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls" })
class TestServerAddressTests {

	@LocalServerPort
	private int port;

	@Test
	void testContext_onARandomPort_shouldHoldThePortOnLoopback() {
		assertHeldOnLoopback(this.port);
	}

	@Test
	void movieApi_onARandomPort_shouldHoldThePortOnLoopback() {
		assertHeldOnLoopback(URI.create(MoviesApiServer.graphQlUrl()).getPort());
	}

	@Test
	void movieApi_urlForGATool_shouldNameTheAddressTheApiListensOn() {
		assertThat(URI.create(MoviesApiServer.graphQlUrl()).getHost()).isEqualTo("127.0.0.1");
	}

	private static void assertHeldOnLoopback(int port) {
		Throwable refusal = catchThrowable(() -> new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close());

		assertThat(refusal).as("what a second bind of 127.0.0.1:" + port + " met beside the server under test")
			.isInstanceOf(BindException.class);
	}

	@SpringBootApplication
	static class SkeletonApplication {

	}

}
