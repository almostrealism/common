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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests of {@link ObjectOrientedReviewLedger}: a settled verdict for a class's content is
 * remembered across ledger instances and survives on disk, while a different content, an unsettled
 * verdict, or a missing file is not treated as reviewed.
 */
public class ObjectOrientedReviewLedgerTest extends TestSuiteBase {

	/** A class's content, once recorded CLEAN, is reviewed; a different blob id for it is not. */
	@Test(timeout = 30000)
	public void settledContentIsReviewed() throws IOException {
		Path dir = Files.createTempDirectory("oop-ledger");
		try {
			String path = "engine/ml/src/main/java/org/example/Window.java";
			ObjectOrientedReviewLedger ledger = new ObjectOrientedReviewLedger(dir.resolve("b.tsv"));
			assertFalse(ledger.isReviewed(path, "blobA"));

			ledger.record(path, "blobA", "CLEAN");
			assertTrue(ledger.isReviewed(path, "blobA"));
			assertFalse("a different content must not count as reviewed", ledger.isReviewed(path, "blobB"));
			assertFalse("a different class must not count as reviewed",
					ledger.isReviewed("other/Other.java", "blobA"));
		} finally {
			delete(dir);
		}
	}

	/** A FIXED verdict is remembered; an unsettled verdict (a review that did not finish) is not. */
	@Test(timeout = 30000)
	public void onlySettledVerdictsAreRemembered() throws IOException {
		Path dir = Files.createTempDirectory("oop-ledger");
		try {
			String path = "engine/ml/src/main/java/org/example/Window.java";
			ObjectOrientedReviewLedger ledger = new ObjectOrientedReviewLedger(dir.resolve("b.tsv"));

			ledger.record(path, "blobA", "FIXED");
			assertTrue(ledger.isReviewed(path, "blobA"));

			ledger.record(path, "blobB", "reviewing");
			assertFalse("a non-CLEAN/FIXED verdict must not be remembered", ledger.isReviewed(path, "blobB"));
		} finally {
			delete(dir);
		}
	}

	/** A verdict recorded by one ledger instance is read back by another over the same file. */
	@Test(timeout = 30000)
	public void verdictSurvivesOnDisk() throws IOException {
		Path dir = Files.createTempDirectory("oop-ledger");
		try {
			Path file = dir.resolve("branch.tsv");
			String path = "engine/ml/src/main/java/org/example/Window.java";
			new ObjectOrientedReviewLedger(file).record(path, "blobA", "CLEAN");

			assertTrue("a fresh ledger over the same file must see the verdict",
					new ObjectOrientedReviewLedger(file).isReviewed(path, "blobA"));
		} finally {
			delete(dir);
		}
	}

	/** A ledger over no file persists nothing, so a job without a branch cannot skip a later job's review. */
	@Test(timeout = 30000)
	public void nullFileLedgerPersistsNothing() {
		ObjectOrientedReviewLedger ledger = new ObjectOrientedReviewLedger(null);
		assertFalse(ledger.isReviewed("a/B.java", "blobA"));
		ledger.record("a/B.java", "blobA", "CLEAN");

		assertFalse("a ledger over no file must not carry a verdict to a fresh instance",
				new ObjectOrientedReviewLedger(null).isReviewed("a/B.java", "blobA"));
	}

	/**
	 * Deletes a temporary tree.
	 *
	 * @param root the tree to delete
	 * @throws IOException if the tree cannot be walked
	 */
	private void delete(Path root) throws IOException {
		try (Stream<Path> paths = Files.walk(root)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
	}
}
