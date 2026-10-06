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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The folder a cache uses: the one the operator configured as it is, and the default
 * under the JVM's temporary directory only while the account GATool runs as owns it and
 * other accounts cannot write to it.
 */
class CacheDirectoriesTests {

	private static final String PROPERTY = "gatool.api.schema.cache-directory";

	private static final String WITHOUT_THE_CACHE = "the schema is fetched at every startup";

	private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

	@BeforeEach
	void watchTheLog() {
		this.lines.start();
		((Logger) LoggerFactory.getLogger(CacheDirectories.class)).addAppender(this.lines);
	}

	@AfterEach
	void stopWatchingTheLog() {
		((Logger) LoggerFactory.getLogger(CacheDirectories.class)).detachAppender(this.lines);
	}

	@Test
	void resolve_blank_shouldSwitchTheCacheOff(@TempDir Path temporaryDirectory) {
		Path defaultFolder = temporaryDirectory.resolve("gatool-ana").resolve("schema");

		assertThat(CacheDirectories.resolve(" ", defaultFolder.toString(), PROPERTY, WITHOUT_THE_CACHE)).isNull();
		assertThat(temporaryDirectory.resolve("gatool-ana")).doesNotExist();
	}

	@Test
	void resolve_defaultFolderMissing_shouldCreateItForItsOwnerAlone(@TempDir Path temporaryDirectory)
			throws IOException {
		assumePosix(temporaryDirectory);
		Path defaultFolder = temporaryDirectory.resolve("gatool-ana").resolve("schema");

		Path resolved = CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isEqualTo(defaultFolder);
		assertThat(permissionsOf(defaultFolder.getParent())).isEqualTo("rwx------");
		assertThat(permissionsOf(defaultFolder)).isEqualTo("rwx------");
		assertThat(filesIn(defaultFolder)).isEmpty();
	}

	@Test
	void resolve_defaultFolderMadeEarlierByThisAccount_shouldUseIt(@TempDir Path temporaryDirectory) {
		Path defaultFolder = temporaryDirectory.resolve("gatool-ana").resolve("schema");
		CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY, WITHOUT_THE_CACHE);

		Path resolved = CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isEqualTo(defaultFolder);
		assertThat(warnings()).isEmpty();
	}

	@Test
	void resolve_defaultFolderOtherAccountsCanWriteTo_shouldLeaveItUnusedAndWarn(@TempDir Path temporaryDirectory)
			throws IOException {
		assumePosix(temporaryDirectory);
		Path accountFolder = Files.createDirectory(temporaryDirectory.resolve("gatool-ana"));
		Files.setPosixFilePermissions(accountFolder, PosixFilePermissions.fromString("rwxrwxrwx"));
		Path defaultFolder = accountFolder.resolve("schema");

		Path resolved = CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isNull();
		assertThat(String.join("\n", warnings())).contains(accountFolder.toString())
			.contains("its permissions, rwxrwxrwx, let other accounts write to it")
			.contains(WITHOUT_THE_CACHE)
			.contains("Set " + PROPERTY + " to a directory of your own");
	}

	@Test
	void resolve_cacheFolderItselfOtherAccountsCanWriteTo_shouldLeaveItUnused(@TempDir Path temporaryDirectory)
			throws IOException {
		assumePosix(temporaryDirectory);
		Path defaultFolder = temporaryDirectory.resolve("gatool-ana").resolve("schema");
		CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY, WITHOUT_THE_CACHE);
		Files.setPosixFilePermissions(defaultFolder, PosixFilePermissions.fromString("rwxrwx---"));

		Path resolved = CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isNull();
		assertThat(String.join("\n", warnings())).contains(defaultFolder.toString())
			.contains("its permissions, rwxrwx---, let other accounts write to it");
	}

	@Test
	void findReasonToLeaveUnused_folderAnotherAccountOwns_shouldNameBothAccounts(@TempDir Path temporaryDirectory)
			throws IOException {
		Path accountFolder = Files.createDirectory(temporaryDirectory.resolve("gatool-ana"));
		tightenWherePosix(accountFolder);
		UserPrincipal owner = Files.getOwner(accountFolder);
		UserPrincipal someoneElse = () -> "mallory";

		assertThat(CacheDirectories.findReasonToLeaveUnused(accountFolder, someoneElse))
			.isEqualTo("the account " + owner.getName() + " owns it, and GATool runs as mallory");
		assertThat(CacheDirectories.findReasonToLeaveUnused(accountFolder, owner)).isNull();
	}

	@Test
	void resolve_defaultFolderThatIsASymbolicLink_shouldLeaveItUnused(@TempDir Path temporaryDirectory,
			@TempDir Path elsewhere) throws IOException {
		Path accountFolder = temporaryDirectory.resolve("gatool-ana");
		try {
			Files.createSymbolicLink(accountFolder, elsewhere);
		}
		catch (IOException | UnsupportedOperationException ex) {
			assumeTrue(false, "this file system cannot hold a symbolic link");
		}
		Path defaultFolder = accountFolder.resolve("schema");

		Path resolved = CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isNull();
		assertThat(String.join("\n", warnings())).contains("it is a symbolic link");
		assertThat(filesIn(elsewhere)).isEmpty();
	}

	@Test
	void resolve_defaultFolderThatIsAFile_shouldLeaveItUnused(@TempDir Path temporaryDirectory) throws IOException {
		Path accountFolder = Files.createFile(temporaryDirectory.resolve("gatool-ana"));
		Path defaultFolder = accountFolder.resolve("schema");

		Path resolved = CacheDirectories.resolve(defaultFolder.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isNull();
		assertThat(String.join("\n", warnings())).contains("it is a file");
	}

	@Test
	void resolve_directoryTheOperatorConfigured_shouldUseItAsItIs(@TempDir Path temporaryDirectory,
			@TempDir Path volume) throws IOException {
		// A volume mounted into a container is often owned by root and writable by a
		// group the process belongs to, and it is the operator's to secure.
		Path configured = Files.createDirectory(volume.resolve("schema-cache"));
		if (isPosix(configured)) {
			Files.setPosixFilePermissions(configured, PosixFilePermissions.fromString("rwxrwxrwx"));
		}
		Path defaultFolder = temporaryDirectory.resolve("gatool-ana").resolve("schema");

		Path resolved = CacheDirectories.resolve(configured.toString(), defaultFolder.toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isEqualTo(configured);
		assertThat(warnings()).isEmpty();
		assertThat(temporaryDirectory.resolve("gatool-ana")).doesNotExist();
	}

	@Test
	void resolve_directoryTheOperatorConfiguredThatIsMissing_shouldLeaveItForTheFirstWrite(@TempDir Path volume) {
		Path configured = volume.resolve("made-on-first-write");

		Path resolved = CacheDirectories.resolve(configured.toString(), volume.resolve("default").toString(), PROPERTY,
				WITHOUT_THE_CACHE);

		assertThat(resolved).isEqualTo(configured);
		assertThat(configured).doesNotExist();
	}

	private List<String> warnings() {
		return this.lines.list.stream()
			.filter((event) -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private static void assumePosix(Path path) {
		assumeTrue(isPosix(path), "this file system does not keep POSIX permissions");
	}

	private static boolean isPosix(Path path) {
		return Files.getFileAttributeView(path, PosixFileAttributeView.class) != null;
	}

	private static void tightenWherePosix(Path folder) throws IOException {
		if (isPosix(folder)) {
			Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
		}
	}

	private static String permissionsOf(Path path) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
	}

	private static List<String> filesIn(Path folder) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.map((file) -> file.getFileName().toString()).toList();
		}
	}

}
