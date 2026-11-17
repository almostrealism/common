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

package org.almostrealism.persist.index.test;

import com.google.protobuf.CodedOutputStream;
import org.almostrealism.persist.index.ProtobufDiskStore;
import org.almostrealism.persist.test.TestRecordProto;
import org.almostrealism.protobuf.Diskstore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.almostrealism.persist.index.test.DiskStoreTestSupport.deleteRecursively;
import static org.almostrealism.persist.index.test.DiskStoreTestSupport.makeRecord;

/**
 * Tests that {@link ProtobufDiskStore} keeps every durably written record when
 * a write is interrupted part way (the state a crash leaves behind), and that a
 * record put from one thread while another thread flushes is neither lost nor
 * the cause of a failure.
 *
 * <p>Interruptions and interleavings are produced deterministically through
 * {@link ProtobufDiskStore#decorateOutput(File, OutputStream)}: the tests wrap the stream a batch
 * or index file is written through, and either fail it after a given number of
 * bytes or run a concurrent {@code put} at a chosen point of the write.</p>
 */
public class ProtobufDiskStoreCrashSafetyTest extends TestSuiteBase {

	/** Temporary directory for store files. */
	private File tempDir;

	/**
	 * Creates a temporary directory before each test.
	 *
	 * @throws IOException if directory creation fails
	 */
	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("diskstore-crash-test").toFile();
	}

	/** Cleans up the temporary directory after each test. */
	@After
	public void tearDown() {
		deleteRecursively(tempDir);
	}

	/**
	 * A write of the index that is interrupted part way must leave the index
	 * committed by the previous flush readable, so every record that flush made
	 * durable is still reachable after a reopen, and new records written after
	 * the reopen are kept alongside them.
	 */
	@Test(timeout = 30000)
	public void interruptedIndexWriteKeepsCommittedRecords() {
		InterceptingStore store = new InterceptingStore(tempDir);
		store.put("a", makeRecord("a", "alpha", 1));
		store.put("b", makeRecord("b", "bravo", 2));
		store.flush();

		store.put("c", makeRecord("c", "charlie", 3));
		store.interceptor = (file, out) -> file.getName().startsWith("index") ? new CrashingStream(out, 5) : out;
		assertWriteFails(store::flush);

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened = open()) {
			Assert.assertEquals("records committed before the interrupted write survive", 2, reopened.size());
			Assert.assertEquals("alpha", reopened.get("a").getContent());
			Assert.assertEquals("bravo", reopened.get("b").getContent());
			Assert.assertEquals(List.of("alpha", "bravo"), scanContents(reopened));

			reopened.put("d", makeRecord("d", "delta", 4));
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened = open()) {
			Assert.assertEquals(3, reopened.size());
			Assert.assertEquals("alpha", reopened.get("a").getContent());
			Assert.assertEquals("delta", reopened.get("d").getContent());
			Assert.assertEquals(List.of("alpha", "bravo", "delta"), scanContents(reopened));
		}
	}

	/**
	 * When the index is lost, the store starts empty, but the batch files already
	 * on disk must not be overwritten by the batches it writes next: their content
	 * is the only remaining copy of the records they hold.
	 *
	 * @throws IOException if the batch file cannot be read
	 */
	@Test(timeout = 30000)
	public void lostIndexDoesNotOverwriteExistingBatches() throws IOException {
		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = open()) {
			store.put("a", makeRecord("a", "alpha", 1));
		}

		File firstBatch = new File(tempDir, "batch_0000.bin");
		byte[] original = Files.readAllBytes(firstBatch.toPath());
		Assert.assertTrue(new File(tempDir, "index.bin").delete());

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = open()) {
			Assert.assertEquals(0, store.size());
			store.put("b", makeRecord("b", "bravo", 2));
		}

		Assert.assertArrayEquals("an existing batch file is never rewritten",
				original, Files.readAllBytes(firstBatch.toPath()));

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = open()) {
			Assert.assertEquals("bravo", store.get("b").getContent());
		}
	}

	/**
	 * A batch file left partially written by an interrupted flush is referenced
	 * by no committed index entry. A reopened store must ignore it: scans visit
	 * exactly the committed records instead of failing on the truncated message,
	 * and later batches are written and scanned normally.
	 */
	@Test(timeout = 30000)
	public void partialBatchFileIsIgnoredAfterReopen() {
		InterceptingStore store = new InterceptingStore(tempDir);
		store.put("a", makeRecord("a", "alpha", 1));
		store.put("b", makeRecord("b", "bravo", 2));
		store.flush();

		store.put("c", makeRecord("c", "charlie", 3));
		store.interceptor = (file, out) -> file.getName().startsWith("batch") ? new CrashingStream(out, 3) : out;
		assertWriteFails(store::flush);

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened = open()) {
			Assert.assertEquals(2, reopened.size());
			Assert.assertEquals(List.of("alpha", "bravo"), scanContents(reopened));

			List<String> pairs = new ArrayList<>();
			reopened.pairwiseScan((x, y) -> pairs.add(x.getId() + y.getId()));
			Assert.assertEquals("one pair of committed records", 1, pairs.size());

			reopened.put("d", makeRecord("d", "delta", 4));
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened = open()) {
			Assert.assertEquals(List.of("alpha", "bravo", "delta"), scanContents(reopened));
		}
	}

	/**
	 * A batch write that fails part way must leave the store as it was before the
	 * flush: the records stay pending and readable, and a later flush writes them
	 * completely, rather than leaving pointers into the partial file.
	 */
	@Test(timeout = 30000)
	public void failedBatchWriteLeavesRecordsPending() {
		InterceptingStore store = new InterceptingStore(tempDir);
		store.put("a", makeRecord("a", "alpha", 1));
		store.interceptor = (file, out) -> file.getName().startsWith("batch") ? new CrashingStream(out, 3) : out;
		assertWriteFails(store::flush);

		Assert.assertEquals("record is still readable after the failed flush",
				"alpha", store.get("a").getContent());

		store.interceptor = null;
		store.close();

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened = open()) {
			Assert.assertEquals("alpha", reopened.get("a").getContent());
			Assert.assertEquals(List.of("alpha"), scanContents(reopened));
		}
	}

	/**
	 * A put issued from another thread while a flush is writing records must not
	 * make the flush fail, and the record it adds must remain readable and be
	 * persisted.
	 *
	 * @throws InterruptedException if interrupted waiting for the putting thread
	 */
	@Test(timeout = 30000)
	public void putDuringBatchWriteIsKept() throws InterruptedException {
		assertConcurrentPutKept(false);
	}

	/**
	 * A put issued from another thread after a flush has written its records but
	 * before it has finished must not be dropped with the flushed records.
	 *
	 * @throws InterruptedException if interrupted waiting for the putting thread
	 */
	@Test(timeout = 30000)
	public void putAtEndOfBatchWriteIsKept() throws InterruptedException {
		assertConcurrentPutKept(true);
	}

	/**
	 * A store directory laid out exactly as the existing on-disk format specifies
	 * (an index message written directly to {@code index.bin} and length-delimited
	 * records in {@code batch_NNNN.bin}) opens with every record readable and
	 * accepts new records.
	 *
	 * @throws IOException if the store files cannot be written
	 */
	@Test(timeout = 30000)
	public void existingStoreLayoutOpens() throws IOException {
		TestRecordProto.TestRecord a = makeRecord("a", "alpha", 1);
		TestRecordProto.TestRecord b = makeRecord("b", "bravo", 2);
		long offsetB = CodedOutputStream.computeUInt32SizeNoTag(a.getSerializedSize()) + a.getSerializedSize();

		try (OutputStream out = new FileOutputStream(new File(tempDir, "batch_0000.bin"))) {
			a.writeDelimitedTo(out);
			b.writeDelimitedTo(out);
		}

		Diskstore.DiskStoreIndex index = Diskstore.DiskStoreIndex.newBuilder()
				.setNextBatchId(2)
				.putLocations("a", location(0, 0))
				.putLocations("b", location(0, offsetB))
				.build();
		try (OutputStream out = new FileOutputStream(new File(tempDir, "index.bin"))) {
			index.writeTo(out);
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = open()) {
			Assert.assertEquals(2, store.size());
			Assert.assertEquals("alpha", store.get("a").getContent());
			Assert.assertEquals("bravo", store.get("b").getContent());
			Assert.assertEquals(List.of("alpha", "bravo"), scanContents(store));
			store.put("c", makeRecord("c", "charlie", 3));
		}

		try (ProtobufDiskStore<TestRecordProto.TestRecord> store = open()) {
			Assert.assertEquals(List.of("alpha", "bravo", "charlie"), scanContents(store));
		}
	}

	/**
	 * Runs a put of record {@code c} from a second thread while the flush of
	 * records {@code a} and {@code b} is writing its batch file, either as the
	 * first byte is written or as the file is closed, and verifies that neither
	 * thread fails and that all three records are readable before and after a
	 * reopen.
	 *
	 * @param atClose {@code true} to interleave the put as the batch file is
	 *                closed, {@code false} to interleave it at the first byte
	 * @throws InterruptedException if interrupted waiting for the putting thread
	 */
	private void assertConcurrentPutKept(boolean atClose) throws InterruptedException {
		InterceptingStore store = new InterceptingStore(tempDir);
		store.put("a", makeRecord("a", "alpha", 1));
		store.put("b", makeRecord("b", "bravo", 2));

		AtomicReference<Throwable> putFailure = new AtomicReference<>();
		Thread putter = new Thread(() -> {
			try {
				store.put("c", makeRecord("c", "charlie", 3));
			} catch (Throwable t) {
				putFailure.set(t);
			}
		});

		Runnable interleave = () -> {
			putter.start();
			awaitFinishedOrBlockedOn(putter, store);
		};
		store.interceptor = (file, out) -> file.getName().startsWith("batch")
				? new InterleavingStream(out, interleave, atClose) : out;

		store.flush();
		store.interceptor = null;
		putter.join(10000);

		Assert.assertFalse("putting thread finished", putter.isAlive());
		Assert.assertNull("concurrent put failed: " + putFailure.get(), putFailure.get());
		Assert.assertEquals(3, store.size());
		Assert.assertEquals("charlie", store.get("c").getContent());
		store.close();

		try (ProtobufDiskStore<TestRecordProto.TestRecord> reopened = open()) {
			Assert.assertEquals(List.of("alpha", "bravo", "charlie"), scanContents(reopened));
			Assert.assertEquals("charlie", reopened.get("c").getContent());
		}
	}

	/**
	 * Waits until the thread has either finished or is blocked entering the
	 * given monitor. Either state is reached without relying on timing: a thread
	 * that does not need the monitor runs to completion, and one that does
	 * blocks on it while the caller holds it.
	 *
	 * @param thread  the thread to observe
	 * @param monitor the monitor the thread may block on
	 */
	private static void awaitFinishedOrBlockedOn(Thread thread, Object monitor) {
		while (thread.isAlive()) {
			ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
			if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
					&& info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) {
				return;
			}

			Thread.onSpinWait();
		}
	}

	/**
	 * Asserts that the given write operation fails with an
	 * {@link UncheckedIOException}, as an interrupted write does.
	 *
	 * @param write the operation expected to fail
	 */
	private static void assertWriteFails(Runnable write) {
		try {
			write.run();
			Assert.fail("the interrupted write should fail");
		} catch (UncheckedIOException expected) {
			// The write was interrupted as intended; the process is treated as crashed.
		}
	}

	/**
	 * Returns the contents of every record visited by a scan, sorted.
	 *
	 * @param store the store to scan
	 * @return the sorted record contents
	 */
	private static List<String> scanContents(ProtobufDiskStore<TestRecordProto.TestRecord> store) {
		List<String> contents = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		store.scan(record -> {
			Assert.assertTrue("record visited once: " + record.getId(), ids.add(record.getId()));
			contents.add(record.getContent());
		});
		contents.sort(String::compareTo);
		return contents;
	}

	/**
	 * Builds an index location entry.
	 *
	 * @param batchId    the batch file identifier
	 * @param byteOffset the byte offset within the batch file
	 * @return the location
	 */
	private static Diskstore.DiskStoreRecordLocation location(int batchId, long byteOffset) {
		return Diskstore.DiskStoreRecordLocation.newBuilder()
				.setBatchId(batchId).setByteOffset(byteOffset).build();
	}

	/**
	 * Opens a plain store on the test directory.
	 *
	 * @return the store
	 */
	private ProtobufDiskStore<TestRecordProto.TestRecord> open() {
		return new ProtobufDiskStore<>(tempDir, TestRecordProto.TestRecord.parser());
	}

	/** Wraps the stream a store file is written through. */
	@FunctionalInterface
	private interface Interceptor {
		/**
		 * Wraps the stream for the given file.
		 *
		 * @param file the file being written
		 * @param out  the stream writing to it
		 * @return the stream the store should write through
		 */
		OutputStream wrap(File file, OutputStream out);
	}

	/** A store whose file writes pass through a test-supplied {@link Interceptor}. */
	private static class InterceptingStore extends ProtobufDiskStore<TestRecordProto.TestRecord> {
		/** The interceptor applied to the next file writes, or {@code null} for none. */
		private volatile Interceptor interceptor;

		/**
		 * Opens a store on the given directory.
		 *
		 * @param rootDir the store directory
		 */
		InterceptingStore(File rootDir) {
			super(rootDir, TestRecordProto.TestRecord.parser());
		}

		@Override
		protected OutputStream decorateOutput(File file, OutputStream out) {
			Interceptor current = interceptor;
			return current == null ? out : current.wrap(file, out);
		}
	}

	/** A stream that writes a fixed number of bytes and then fails, as a crash would. */
	private static class CrashingStream extends FilterOutputStream {
		/** Bytes that may still be written before the failure. */
		private int remaining;

		/**
		 * Creates a stream that fails after {@code limit} bytes.
		 *
		 * @param out   the underlying stream
		 * @param limit the number of bytes written before failing
		 */
		CrashingStream(OutputStream out, int limit) {
			super(out);
			this.remaining = limit;
		}

		@Override
		public void write(int b) throws IOException {
			if (remaining-- <= 0) {
				out.flush();
				throw new IOException("Simulated crash during write");
			}

			out.write(b);
		}
	}

	/** A stream that runs an action once, at its first write or at its close. */
	private static class InterleavingStream extends FilterOutputStream {
		/** The action to run. */
		private final Runnable action;

		/** Whether the action runs at close rather than at the first write. */
		private final boolean atClose;

		/** Whether the action has already run. */
		private boolean done;

		/**
		 * Creates the stream.
		 *
		 * @param out     the underlying stream
		 * @param action  the action to run once
		 * @param atClose {@code true} to run it at close, {@code false} at the first write
		 */
		InterleavingStream(OutputStream out, Runnable action, boolean atClose) {
			super(out);
			this.action = action;
			this.atClose = atClose;
		}

		@Override
		public void write(int b) throws IOException {
			if (!atClose) runOnce();
			out.write(b);
		}

		@Override
		public void close() throws IOException {
			if (atClose) runOnce();
			super.close();
		}

		/** Runs the action if it has not run yet. */
		private void runOnce() {
			if (!done) {
				done = true;
				action.run();
			}
		}
	}
}
