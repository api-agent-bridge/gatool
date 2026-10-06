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

package io.gatool.boot.internal.operation;

import org.junit.jupiter.api.Test;
import org.springframework.graphql.support.ResourceDocumentSource;

import static org.assertj.core.api.Assertions.assertThat;

// GATool follows Spring for GraphQL's operation file extensions. The reader takes them
// from this constant, and gatool-core writes the same two out because the enforcer bans
// Spring there, so each side pins its own end and a change in Spring fails here.
class OperationFileExtensionsTests {

	@Test
	void extensions_springForGraphQl_shouldStillBeTheTwoOperationExtensions() {
		assertThat(ResourceDocumentSource.FILE_EXTENSIONS).containsExactly(".graphql", ".gql");
	}

}
