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

package io.gatool.boot.mcp.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

/**
 * The names of the classes the security auto-configuration needs, kept in a class whose
 * imports leave Spring Security out.
 *
 * <p>
 * {@link GAToolMcpAutoConfiguration} probes these names before it lets an HTTP endpoint
 * start without the unsafe switch. The names stay out of
 * {@link GAToolMcpSecurityAutoConfiguration}, because reading a field there runs that
 * class's static initializer, which builds an {@code AnonymousAuthenticationToken}. On a
 * classpath without Spring Security, the one classpath where the probe matters, the read
 * would die on a {@code NoClassDefFoundError} for
 * {@code org.springframework.security.core.Authentication} ahead of the stop that names
 * the dependency to add.
 *
 * @author Željko Kozina
 */
final class McpSecurityClasses {

	/**
	 * Spring Security's {@code HttpSecurity}, the class the security auto-configuration's
	 * condition names first.
	 */
	static final String HTTP_SECURITY = "org.springframework.security.config.annotation.web.builders.HttpSecurity";

	/**
	 * The classes the security auto-configuration needs: {@code HttpSecurity},
	 * {@code SecurityFilterChain} and Boot's {@code ConditionalOnDefaultWebSecurity}.
	 */
	static final String[] REQUIRED_CLASSES = { HTTP_SECURITY, "org.springframework.security.web.SecurityFilterChain",
			"org.springframework.boot.security.autoconfigure.web.servlet.ConditionalOnDefaultWebSecurity" };

	private McpSecurityClasses() {
	}

	/**
	 * Whether every required class is on the classpath.
	 * @param classLoader the class loader to probe, or null for the default
	 * @return true where all three classes load
	 */
	static boolean allPresent(@Nullable ClassLoader classLoader) {
		for (String name : REQUIRED_CLASSES) {
			if (!ClassUtils.isPresent(name, classLoader)) {
				return false;
			}
		}
		return true;
	}

}
