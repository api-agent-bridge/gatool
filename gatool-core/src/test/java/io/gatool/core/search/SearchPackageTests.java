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

package io.gatool.core.search;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public types of {@code io.gatool.core.search}, read from the compiled classes
 * instead of a list written by hand.
 *
 * <p>
 * An application ranks with four of them: {@link SchemaSearch} is the seat it implements,
 * {@link SearchHit} is what a ranking returns, and a {@link CorpusEntry} rendered in a
 * {@link CorpusFormat} is what {@link SchemaCorpus#of} builds for it to rank. Every other
 * type GATool uses to build the corpus lives in {@code io.gatool.core.internal.search},
 * so a change to one of them does not widen what a patch release has to keep compatible.
 */
class SearchPackageTests {

	@Test
	void publicTypes_ofTheSearchPackage_shouldBeTheFiveAnApplicationRanksWith() throws URISyntaxException {
		assertThat(publicSimpleNamesOf(SchemaCorpus.class)).containsExactlyInAnyOrder("SchemaSearch", "SearchHit",
				"CorpusEntry", "CorpusFormat", "SchemaCorpus");
	}

	// Reads the directory SchemaCorpus compiled into, instead of asking the class
	// loader for the package. Surefire puts test output ahead of main output on the
	// class path, and both share this package name, so the class loader could
	// return the wrong directory.
	private static Set<String> publicSimpleNamesOf(Class<?> classInPackage) throws URISyntaxException {
		CodeSource codeSource = Objects.requireNonNull(classInPackage.getProtectionDomain().getCodeSource(),
				"No code source for " + classInPackage);
		File classesRoot = new File(codeSource.getLocation().toURI());
		File packageDirectory = new File(classesRoot, classInPackage.getPackageName().replace('.', '/'));
		String[] classFileNames = packageDirectory.list((directory, name) -> name.endsWith(".class")
				&& !name.contains("$") && !name.equals("package-info.class"));
		Objects.requireNonNull(classFileNames, "Not a directory: " + packageDirectory);
		return Arrays.stream(classFileNames)
			.map((fileName) -> loadFromPackage(classInPackage, fileName))
			.filter((type) -> Modifier.isPublic(type.getModifiers()))
			.map(Class::getSimpleName)
			.collect(Collectors.toUnmodifiableSet());
	}

	private static Class<?> loadFromPackage(Class<?> classInPackage, String classFileName) {
		String simpleName = classFileName.substring(0, classFileName.length() - ".class".length());
		String className = classInPackage.getPackageName() + "." + simpleName;
		try {
			return Class.forName(className, false, classInPackage.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			throw new AssertionError("The package directory lists a class the class loader cannot find: " + className,
					ex);
		}
	}

}
