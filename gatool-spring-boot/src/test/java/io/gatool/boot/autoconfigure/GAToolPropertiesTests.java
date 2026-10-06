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

package io.gatool.boot.autoconfigure;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GAToolPropertiesTests {

	@Test
	void cacheDirectories_byDefault_shouldLiveUnderTheJvmsTemporaryDirectoryInAFolderOfTheAccount() {
		// Boot's own on-disk defaults live under java.io.tmpdir. A folder under the
		// working directory would be written into the repository of every application
		// that fetches a schema and refused in a read-only container. The folder carries
		// the account's name, because on a host several accounts share, one folder for
		// all of them belongs to whichever account made it first.
		GAToolProperties properties = new GAToolProperties();
		Path accountFolder = Path.of(System.getProperty("java.io.tmpdir"))
			.resolve("gatool-" + System.getProperty("user.name").replaceAll("[^A-Za-z0-9._-]", "_"));

		assertThat(properties.getApi().getSchema().getCacheDirectory())
			.isEqualTo(accountFolder.resolve("schema").toString());
		assertThat(properties.getDev().getExperimental().getDynamicOperations().getVectorCacheDirectory())
			.isEqualTo(accountFolder.resolve("vectors").toString());
	}

	@Test
	void underTemporaryDirectory_accountNameWithCharactersAPathRefuses_shouldReplaceEachWithAnUnderscore() {
		// A JVM whose user id lacks an entry in the password file reports the name "?",
		// which is what a container started with an arbitrary user id does, and a name
		// on Windows or macOS can hold a space.
		assertThat(GAToolProperties.underTemporaryDirectory("/tmp", "?", "schema"))
			.isEqualTo(Path.of("/tmp", "gatool-_", "schema").toString());
		assertThat(GAToolProperties.underTemporaryDirectory("/tmp", "Ana Marija/..", "schema"))
			.isEqualTo(Path.of("/tmp", "gatool-Ana_Marija_..", "schema").toString());
	}

	@Test
	void underTemporaryDirectory_accountNameUnset_shouldStillNameAFolder() {
		assertThat(GAToolProperties.underTemporaryDirectory("/tmp", null, "vectors"))
			.isEqualTo(Path.of("/tmp", "gatool-unknown", "vectors").toString());
		assertThat(GAToolProperties.underTemporaryDirectory("/tmp", " ", "vectors"))
			.isEqualTo(Path.of("/tmp", "gatool-unknown", "vectors").toString());
	}

	@Test
	void cacheDirectories_setBlank_shouldStayBlankSoTheCacheIsOff() {
		GAToolProperties properties = new GAToolProperties();

		properties.getApi().getSchema().setCacheDirectory("");
		properties.getDev().getExperimental().getDynamicOperations().setVectorCacheDirectory("");

		assertThat(properties.getApi().getSchema().getCacheDirectory()).isEmpty();
		assertThat(properties.getDev().getExperimental().getDynamicOperations().getVectorCacheDirectory()).isEmpty();
	}

}
