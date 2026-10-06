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
 * The starter that serves curated GraphQL operation files as MCP tools.
 *
 * <p>
 * This module carries dependencies: Spring AI's MCP server on WebMVC,
 * {@code gatool-mcp-spring-boot} with the MCP auto-configuration and the adapter,
 * {@code gatool-spring-boot} underneath it with the shared auto-configuration, and Spring
 * Boot itself. An application adds the starter, writes operation files under
 * {@code gatool/mcp/}, and each one appears as a tool on the MCP endpoint.
 *
 * <p>
 * The classes an application uses directly live in those two modules:
 * {@code GAToolCatalog} and the properties under {@code GATool} in
 * {@code gatool-spring-boot}, and {@code McpServerSecurityConfigurer} and
 * {@code ToolRateLimiter} in {@code gatool-mcp-spring-boot}.
 */
package io.gatool.boot.mcp.starter;
