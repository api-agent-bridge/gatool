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

/**
 * The test-scoped starter for a team's own tests.
 *
 * <p>
 * This module carries dependencies: {@code gatool-spring-boot-test} with the two
 * assertions, and {@code spring-boot-starter-test} with JUnit, AssertJ and Spring's test
 * support. An application adds the starter in test scope, asserts that its operation
 * files validate with {@code OperationFilesAssert.assertValid(...)}, and guards its
 * public tool names with {@code ToolContractSnapshot.of(catalog).assertMatches(...)}.
 *
 * <p>
 * The code lives in {@code gatool-spring-boot-test}, kept apart from this starter the way
 * {@code spring-boot-test} sits apart from {@code spring-boot-starter-test}, so test
 * support stays out of the runtime jars.
 */
package io.gatool.boot.test.starter;
