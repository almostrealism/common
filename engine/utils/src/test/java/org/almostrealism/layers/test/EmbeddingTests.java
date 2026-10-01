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

package org.almostrealism.layers.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for {@link LayerFeatures#embedding}: the forward pass gathers the selected rows of the
 * table, and the backward pass delivers to the table the gradient of a gather, which accumulates
 * the output gradient of every position into the row that position selected (repeated ids add
 * up, unselected rows receive zero).
 */
public class EmbeddingTests extends TestSuiteBase implements LayerFeatures, ModelTestFeatures {
	/** Vocabulary size. */
	private static final int VOCAB = 6;
	/** Embedding dimension. */
	private static final int DIM = 4;
	/** Token ids, with a repeat (2) and unused rows (0, 5). */
	private static final int[] IDS = { 2, 4, 2, 1, 3 };

	/** The forward pass replaces each id with its table row. */
	@Test(timeout = 120000)
	public void forwardGathersRows() {
		Random random = new Random(1);
		PackedCollection table = randn(shape(VOCAB, DIM), 0.0, 1.0, random).evaluate();
		Model model = new Model(shape(IDS.length));
		model.add(embedding(shape(IDS.length), table));
		PackedCollection out = model.compile(false).forward(ids());

		for (int r = 0; r < IDS.length; r++) {
			for (int c = 0; c < DIM; c++) {
				Assert.assertEquals(table.valueAt(IDS[r], c), out.toDouble(r * DIM + c), 1e-6);
			}
		}
	}

	/** The table gradient is the output gradient scattered (and summed) into the selected rows. */
	@Test(timeout = 120000)
	public void tableGradient() {
		Random random = new Random(2);
		PackedCollection table = randn(shape(VOCAB, DIM), 0.0, 1.0, random).evaluate();
		PackedCollection outputGradient = randn(shape(IDS.length, DIM), 0.0, 1.0, random).evaluate();

		List<PackedCollection> recorded = new ArrayList<>();
		Model model = new Model(shape(IDS.length), gradientRecorder(recorded));
		model.add(embedding(shape(IDS.length), table));
		CompiledModel compiled = model.compile(true);
		compiled.forward(ids());
		compiled.backward(outputGradient);

		Assert.assertEquals(1, recorded.size());
		PackedCollection gradient = recorded.get(0);

		for (int k = 0; k < VOCAB; k++) {
			for (int c = 0; c < DIM; c++) {
				double expected = 0.0;
				for (int r = 0; r < IDS.length; r++) {
					if (IDS[r] == k) expected += outputGradient.toDouble(r * DIM + c);
				}

				Assert.assertEquals("row " + k + " column " + c, expected,
						gradient.toDouble(k * DIM + c), 1e-5);
			}
		}
	}

	/**
	 * Returns the token ids as a collection.
	 *
	 * @return the ids
	 */
	private PackedCollection ids() {
		PackedCollection ids = new PackedCollection(shape(IDS.length));
		for (int i = 0; i < IDS.length; i++) {
			ids.setMem(i, IDS[i]);
		}

		return ids;
	}
}
