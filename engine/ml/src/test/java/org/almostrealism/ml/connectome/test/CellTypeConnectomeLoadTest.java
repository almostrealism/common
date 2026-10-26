/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.ml.connectome.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.connectome.CellTypeConnectome;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks {@link CellTypeConnectome#load(File)} on a small directory written in the layout of
 * {@code tools/connectome/male_cns_cell_types.py}: an F32 {@code weights} matrix and I32
 * {@code type_sizes} and {@code type_out_synapses} in one safetensors file, the type names and
 * the annotation table. Unlike {@link CellTypeConnectomeTest}, it needs no external data, so it
 * runs everywhere.
 */
public class CellTypeConnectomeLoadTest extends TestSuiteBase {
	/** The type names of the synthetic connectome. */
	private static final List<String> NAMES = List.of("LB3a", "MN9", "Kenyon");

	/** The annotation table matching {@link #NAMES}; the last type has no annotations. */
	private static final String ANNOTATIONS = "type\tsuperclass\tclass\n"
			+ "LB3a\tcb_sensory\tgustatory\n"
			+ "MN9\tcb_motor\tmotor\n"
			+ "Kenyon\t\t\n";

	/** The weights, row = postsynaptic type, column = presynaptic type. */
	private static final float[] WEIGHTS = { 0, -3, 7, 12, 0, -1, 5, 2, 0 };

	/** The neurons per type. */
	private static final int[] SIZES = { 4, 2, 5 };

	/** The outgoing synapses per type. */
	private static final int[] OUT_SYNAPSES = { 17, 1, 6 };

	/**
	 * Writes a connectome directory with the given tensors, names and annotation table.
	 *
	 * @param tensorCount the number of types the tensors are shaped for
	 * @param names       the content of the type-name file
	 * @param annotations the content of the annotation file
	 * @return the directory
	 */
	private static File writeDirectory(int tensorCount, String names, String annotations) throws IOException {
		int t = tensorCount;
		int weightBytes = t * t * Float.BYTES;
		int sizeBytes = t * Integer.BYTES;
		String header = "{"
				+ "\"weights\":{\"dtype\":\"F32\",\"shape\":[" + t + "," + t + "],\"data_offsets\":[0," + weightBytes + "]},"
				+ "\"type_sizes\":{\"dtype\":\"I32\",\"shape\":[" + t + "],\"data_offsets\":["
				+ weightBytes + "," + (weightBytes + sizeBytes) + "]},"
				+ "\"type_out_synapses\":{\"dtype\":\"I32\",\"shape\":[" + t + "],\"data_offsets\":["
				+ (weightBytes + sizeBytes) + "," + (weightBytes + 2 * sizeBytes) + "]}"
				+ "}";
		byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

		ByteBuffer file = ByteBuffer.allocate(Long.BYTES + headerBytes.length + weightBytes + 2 * sizeBytes)
				.order(ByteOrder.LITTLE_ENDIAN);
		file.putLong(headerBytes.length);
		file.put(headerBytes);
		for (int i = 0; i < t * t; i++) file.putFloat(i < WEIGHTS.length ? WEIGHTS[i] : 1.0f);
		for (int i = 0; i < t; i++) file.putInt(i < SIZES.length ? SIZES[i] : 1);
		for (int i = 0; i < t; i++) file.putInt(i < OUT_SYNAPSES.length ? OUT_SYNAPSES[i] : 1);

		Path directory = Files.createTempDirectory("cell-types");
		Files.write(directory.resolve(CellTypeConnectome.WEIGHTS_FILE), file.array());
		Files.writeString(directory.resolve(CellTypeConnectome.TYPES_FILE), names);
		Files.writeString(directory.resolve(CellTypeConnectome.ANNOTATIONS_FILE), annotations);
		return directory.toFile();
	}

	/** Writes the consistent three-type directory. */
	private static File writeValidDirectory() throws IOException {
		return writeDirectory(NAMES.size(), String.join("\n", NAMES) + "\n\n", ANNOTATIONS);
	}

	/**
	 * Every tensor, name and annotation is loaded in index order, names resolve to their indices,
	 * blank lines are ignored, an unannotated type reads as {@code ""}, and
	 * {@link CellTypeConnectome#inverseTypeSizes()} is {@code 1 / type_size}.
	 */
	@Test(timeout = 60000)
	public void loadsTensorsNamesAndAnnotations() throws IOException {
		try (CellTypeConnectome connectome = CellTypeConnectome.load(writeValidDirectory());
			 PackedCollection inverse = connectome.inverseTypeSizes().evaluate()) {
			assertEquals(3, connectome.getTypeCount());
			Assert.assertEquals(NAMES, connectome.getTypeNames());

			double[] expectedWeights = new double[WEIGHTS.length];
			for (int i = 0; i < WEIGHTS.length; i++) expectedWeights[i] = WEIGHTS[i];
			Assert.assertArrayEquals(expectedWeights, connectome.getWeights().toArray(), 0.0);
			Assert.assertArrayEquals(new double[] { 4, 2, 5 }, connectome.getTypeSizes().toArray(), 0.0);
			Assert.assertArrayEquals(new double[] { 17, 1, 6 }, connectome.getOutSynapses().toArray(), 0.0);
			Assert.assertArrayEquals(new double[] { 0.25, 0.5, 0.2 }, inverse.toArray(), 1e-6);

			assertEquals(0, connectome.indexOf("LB3a"));
			assertEquals(1, connectome.indexOf("MN9"));
			assertEquals(2, connectome.indexOf("Kenyon"));
			Assert.assertEquals("cb_sensory", connectome.getSuperclass(0));
			Assert.assertEquals("gustatory", connectome.getTypeClass(0));
			Assert.assertEquals("cb_motor", connectome.getSuperclass(1));
			Assert.assertEquals("", connectome.getSuperclass(2));
			Assert.assertEquals("", connectome.getTypeClass(2));

			Assert.assertArrayEquals(new int[] { 0, 1 }, connectome.indicesMatching(name -> name.length() <= 4));
			Assert.assertArrayEquals(new int[0], connectome.indicesMatching(name -> name.startsWith("X")));
		}
	}

	/** An unknown type name, including the empty name, is rejected, and the names are read-only. */
	@Test(timeout = 60000)
	public void rejectsUnknownTypeName() throws IOException {
		try (CellTypeConnectome connectome = CellTypeConnectome.load(writeValidDirectory())) {
			assertThrowsIllegalArgument(() -> connectome.indexOf("MN10"));
			assertThrowsIllegalArgument(() -> connectome.indexOf(""));
			try {
				connectome.getTypeNames().add("extra");
				Assert.fail("The type names are modifiable");
			} catch (UnsupportedOperationException expected) {
				// expected
			}
		}
	}

	/** An annotation table that lists the types in a different order from the names is rejected. */
	@Test(timeout = 60000)
	public void rejectsAnnotationsOutOfOrder() throws IOException {
		String swapped = "type\tsuperclass\tclass\n"
				+ "MN9\tcb_motor\tmotor\n"
				+ "LB3a\tcb_sensory\tgustatory\n"
				+ "Kenyon\t\t\n";
		File directory = writeDirectory(3, String.join("\n", NAMES), swapped);
		assertThrowsLoad(directory);
	}

	/** An annotation row with the wrong number of fields is rejected. */
	@Test(timeout = 60000)
	public void rejectsMalformedAnnotationRow() throws IOException {
		String malformed = "type\tsuperclass\tclass\n"
				+ "LB3a\tcb_sensory\n"
				+ "MN9\tcb_motor\tmotor\n"
				+ "Kenyon\t\t\n";
		assertThrowsLoad(writeDirectory(3, String.join("\n", NAMES), malformed));
	}

	/** An annotation table with fewer or more rows than there are types is rejected. */
	@Test(timeout = 60000)
	public void rejectsAnnotationCountMismatch() throws IOException {
		String missing = "type\tsuperclass\tclass\nLB3a\tcb_sensory\tgustatory\nMN9\tcb_motor\tmotor\n";
		assertThrowsLoad(writeDirectory(3, String.join("\n", NAMES), missing));

		String extra = ANNOTATIONS + "Extra\t\t\n";
		assertThrowsLoad(writeDirectory(3, String.join("\n", NAMES), extra));
	}

	/** Tensors shaped for a different number of types than the name list are rejected. */
	@Test(timeout = 60000)
	public void rejectsShapeMismatch() throws IOException {
		assertThrowsLoad(writeDirectory(4, String.join("\n", NAMES), ANNOTATIONS));
	}

	/** A type name listed twice is rejected. */
	@Test(timeout = 60000)
	public void rejectsDuplicateTypeName() throws IOException {
		String annotations = "type\tsuperclass\tclass\nLB3a\t\t\nMN9\t\t\nLB3a\t\t\n";
		assertThrowsLoad(writeDirectory(3, "LB3a\nMN9\nLB3a\n", annotations));
	}

	/**
	 * Asserts that loading {@code directory} throws {@link IllegalArgumentException}.
	 *
	 * @param directory the connectome directory
	 */
	private static void assertThrowsLoad(File directory) {
		assertThrowsIllegalArgument(() -> {
			try {
				CellTypeConnectome.load(directory).destroy();
			} catch (IOException e) {
				throw new AssertionError("Expected an IllegalArgumentException, not " + e, e);
			}
		});
	}

	/**
	 * Asserts that {@code action} throws {@link IllegalArgumentException}.
	 *
	 * @param action the action expected to throw
	 */
	private static void assertThrowsIllegalArgument(Runnable action) {
		try {
			action.run();
			Assert.fail("Expected an IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
