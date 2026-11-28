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

package org.almostrealism.bioscience.connectome.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.bioscience.connectome.CellTypeConnectome;
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
 * {@code studio/bioscience/scripts/male_cns_cell_types.py}: an F32 {@code weights} matrix and I32
 * {@code type_sizes} and {@code type_out_synapses} in one safetensors file, the type names and
 * the annotation table. Unlike {@link CellTypeConnectomeTest}, it needs no external data, so it
 * runs everywhere.
 *
 * <p>It also covers the text layout a committed fixture uses in place of the safetensors file
 * ({@link CellTypeConnectome#COUNTS_FILE} and {@link CellTypeConnectome#WEIGHTS_TEXT_FILE}): the
 * same directory written that way loads to the same tensors, and malformed rows are rejected.</p>
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

	/** The text layout's counts table matching {@link #SIZES} and {@link #OUT_SYNAPSES}. */
	private static final String TEXT_COUNTS = "type\tsize\tout_synapses\n"
			+ "LB3a\t4\t17\n"
			+ "MN9\t2\t1\n"
			+ "Kenyon\t5\t6\n";

	/** The header line of the text layout's entry list. */
	private static final String TEXT_WEIGHTS_HEADER = "post\tpre\tweight\n";

	/** The text layout's entry list holding the non-zero entries of {@link #WEIGHTS}. */
	private static final String TEXT_WEIGHTS = TEXT_WEIGHTS_HEADER
			+ "0\t1\t-3\n"
			+ "0\t2\t7\n"
			+ "1\t0\t12\n"
			+ "1\t2\t-1\n"
			+ "2\t0\t5\n"
			+ "2\t1\t2\n";

	/**
	 * Writes a connectome directory with the given tensors, names and annotation table.
	 *
	 * @param tensorCount the number of types the tensors are shaped for
	 * @param names       the content of the type-name file
	 * @param annotations the content of the annotation file
	 * @return the directory
	 */
	private static File writeDirectory(int tensorCount, String names, String annotations) throws IOException {
		return writeDirectory(tensorCount, names, annotations, true);
	}

	/**
	 * Writes a connectome directory with the given tensors, names and annotation table,
	 * optionally leaving out the {@code type_out_synapses} tensor.
	 *
	 * @param tensorCount     the number of types the tensors are shaped for
	 * @param names           the content of the type-name file
	 * @param annotations     the content of the annotation file
	 * @param withOutSynapses whether the {@code type_out_synapses} tensor is written
	 * @return the directory
	 */
	private static File writeDirectory(int tensorCount, String names, String annotations,
									   boolean withOutSynapses) throws IOException {
		int t = tensorCount;
		int weightBytes = t * t * Float.BYTES;
		int sizeBytes = t * Integer.BYTES;
		int countTensors = withOutSynapses ? 2 : 1;
		String header = "{"
				+ "\"weights\":{\"dtype\":\"F32\",\"shape\":[" + t + "," + t + "],\"data_offsets\":[0," + weightBytes + "]},"
				+ "\"type_sizes\":{\"dtype\":\"I32\",\"shape\":[" + t + "],\"data_offsets\":["
				+ weightBytes + "," + (weightBytes + sizeBytes) + "]}"
				+ (withOutSynapses ? ",\"type_out_synapses\":{\"dtype\":\"I32\",\"shape\":[" + t + "],\"data_offsets\":["
				+ (weightBytes + sizeBytes) + "," + (weightBytes + 2 * sizeBytes) + "]}" : "")
				+ "}";
		byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);

		ByteBuffer file = ByteBuffer.allocate(Long.BYTES + headerBytes.length + weightBytes + countTensors * sizeBytes)
				.order(ByteOrder.LITTLE_ENDIAN);
		file.putLong(headerBytes.length);
		file.put(headerBytes);
		for (int i = 0; i < t * t; i++) file.putFloat(i < WEIGHTS.length ? WEIGHTS[i] : 1.0f);
		for (int i = 0; i < t; i++) file.putInt(i < SIZES.length ? SIZES[i] : 1);
		if (withOutSynapses) {
			for (int i = 0; i < t; i++) file.putInt(i < OUT_SYNAPSES.length ? OUT_SYNAPSES[i] : 1);
		}

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
	 * Writes the three-type directory in the text layout, with no safetensors file.
	 *
	 * @param counts  the content of the counts table
	 * @param weights the content of the entry list
	 * @return the directory
	 */
	private static File writeTextDirectory(String counts, String weights) throws IOException {
		Path directory = Files.createTempDirectory("cell-types-text");
		Files.writeString(directory.resolve(CellTypeConnectome.TYPES_FILE), String.join("\n", NAMES) + "\n");
		Files.writeString(directory.resolve(CellTypeConnectome.ANNOTATIONS_FILE), ANNOTATIONS);
		addTextTensors(directory.toFile(), counts, weights);
		return directory.toFile();
	}

	/**
	 * Writes the text layout's two tensor files into a connectome directory.
	 *
	 * @param directory the connectome directory
	 * @param counts    the content of the counts table
	 * @param weights   the content of the entry list
	 */
	private static void addTextTensors(File directory, String counts, String weights) throws IOException {
		Files.writeString(directory.toPath().resolve(CellTypeConnectome.COUNTS_FILE), counts);
		Files.writeString(directory.toPath().resolve(CellTypeConnectome.WEIGHTS_TEXT_FILE), weights);
	}

	/**
	 * Asserts that a loaded connectome holds {@link #WEIGHTS}, {@link #SIZES} and
	 * {@link #OUT_SYNAPSES}.
	 *
	 * @param connectome the loaded connectome
	 */
	private static void assertExpectedTensors(CellTypeConnectome connectome) {
		double[] expectedWeights = new double[WEIGHTS.length];
		for (int i = 0; i < WEIGHTS.length; i++) expectedWeights[i] = WEIGHTS[i];
		Assert.assertArrayEquals(expectedWeights, connectome.getWeights().toArray(), 0.0);
		Assert.assertArrayEquals(new double[] { 4, 2, 5 }, connectome.getTypeSizes().toArray(), 0.0);
		Assert.assertArrayEquals(new double[] { 17, 1, 6 }, connectome.getOutSynapses().toArray(), 0.0);
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

			assertExpectedTensors(connectome);
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

	/**
	 * A weights file without one of the three required tensors is rejected with an
	 * {@link IllegalArgumentException} naming the missing tensor, rather than loading a
	 * connectome whose outgoing synapse counts are {@code null}.
	 */
	@Test(timeout = 60000)
	public void rejectsMissingTensor() throws IOException {
		File directory = writeDirectory(3, String.join("\n", NAMES), ANNOTATIONS, false);
		try {
			CellTypeConnectome.load(directory).destroy();
			Assert.fail("Expected an IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("type_out_synapses"));
		}
	}

	/**
	 * A directory in the text layout loads to the tensors, names and annotations the safetensors
	 * layout gives, with every entry the list does not name left at zero.
	 */
	@Test(timeout = 60000)
	public void loadsTextLayout() throws IOException {
		try (CellTypeConnectome connectome = CellTypeConnectome.load(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS));
			 PackedCollection inverse = connectome.inverseTypeSizes().evaluate()) {
			assertEquals(3, connectome.getTypeCount());
			Assert.assertEquals(NAMES, connectome.getTypeNames());
			assertExpectedTensors(connectome);
			Assert.assertArrayEquals(new double[] { 0.25, 0.5, 0.2 }, inverse.toArray(), 1e-6);
			Assert.assertEquals("gustatory", connectome.getTypeClass(0));
		}
	}

	/** An entry list holding only its header line loads as a matrix of zeros. */
	@Test(timeout = 60000)
	public void loadsEmptyTextEntryList() throws IOException {
		try (CellTypeConnectome connectome = CellTypeConnectome.load(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS_HEADER))) {
			Assert.assertArrayEquals(new double[WEIGHTS.length], connectome.getWeights().toArray(), 0.0);
			Assert.assertArrayEquals(new double[] { 4, 2, 5 }, connectome.getTypeSizes().toArray(), 0.0);
		}
	}

	/**
	 * The text layout is read only when there is no safetensors file: a directory holding both
	 * loads the safetensors tensors, whatever the text files say.
	 */
	@Test(timeout = 60000)
	public void prefersSafetensorsOverTextLayout() throws IOException {
		File directory = writeValidDirectory();
		addTextTensors(directory, "type\tsize\tout_synapses\nLB3a\t9\t9\nMN9\t9\t9\nKenyon\t9\t9\n",
				TEXT_WEIGHTS_HEADER + "0\t0\t99\n");
		try (CellTypeConnectome connectome = CellTypeConnectome.load(directory)) {
			assertExpectedTensors(connectome);
		}
	}

	/**
	 * An entry of the text layout that names a row or column outside the matrix, has the wrong
	 * number of fields, or holds a value that is not a number is rejected.
	 */
	@Test(timeout = 60000)
	public void rejectsMalformedTextEntry() throws IOException {
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "3\t0\t1\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t3\t1\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "-1\t0\t1\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\t2\t3\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\tx\t1\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\tmany\n"));
	}

	/**
	 * An entry of the text layout whose weight is not a finite single-precision value, whether
	 * written as {@code NaN}, as an infinity or as a number beyond the single-precision range, is
	 * rejected, since it would make every rate it reaches non-finite; the largest finite
	 * single-precision weight loads as given.
	 */
	@Test(timeout = 60000)
	public void rejectsNonFiniteTextEntry() throws IOException {
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\tNaN\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\tInfinity\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\t-Infinity\n"));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS, TEXT_WEIGHTS + "0\t1\t1e39\n"));

		try (CellTypeConnectome connectome = CellTypeConnectome.load(writeTextDirectory(TEXT_COUNTS,
				TEXT_WEIGHTS_HEADER + "0\t1\t" + Float.MAX_VALUE + "\n"))) {
			double[] weights = connectome.getWeights().toArray();
			Assert.assertEquals(Float.MAX_VALUE, weights[1], 0.0);
			Assert.assertEquals(0.0, weights[0], 0.0);
		}
	}

	/**
	 * A counts table of the text layout that does not list every type, in index order, with an
	 * integer size and outgoing synapse count is rejected.
	 */
	@Test(timeout = 60000)
	public void rejectsMalformedTextCounts() throws IOException {
		String header = "type\tsize\tout_synapses\n";
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t4\t17\nMN9\t2\t1\n", TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(TEXT_COUNTS + "Extra\t1\t1\n", TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(header + "MN9\t2\t1\nLB3a\t4\t17\nKenyon\t5\t6\n", TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t4\nMN9\t2\t1\nKenyon\t5\t6\n", TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t4.5\t17\nMN9\t2\t1\nKenyon\t5\t6\n", TEXT_WEIGHTS));
	}

	/**
	 * A counts table of the text layout giving a type no neurons, a negative size or a negative
	 * outgoing synapse count is rejected, since {@link CellTypeConnectome#inverseTypeSizes()}
	 * would scale that type's row by an invalid factor; a size of one and no outgoing synapses,
	 * the smallest valid counts, load as given.
	 */
	@Test(timeout = 60000)
	public void rejectsInvalidTextCounts() throws IOException {
		String header = "type\tsize\tout_synapses\n";
		String rest = "MN9\t2\t1\nKenyon\t5\t6\n";
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t0\t17\n" + rest, TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t-4\t17\n" + rest, TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t4\t-1\n" + rest, TEXT_WEIGHTS));
		assertThrowsLoad(writeTextDirectory(header + "LB3a\t4\t17\nMN9\t2\t1\nKenyon\t0\t6\n", TEXT_WEIGHTS));

		try (CellTypeConnectome connectome = CellTypeConnectome.load(
				writeTextDirectory(header + "LB3a\t1\t0\n" + rest, TEXT_WEIGHTS))) {
			Assert.assertArrayEquals(new double[] { 1, 2, 5 }, connectome.getTypeSizes().toArray(), 0.0);
			Assert.assertArrayEquals(new double[] { 0, 1, 6 }, connectome.getOutSynapses().toArray(), 0.0);
		}
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
