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
 * Assertions for a team's own tests.
 *
 * <p>
 * {@link io.gatool.boot.test.OperationFilesAssert} runs the startup checks over the
 * operation files from a plain JUnit test, so a broken file fails the build that changed
 * it. {@link io.gatool.boot.test.ToolContractSnapshot} compares the tool contract an
 * application publishes with a committed snapshot, so a renamed tool or a changed input
 * schema is visible in review. Neither needs a running application, an issuer or a
 * reachable GraphQL API.
 */
@NullMarked
package io.gatool.boot.test;

import org.jspecify.annotations.NullMarked;
