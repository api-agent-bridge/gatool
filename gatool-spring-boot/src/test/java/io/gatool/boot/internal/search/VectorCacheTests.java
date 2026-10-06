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

package io.gatool.boot.internal.search;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file that keeps a schema from being embedded twice.
 *
 * <p>
 * A cache may miss and must not lie, so the cases here are the files a cache directory
 * accumulates over time: a truncated write, a foreign or corrupt header, a second server
 * writing the same key, a directory that cannot be made. Each one has to come back as a
 * miss and leave the tools working.
 */
class VectorCacheTests {

	private static final float[] PROBE = { 0.5f, -1f };

	private static final List<float[]> VECTORS = List.of(new float[] { 1f, 2f, 3f }, new float[] { -4f, 5.5f, 0f });

	@Test
	void write_thenRead_shouldGiveBackTheSameVectors(@TempDir Path directory) {
		VectorCache cache = new VectorCache(directory);

		Path written = cache.write("key", VECTORS);
		List<float[]> read = cache.read("key", 2);

		assertThat(written).isNotNull().hasParent(directory);
		assertThat(read).isNotNull().hasSize(2);
		assertThat(read.get(0)).containsExactly(1f, 2f, 3f);
		assertThat(read.get(1)).containsExactly(-4f, 5.5f, 0f);
	}

	@Test
	void read_aKeyNothingWasWrittenFor_shouldMiss(@TempDir Path directory) {
		assertThat(new VectorCache(directory).read("key", 2)).isNull();
	}

	@Test
	void read_aCountOtherThanTheFileHolds_shouldMiss(@TempDir Path directory) {
		VectorCache cache = new VectorCache(directory);
		cache.write("key", VECTORS);

		// The corpus grew or shrank, so the vectors on disk belong to another schema.
		assertThat(cache.read("key", 3)).isNull();
	}

	@Test
	void read_aTruncatedFile_shouldMiss(@TempDir Path directory) throws IOException {
		VectorCache cache = new VectorCache(directory);
		Path file = cache.write("key", VECTORS);
		byte[] bytes = Files.readAllBytes(file);
		Files.write(file, Arrays.copyOf(bytes, bytes.length - 5));

		assertThat(cache.read("key", 2)).isNull();
	}

	@Test
	void read_aHeaderWithDimensionsOutsideTheBound_shouldMiss(@TempDir Path directory) throws IOException {
		// A negative count would throw NegativeArraySizeException past the IOException
		// catch, and Integer.MAX_VALUE would ask the JVM for gigabytes before a vector is
		// read. The Javadoc promises that a file the cache cannot read is embedded again.
		VectorCache cache = new VectorCache(directory);
		for (int dimensions : new int[] { -1, 0, 65_537, Integer.MAX_VALUE }) {
			writeHeader(directory.resolve("key.vectors"), 1, dimensions);

			assertThat(cache.read("key", 1)).as("dimensions %d", dimensions).isNull();
		}
	}

	@Test
	void write_aSecondTime_shouldReplaceTheFileAndLeaveNothingBeside(@TempDir Path directory) throws IOException {
		VectorCache cache = new VectorCache(directory);
		cache.write("key", VECTORS);

		cache.write("key", List.of(new float[] { 9f }, new float[] { 8f }));

		List<float[]> read = cache.read("key", 2);
		assertThat(read).isNotNull();
		assertThat(read.get(0)).containsExactly(9f);
		assertThat(filesIn(directory)).containsExactly("key.vectors");
	}

	@Test
	void write_twoWritersOfOneKeyAtOnce_shouldLeaveOneWholeFile(@TempDir Path directory) throws Exception {
		// Two servers sharing a cache directory start together. Each writes its own
		// file and moves it into place, so the reader sees one of them whole and the
		// directory holds that one finished file alone.
		VectorCache cache = new VectorCache(directory);
		List<float[]> first = List.of(new float[] { 1f });
		List<float[]> second = List.of(new float[] { 2f });
		Thread one = new Thread(() -> cache.write("key", first));
		Thread other = new Thread(() -> cache.write("key", second));

		one.start();
		other.start();
		one.join();
		other.join();

		List<float[]> read = cache.read("key", 1);
		assertThat(read).isNotNull().hasSize(1);
		assertThat(read.get(0)[0]).isIn(1f, 2f);
		assertThat(filesIn(directory)).containsExactly("key.vectors");
	}

	@Test
	void write_aDirectoryThatCannotBeMade_shouldMissAndStopNothing(@TempDir Path directory) throws IOException {
		// A file sits where the directory would go, so createDirectories fails. The
		// tools work either way, so the failure is a miss and startup carries on.
		Path blocked = Files.writeString(directory.resolve("blocked"), "a file, so the directory cannot be made here");
		VectorCache cache = new VectorCache(blocked);

		assertThat(cache.write("key", VECTORS)).isNull();
		assertThat(cache.read("key", 2)).isNull();
	}

	@Test
	void write_withoutADirectory_shouldMiss() {
		VectorCache cache = new VectorCache(null);

		assertThat(cache.write("key", VECTORS)).isNull();
		assertThat(cache.read("key", 2)).isNull();
	}

	@Test
	void keyOf_theSameInput_shouldGiveTheSameKey() {
		assertThat(VectorCache.keyOf(List.of("a", "b"), "q", "d", PROBE))
			.isEqualTo(VectorCache.keyOf(List.of("a", "b"), "q", "d", PROBE))
			.hasSize(64);
	}

	@Test
	void keyOf_itemsThatConcatenateToTheSameText_shouldGiveDifferentKeys() {
		// Digested back to back, ["ab", "c"] and ["a", "bc"] would hash the same, and so
		// would a prefix that moves its last character into the first field.
		assertThat(VectorCache.keyOf(List.of("ab", "c"), "", "", PROBE))
			.isNotEqualTo(VectorCache.keyOf(List.of("a", "bc"), "", "", PROBE));
		assertThat(VectorCache.keyOf(List.of("b"), "a", "", PROBE))
			.isNotEqualTo(VectorCache.keyOf(List.of("ab"), "", "", PROBE));
		assertThat(VectorCache.keyOf(List.of("b"), "a", "", PROBE))
			.isNotEqualTo(VectorCache.keyOf(List.of("b"), "", "a", PROBE));
	}

	@Test
	void keyOf_aDifferentProbe_shouldGiveADifferentKey() {
		assertThat(VectorCache.keyOf(List.of("a"), "", "", PROBE))
			.isNotEqualTo(VectorCache.keyOf(List.of("a"), "", "", new float[] { 0.5f, -1.5f }));
	}

	private static void writeHeader(Path file, int count, int dimensions) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(0x61_74_71_6C);
			out.writeInt(1);
			out.writeInt(count);
			out.writeInt(dimensions);
		}
		Files.write(file, bytes.toByteArray());
	}

	private static List<String> filesIn(Path directory) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.map((file) -> file.getFileName().toString()).toList();
		}
	}

}
