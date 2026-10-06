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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.gatool.boot.autoconfigure.GAToolAutoConfiguration;
import io.gatool.core.search.SchemaSearch;
import io.gatool.core.search.SearchHit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application's own closeable {@code SchemaSearch} bean is closed once.
 *
 * <p>
 * GATool registers a disposal callback for the Lucene index it builds itself, which is
 * created inside a bean method and is not a bean. An application's own bean goes without
 * the callback, because Spring closes it already, and a second registration would run its
 * {@code close()} twice at shutdown.
 */
class OwnSchemaSearchDisposalTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
				GAToolAutoConfiguration.class))
		.withPropertyValues("gatool.api.url=http://localhost:1/graphql",
				"gatool.api.schema.location=classpath:io/gatool/fixtures/movies/movies.graphqls",
				"gatool.dev.experimental.generate-tools=dynamic-three-step");

	@Test
	void shutdown_applicationsOwnCloseableSchemaSearch_shouldCloseItOnce() {
		CountingSearch.CLOSED.set(0);

		this.contextRunner.withUserConfiguration(OwnSearch.class).run((context) -> assertThat(context).hasNotFailed());

		assertThat(CountingSearch.CLOSED.get()).isEqualTo(1);
	}

	@Configuration(proxyBeanMethods = false)
	static class OwnSearch {

		@Bean
		SchemaSearch ownSearch() {
			return new CountingSearch();
		}

	}

	static final class CountingSearch implements SchemaSearch, AutoCloseable {

		static final AtomicInteger CLOSED = new AtomicInteger();

		@Override
		public List<SearchHit> search(String question, int maxHits) {
			return List.of();
		}

		@Override
		public void close() {
			CLOSED.incrementAndGet();
		}

	}

}
