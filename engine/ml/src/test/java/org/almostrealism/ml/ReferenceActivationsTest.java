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

package org.almostrealism.ml;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests the reading half of the reference-dump contract against a dump of the same shape the
 * extraction scripts write: protobuf collection data, named by key, shapes included.
 *
 * <p>The parity tests that consume real dumps are gated on assets no runner carries, so they skip
 * and prove nothing about this path. These do not need a model — a dump is a protobuf shard, and
 * one written here is the same artifact one written by the scripts is.</p>
 */
public class ReferenceActivationsTest extends TestSuiteBase {

	/**
	 * Writes a reference dump of the given tensors, as the scripts do.
	 *
	 * @param tensors the references to write
	 * @return the directory holding the dump
	 * @throws IOException if the dump cannot be written
	 */
	private File dump(Map<String, PackedCollection> tensors) throws IOException {
		Path dir = Files.createTempDirectory("references");
		dir.toFile().deleteOnExit();

		StateDictionary written = new StateDictionary(tensors);
		written.save(dir.resolve("references"));
		return dir.toFile();
	}

	/** A dump holding one three-by-four reference and one flat reference. */
	private File standardDump() throws IOException {
		Map<String, PackedCollection> tensors = new HashMap<>();
		tensors.put("enc_after_mapping", new PackedCollection(shape(3, 4)).fill(1.5));
		tensors.put("enc_resamp_output", new PackedCollection(shape(6)).fill(-2.25));
		return dump(tensors);
	}

	/**
	 * A reference reads back with the values and the shape the dump recorded, without the caller
	 * supplying either.
	 *
	 * @throws IOException if the dump cannot be read
	 */
	@Test(timeout = 120000)
	public void referenceKeepsItsValuesAndShape() throws IOException {
		ReferenceActivations references = new ReferenceActivations(standardDump());
		PackedCollection mapping = references.collection("enc_after_mapping");

		assertEquals(2, mapping.getShape().getDimensions());
		assertEquals(3, mapping.getShape().length(0));
		assertEquals(4, mapping.getShape().length(1));
		assertEquals(1.5, mapping.toDouble(7));
	}

	/**
	 * The shape-checked read accepts the shape the dump recorded and rejects any other, so a
	 * capture and a test that have drifted apart fail rather than reinterpreting the values.
	 *
	 * @throws IOException if the dump cannot be read
	 */
	@Test(timeout = 120000)
	public void aShapeDisagreementFails() throws IOException {
		ReferenceActivations references = new ReferenceActivations(standardDump());
		assertEquals(12, references.collection("enc_after_mapping", shape(3, 4))
				.getShape().getTotalSize());

		try {
			references.collection("enc_after_mapping", shape(3, 5));
			throw new AssertionError("a reference of another size must be rejected");
		} catch (IllegalStateException e) {
			// expected
		}

		try {
			references.collection("enc_after_mapping", shape(4, 3));
			throw new AssertionError("a transposed reference of the same size must be rejected");
		} catch (IllegalStateException e) {
			// expected
		}
	}

	/**
	 * A rank change with the same element count is still shaped, so a flat reference may be read
	 * into the caller's layout even though a same-rank axis disagreement would fail.
	 *
	 * @throws IOException if the dump cannot be read
	 */
	@Test(timeout = 120000)
	public void aFlatReferenceIsShaped() throws IOException {
		ReferenceActivations references = new ReferenceActivations(standardDump());
		PackedCollection shaped = references.collection("enc_resamp_output", shape(2, 3));

		assertEquals(2, shaped.getShape().getDimensions());
		assertEquals(2, shaped.getShape().length(0));
		assertEquals(3, shaped.getShape().length(1));
		assertEquals(-2.25, shaped.toDouble(5));
	}

	/**
	 * A reference absent from the dump is named in the failure, rather than surfacing later as a
	 * null.
	 *
	 * @throws IOException if the dump cannot be read
	 */
	@Test(timeout = 120000)
	public void anAbsentReferenceIsNamed() throws IOException {
		ReferenceActivations references = new ReferenceActivations(standardDump());

		try {
			references.collection("never_captured");
			throw new AssertionError("an absent reference must be rejected");
		} catch (IllegalStateException e) {
			assertTrue(e.getMessage().contains("never_captured"));
		}
	}

	/**
	 * A reference reads back flat, in the order it was written.
	 *
	 * @throws IOException if the dump cannot be read
	 */
	@Test(timeout = 120000)
	public void aReferenceReadsBackFlat() throws IOException {
		float[] values = new ReferenceActivations(standardDump()).load("enc_resamp_output");

		assertEquals(6, values.length);
		assertEquals(-2.25, values[3], 1e-6);
	}

	/**
	 * A directory is located by a reference it holds, whether the marker is named as a key or with
	 * the legacy suffix, and a directory without it is passed over.
	 *
	 * @throws IOException if the dump cannot be written
	 */
	@Test(timeout = 120000)
	public void aDirectoryIsLocatedByAReferenceItHolds() throws IOException {
		File dir = standardDump();
		Path empty = Files.createTempDirectory("no-references");
		empty.toFile().deleteOnExit();

		String[] candidates = {null, empty.toString(), dir.getPath()};

		assertEquals(dir, ReferenceActivations.firstExisting(candidates, "enc_after_mapping"));
		assertEquals(dir, ReferenceActivations.firstExisting(candidates, "enc_after_mapping.bin"));
		assertTrue(ReferenceActivations.firstExisting(candidates, "not_in_any_dump") == null);
	}
}
