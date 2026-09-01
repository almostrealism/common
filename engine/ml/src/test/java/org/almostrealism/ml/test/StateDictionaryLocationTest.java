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

package org.almostrealism.ml.test;

import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.persist.assets.CollectionEncoder;
import org.almostrealism.protobuf.Collections;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Pins that a weight library is read from where it was written.
 *
 * <p>Weights used to be parsed in full at load: every tensor became protobuf
 * objects on the Java heap, whether or not anything went on to use it, and
 * stayed there for the life of the model. Now the file is walked for structure
 * and the values are left in it. What must not change is what a weight
 * contains — the file is the same file, and reading it a cheaper way has to
 * produce the same numbers.</p>
 */
public class StateDictionaryLocationTest extends TestSuiteBase {
	/** The first tensor written by these tests. */
	private PackedCollection first() {
		return PackedCollection.of(1.5, -2.25, 0.0, 1024.75);
	}

	/** The second tensor written by these tests. */
	private PackedCollection second() {
		return PackedCollection.of(0.125, 8.5, -16.0);
	}

	/**
	 * Writes a weight library holding both tensors.
	 *
	 * @param precision precision to encode the values at
	 * @return the directory holding the library
	 * @throws IOException if the library cannot be written
	 */
	private File library(Precision precision) throws IOException {
		File dir = Files.createTempDirectory("weights").toFile();
		dir.deleteOnExit();

		Collections.CollectionLibraryData library = Collections.CollectionLibraryData
				.newBuilder()
				.addCollections(entry("first", first(), precision))
				.addCollections(entry("second", second(), precision))
				.build();

		File file = new File(dir, "weights.pb");
		file.deleteOnExit();

		try (FileOutputStream out = new FileOutputStream(file)) {
			library.writeTo(out);
		}

		return dir;
	}

	/**
	 * Builds a library entry holding the given values.
	 *
	 * @param key       name of the tensor
	 * @param values    its values
	 * @param precision precision to encode them at
	 * @return the entry
	 */
	private Collections.CollectionLibraryEntry entry(String key, PackedCollection values,
													 Precision precision) {
		return Collections.CollectionLibraryEntry.newBuilder()
				.setKey(key)
				.setCollection(CollectionEncoder.encode(values, precision))
				.build();
	}

	/**
	 * Reads a collection's values onto the host.
	 *
	 * @param c the collection
	 * @return its values
	 */
	private double[] valuesOf(PackedCollection c) {
		double[] out = new double[c.getMemLength()];

		for (int i = 0; i < out.length; i++) {
			out[i] = c.toDouble(i);
		}

		return out;
	}

	/**
	 * Reads a weight's values onto the host.
	 *
	 * @param weights the loaded weights
	 * @param key     name of the tensor
	 * @return its values
	 */
	private double[] valuesOf(StateDictionary weights, String key) {
		return valuesOf(weights.get(key));
	}

	/** Every tensor in the library is found, by name and by shape. */
	@Test(timeout = 60000)
	public void everyTensorIsFound() throws IOException {
		StateDictionary weights =
				new StateDictionary(library(Precision.FP64).getAbsolutePath());

		Assert.assertNotNull(weights.get("first"));
		Assert.assertNotNull(weights.get("second"));
		Assert.assertEquals(first().getMemLength(), weights.get("first").getMemLength());
		Assert.assertEquals(second().getMemLength(), weights.get("second").getMemLength());
	}

	/** The values read from the file are the values written to it. */
	@Test(timeout = 60000)
	public void theValuesAreTheOnesWritten() throws IOException {
		StateDictionary weights =
				new StateDictionary(library(Precision.FP64).getAbsolutePath());

		Assert.assertArrayEquals(valuesOf(first()), valuesOf(weights, "first"), 0.0);
		Assert.assertArrayEquals(valuesOf(second()), valuesOf(weights, "second"), 0.0);
	}

	/** Half-precision libraries read back the same way. */
	@Test(timeout = 60000)
	public void halfPrecisionValuesAreRead() throws IOException {
		StateDictionary weights =
				new StateDictionary(library(Precision.FP32).getAbsolutePath());

		Assert.assertArrayEquals(valuesOf(first()), valuesOf(weights, "first"), 1e-6);
		Assert.assertArrayEquals(valuesOf(second()), valuesOf(weights, "second"), 1e-6);
	}

	/**
	 * Locating and parsing produce the same weights.
	 *
	 * <p>The eager path is still there, and is what a caller asks for when it
	 * wants ordinary writable storage. Both read one file, so they must agree
	 * about what is in it; if they ever stop agreeing, one of them is reading
	 * the format wrongly.</p>
	 */
	@Test(timeout = 60000)
	public void locatingAgreesWithParsing() throws IOException {
		File dir = library(Precision.FP64);

		StateDictionary located = new StateDictionary(dir.getAbsolutePath());

		StateDictionary parsed;
		StateDictionary.enableMaterializeWeights = true;

		try {
			parsed = new StateDictionary(dir.getAbsolutePath());
		} finally {
			StateDictionary.enableMaterializeWeights = false;
		}

		Assert.assertArrayEquals(valuesOf(parsed, "first"),
				valuesOf(located, "first"), 0.0);
		Assert.assertArrayEquals(valuesOf(parsed, "second"),
				valuesOf(located, "second"), 0.0);
	}
}
