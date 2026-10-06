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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Keeps embedded schema fields on disk, so a schema is embedded once instead of at every
 * startup.
 *
 * <p>
 * Embedding a large schema means one call per field, which is thousands of calls and, on
 * a paid provider, a bill. Every restart repeating that is the cost this removes.
 *
 * <p>
 * The file name is a hash of everything that would change a vector: the corpus text of
 * every field, the prefixes, and a probe vector the model itself returned. The probe is
 * what makes the key honest across providers. A model does not carry a name GATool can
 * read, since {@code EmbeddingModel} exposes its dimensions alone, so an application
 * switching from one model to another of the same size would otherwise read back vectors
 * from the model it left. Asking the model to embed one fixed sentence costs a single
 * call and answers differently for a different model.
 *
 * <p>
 * A miss is a normal outcome: nothing is cached yet, the schema changed, the model
 * changed, or the file cannot be read. Each one embeds again and writes a new file. A
 * directory that cannot be written lets startup carry on, since a cache that fails makes
 * startup slow.
 *
 * <p>
 * A file is written beside its final name and moved into place in one step, so a reader
 * opens either the previous file or the finished one. Two servers sharing a directory, or
 * a crash midway, leave the previous file whole.
 *
 * @author Željko Kozina
 */
final class VectorCache {

	// The file starts with these, so a truncated or foreign file is refused instead of
	// being read as vectors.
	private static final int MAGIC = 0x61_74_71_6C;

	private static final int VERSION = 1;

	// The widest embedding any provider publishes today is a few thousand floats, so a
	// header claiming more than this is a corrupt or foreign file. Without the bound a
	// negative count would throw past the IOException catch, and a huge one would ask the
	// JVM for gigabytes before the first vector is read.
	private static final int MAX_DIMENSIONS = 65_536;

	private static final String SUFFIX = ".vectors";

	private final @Nullable Path directory;

	/**
	 * Creates the cache.
	 * @param directory where files are kept, or {@code null} to embed at every startup
	 */
	VectorCache(@Nullable Path directory) {
		this.directory = directory;
	}

	/**
	 * Builds the key of one corpus, one pair of prefixes and one model.
	 * @param texts the corpus text of every field, in corpus order
	 * @param queryPrefix what a question carries before it is embedded
	 * @param documentPrefix what a field carries before it is embedded
	 * @param probe the vector the model returned for one fixed sentence, which identifies
	 * the model by what it does
	 * @return the key, as hex
	 */
	// Every item goes into the digest behind its length, so the boundaries between items
	// are part of the key. Digested back to back, ["ab", "c"] and ["a", "bc"] would hash
	// the same, and so would a prefix that moves its last character into the first field.
	static String keyOf(List<String> texts, String queryPrefix, String documentPrefix, float[] probe) {
		MessageDigest digest = sha256();
		update(digest, queryPrefix);
		update(digest, documentPrefix);
		ByteBuffer probeBytes = ByteBuffer.allocate(Integer.BYTES + Float.BYTES * probe.length).putInt(probe.length);
		for (float value : probe) {
			probeBytes.putFloat(value);
		}
		digest.update(probeBytes.array());
		for (String text : texts) {
			update(digest, text);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static void update(MessageDigest digest, String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	/**
	 * Reads the vectors of one key.
	 * @param key from {@link #keyOf}
	 * @param expectedCount how many vectors the corpus needs
	 * @return the vectors, or {@code null} where nothing usable is on disk
	 */
	@Nullable List<float[]> read(String key, int expectedCount) {
		Path file = fileOf(key);
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		// Buffered, because DataInputStream reads a float as four single-byte reads and
		// an unbuffered file stream turns each into a system call: 2,000 vectors of 1,536
		// floats take a second to read unbuffered, and a few dozen milliseconds buffered.
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readInt() != expectedCount) {
				return null;
			}
			int dimensions = in.readInt();
			if (dimensions < 1 || dimensions > MAX_DIMENSIONS) {
				return null;
			}
			List<float[]> vectors = new ArrayList<>(expectedCount);
			for (int index = 0; index < expectedCount; index++) {
				float[] vector = new float[dimensions];
				for (int position = 0; position < dimensions; position++) {
					vector[position] = in.readFloat();
				}
				vectors.add(vector);
			}
			return vectors;
		}
		catch (IOException | RuntimeException ex) {
			// A half-written or unreadable file embeds again, which is what a cache
			// should do when it cannot help. RuntimeException is caught beside it because
			// a corrupt header can fail in ways the stream does not report, and the
			// promise above is that any file this cannot read is embedded again.
			return null;
		}
	}

	/**
	 * Writes the vectors of one key.
	 * @param key from {@link #keyOf}
	 * @param vectors one per corpus entry, all of the same length
	 * @return where they were written, or {@code null} where nothing was written
	 */
	@Nullable Path write(String key, List<float[]> vectors) {
		Path cacheDirectory = this.directory;
		if (cacheDirectory == null || vectors.isEmpty()) {
			return null;
		}
		Path file = cacheDirectory.resolve(key + SUFFIX);
		Path partial = null;
		try {
			Files.createDirectories(cacheDirectory);
			// A file of its own in the same directory, so the move below is a rename
			// instead of a copy across file systems. A temporary name, so two servers
			// writing the same key at once each write their own file. The rename is
			// atomic: a reader sees the old file or the new one whole, and a crash midway
			// leaves the old one. The one visible difference from writing in place is
			// that a temporary file is created readable by its owner alone.
			partial = Files.createTempFile(cacheDirectory, key, ".part");
			try (DataOutputStream out = new DataOutputStream(
					new BufferedOutputStream(Files.newOutputStream(partial)))) {
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				out.writeInt(vectors.size());
				out.writeInt(vectors.getFirst().length);
				writeVectors(out, vectors);
			}
			Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return file;
		}
		catch (IOException ex) {
			// A cache that cannot be written is a slower startup, and the tools work
			// either way, so the failure stops here and startup carries on. The partial
			// file goes with it, so a failed write leaves the directory as it found it.
			deleteQuietly(partial);
			return null;
		}
	}

	private static void writeVectors(DataOutputStream out, List<float[]> vectors) throws IOException {
		for (float[] vector : vectors) {
			for (float value : vector) {
				out.writeFloat(value);
			}
		}
	}

	private static void deleteQuietly(@Nullable Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			// The write already failed, and a partial file left behind is a stray file
			// in a cache directory, which is the least of what the caller has to hear.
		}
	}

	private @Nullable Path fileOf(String key) {
		return (this.directory != null) ? this.directory.resolve(key + SUFFIX) : null;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			// Every Java runtime carries SHA-256, so this cannot happen on a working JVM,
			// and a JVM without it is a broken installation, which is a state and not an
			// I/O failure.
			throw new IllegalStateException("SHA-256 is missing from this JVM", ex);
		}
	}

}
