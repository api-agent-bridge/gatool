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
 * The starter that hands curated GraphQL operation files to a Spring AI
 * {@code ChatClient} as in-process tools.
 *
 * <p>
 * This module carries dependencies: {@code spring-ai-model} with Spring AI's tool types,
 * {@code gatool-in-process-spring-boot} with the in-process auto-configuration and the
 * adapter, {@code gatool-spring-boot} underneath it with the shared auto-configuration,
 * and Spring Boot itself. Every MCP artifact stays out, so an application that wants
 * in-process tools alone keeps the MCP server away. An application adds the starter,
 * writes operation files under {@code gatool/in-process/}, and attaches the
 * {@code GAToolCallbacks} bean to its {@code ChatClient}.
 *
 * <p>
 * The classes an application uses directly live in those two modules:
 * {@code GAToolCatalog} and the properties under {@code GATool} in
 * {@code gatool-spring-boot}, and {@code GAToolCallbacks} in
 * {@code gatool-in-process-spring-boot}.
 */
package io.gatool.boot.inprocess.starter;
