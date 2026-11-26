/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio.persistence.test;

import org.almostrealism.audio.api.Audio;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.studio.persistence.LibraryDestination;
import org.almostrealism.studio.persistence.ProtobufWaveDetailsStore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Tests for {@link LibraryDestination}, the file-based batch store that writes
 * and reads sequential protobuf files at a path prefix ({@code PREFIX_N.bin}).
 *
 * <p>Every test drives the public contract through a {@link Path} prefix inside
 * a temporary directory — a prefix that contains a path separator is used
 * verbatim, so the batch files land under the test's own directory rather than
 * the shared local destination — and asserts on the files produced and the data
 * round-tripped, never merely that a method ran.</p>
 *
 * @see LibraryDestination
 */
public class LibraryDestinationTest extends TestSuiteBase {

	/** Root temporary directory for the test, removed during teardown. */
	private Path tempDir;

	/** Path prefix (with a separator, so it is used verbatim) for batch files. */
	private String prefix;

	/**
	 * Creates the temporary directory and the batch-file prefix used by each test.
	 *
	 * @throws IOException if directory creation fails
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("library-destination-test");
		prefix = tempDir.resolve("lib").toString();
	}

	/**
	 * Recursively deletes the test's temporary directory.
	 *
	 * @throws IOException if directory traversal fails
	 */
	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			try (Stream<Path> walk = Files.walk(tempDir)) {
				walk.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.forEach(File::delete);
			}
		}
	}

	/** Builds a library-data batch carrying a single named recording. */
	private static Audio.AudioLibraryData batchWithRecording(String key) {
		return Audio.AudioLibraryData.newBuilder()
				.addRecordings(Audio.WaveRecording.newBuilder().setKey(key))
				.build();
	}

	/**
	 * A prefix containing a path separator is used verbatim, so the first
	 * appended batch is written as {@code PREFIX_0.bin} under the test directory.
	 */
	@Test(timeout = 60000)
	public void verbatimPrefixWritesBatchZeroUnderTestDirectory() {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		dest.save(batchWithRecording("r0"));

		File expected = new File(prefix + "_0.bin");
		Assert.assertTrue("First appended batch must be PREFIX_0.bin under the test dir",
				expected.exists());
		Assert.assertTrue("The batch file must be inside the test's temporary directory",
				expected.toPath().startsWith(tempDir));
	}

	/**
	 * A saved batch round-trips through {@link LibraryDestination#load()}: the
	 * recording it carried is recovered byte-for-byte.
	 */
	@Test(timeout = 60000)
	public void savedBatchRoundTripsThroughLoad() {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		dest.save(batchWithRecording("alpha"));

		List<Audio.AudioLibraryData> loaded = dest.load();
		Assert.assertEquals("Exactly one batch should have been written", 1, loaded.size());
		Assert.assertEquals("The single recording must survive the round trip",
				1, loaded.get(0).getRecordingsCount());
		Assert.assertEquals("alpha", loaded.get(0).getRecordings(0).getKey());
	}

	/**
	 * Successive saves advance the batch index, producing {@code PREFIX_0.bin}
	 * and {@code PREFIX_1.bin}, both recovered in order by {@link
	 * LibraryDestination#load()}.
	 */
	@Test(timeout = 60000)
	public void successiveSavesProduceOrderedBatches() {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		dest.save(batchWithRecording("first"));
		dest.save(batchWithRecording("second"));

		Assert.assertTrue(new File(prefix + "_0.bin").exists());
		Assert.assertTrue(new File(prefix + "_1.bin").exists());

		List<Audio.AudioLibraryData> loaded = dest.load();
		Assert.assertEquals("Two batches should have been written", 2, loaded.size());
		Assert.assertEquals("first", loaded.get(0).getRecordings(0).getKey());
		Assert.assertEquals("second", loaded.get(1).getRecordings(0).getKey());
	}

	/**
	 * The {@link LibraryDestination#files()} iterator enumerates exactly the
	 * batch files that exist, in index order, and stops once one is missing.
	 */
	@Test(timeout = 60000)
	public void filesIteratorEnumeratesExistingBatchesInOrder() {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		dest.save(batchWithRecording("a"));
		dest.save(batchWithRecording("b"));

		Iterator<String> files = dest.files();
		Assert.assertTrue(files.hasNext());
		Assert.assertEquals(prefix + "_0.bin", files.next());
		Assert.assertTrue(files.hasNext());
		Assert.assertEquals(prefix + "_1.bin", files.next());
		Assert.assertFalse("Iterator must stop at the first missing batch", files.hasNext());
	}

	/**
	 * The {@link LibraryDestination#in()} supplier yields one stream per batch
	 * file in order and then {@code null} once every batch has been consumed.
	 *
	 * @throws IOException if a batch stream cannot be closed
	 */
	@Test(timeout = 60000)
	public void inputSupplierYieldsEachBatchThenNull() throws IOException {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		dest.save(batchWithRecording("only"));

		Supplier<InputStream> in = dest.in();
		InputStream first = in.get();
		Assert.assertNotNull("The first batch stream must be supplied", first);
		first.close();
		Assert.assertNull("The supplier must report exhaustion with null", in.get());
	}

	/**
	 * In non-append mode, a batch is staged to a temporary file and only moved
	 * to its final {@code PREFIX_0.bin} destination when the writer is closed
	 * (the flush), where it round-trips through {@link LibraryDestination#load()}.
	 *
	 * @throws IOException if writing or closing the staged batch fails
	 */
	@Test(timeout = 60000)
	public void nonAppendWriterStagesThenFlushesOnClose() throws IOException {
		LibraryDestination dest = new LibraryDestination(prefix, false);
		byte[] bytes = batchWithRecording("staged").toByteArray();

		try (LibraryDestination.Writer writer = dest.out()) {
			OutputStream out = writer.get();
			Assert.assertFalse("The final batch must not exist before flush",
					new File(prefix + "_0.bin").exists());
			out.write(bytes);
			out.close();
		}

		Assert.assertTrue("Closing the writer must flush the staged batch to its final path",
				new File(prefix + "_0.bin").exists());
		List<Audio.AudioLibraryData> loaded = dest.load();
		Assert.assertEquals(1, loaded.size());
		Assert.assertEquals("staged", loaded.get(0).getRecordings(0).getKey());
	}

	/**
	 * {@link LibraryDestination#discardTemporary()} drops the pending staged
	 * files so a subsequent {@link LibraryDestination#flush()} moves nothing to
	 * a final destination.
	 *
	 * @throws IOException if writing the staged batch or flushing fails
	 */
	@Test(timeout = 60000)
	public void discardTemporaryCancelsPendingStagedBatches() throws IOException {
		LibraryDestination dest = new LibraryDestination(prefix, false);

		OutputStream out = dest.out().get();
		out.write(batchWithRecording("discarded").toByteArray());
		out.close();

		dest.discardTemporary();
		dest.flush();

		Assert.assertFalse("A discarded batch must never reach a final destination",
				new File(prefix + "_0.bin").exists());
		Assert.assertFalse("No batch file should remain after a discard", dest.files().hasNext());
	}

	/**
	 * {@link LibraryDestination#delete()} removes every batch file at the prefix,
	 * leaving the iterator empty.
	 */
	@Test(timeout = 60000)
	public void deleteRemovesEveryBatchFile() {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		dest.save(batchWithRecording("x"));
		dest.save(batchWithRecording("y"));
		Assert.assertTrue(new File(prefix + "_0.bin").exists());

		dest.delete();

		Assert.assertFalse("No batch file may remain after delete", dest.files().hasNext());
		Assert.assertFalse(new File(prefix + "_0.bin").exists());
		Assert.assertFalse(new File(prefix + "_1.bin").exists());
	}

	/**
	 * {@link LibraryDestination#getTemporaryFile(String, String)} names a plain
	 * key directly but Base64-encodes a key containing a path separator so it is
	 * safe to use as a single filename.
	 */
	@Test(timeout = 60000)
	public void temporaryFileNameEncodesKeysContainingSeparators() {
		LibraryDestination dest = new LibraryDestination(prefix, true);

		Assert.assertEquals("A plain key is used verbatim as the file name",
				"plain.bin", dest.getTemporaryFile("plain", "bin").getName());

		String encoded = Base64.getEncoder().encodeToString("group/sub".getBytes());
		Assert.assertEquals("A key with a separator must be Base64-encoded for the file name",
				encoded + ".wav", dest.getTemporaryFile("group/sub", "wav").getName());
	}

	/**
	 * {@link LibraryDestination#getTemporaryDestination(String, String)} opens a
	 * writable stream to the temporary file for a key, and the bytes written are
	 * recoverable from {@link LibraryDestination#getTemporaryFile(String, String)}.
	 *
	 * @throws IOException if writing or reading the temporary file fails
	 */
	@Test(timeout = 60000)
	public void temporaryDestinationWritesRecoverableBytes() throws IOException {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		String key = "lib-destination-test-" + tempDir.getFileName();
		byte[] payload = {1, 2, 3, 4, 5};

		File target = dest.getTemporaryFile(key, "bin");
		target.delete();
		try (OutputStream out = dest.getTemporaryDestination(key, "bin")) {
			out.write(payload);
		}

		Assert.assertTrue("The temporary destination must create the backing file", target.exists());
		Assert.assertArrayEquals("The written bytes must be recoverable",
				payload, Files.readAllBytes(target.toPath()));
		target.delete();
	}

	/**
	 * {@link LibraryDestination#getDefaultLibraryRoot()} resolves a library root
	 * beneath the user's home directory and ensures it exists.
	 */
	@Test(timeout = 60000)
	public void defaultLibraryRootResolvesUnderHome() {
		Path root = LibraryDestination.getDefaultLibraryRoot();
		Assert.assertNotNull("A default library root must always be returned", root);
		Assert.assertTrue("The root must resolve beneath the user's home directory",
				root.startsWith(Path.of(SystemUtils.getHome())));
		Assert.assertTrue("The root directory must have been created", Files.isDirectory(root));
	}

	/**
	 * {@link LibraryDestination#migrateAndOpenStore(File)} opens an empty store
	 * and runs no migration when there is no legacy batch data at the prefix.
	 */
	@Test(timeout = 60000)
	public void migrateAndOpenStoreReturnsEmptyStoreWhenNoLegacyData() {
		LibraryDestination dest = new LibraryDestination(prefix, true);
		File storeDir = tempDir.resolve("store").toFile();

		ProtobufWaveDetailsStore store = dest.migrateAndOpenStore(storeDir);
		try {
			Assert.assertNotNull("A store must always be returned", store);
			Assert.assertEquals("With no legacy data the store starts empty", 0, store.size());
			Assert.assertFalse("No legacy batch file should have been created",
					new File(prefix + "_0.bin").exists());
		} finally {
			store.close();
		}
	}
}
