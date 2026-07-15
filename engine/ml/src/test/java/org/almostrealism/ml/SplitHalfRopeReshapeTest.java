/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the permutations performed by {@link AttentionFeatures#reshapeToSplitHalfRope(int, int, int)}
 * and {@link AttentionFeatures#reshapeFromSplitHalfRope(int, int)}.
 *
 * <p>Each input is filled with its own element index, so every output element carries the index of
 * the input element it was gathered from. Asserting on the output therefore pins the permutation
 * exactly, rather than merely checking that values are plausible.</p>
 *
 * @see AttentionFeatures
 */
public class SplitHalfRopeReshapeTest extends TestSuiteBase implements AttentionFeatures {

	/** Number of attention heads used by every case here. */
	private static final int HEADS = 3;

	/** Size of each head; must be even for the split-half format. */
	private static final int HEAD_SIZE = 4;

	/** Number of rotary frequencies per head, being half of {@link #HEAD_SIZE}. */
	private static final int FREQ_DIM = HEAD_SIZE / 2;

	/** Flat input dimension, being {@link #HEADS} times {@link #HEAD_SIZE}. */
	private static final int FLAT_DIM = HEADS * HEAD_SIZE;

	/**
	 * Creates a collection of the given shape in which every element holds its own index.
	 *
	 * @param shape the shape of the collection to create
	 * @return a collection whose element at index i has the value i
	 */
	private PackedCollection indexValued(TraversalPolicy shape) {
		int size = shape.getTotalSize();
		PackedCollection result = new PackedCollection(shape);
		a(cp(result.reshape(shape(size))), integers(0, size)).get().run();
		return result;
	}

	/**
	 * Verifies that the split-half reshape sends the first half of each head to index 0 of the
	 * trailing dimension and the second half to index 1.
	 */
	@Test(timeout = 60000)
	public void reshapeToSplitHalfRopeSeparatesHalves() {
		PackedCollection input = indexValued(shape(1, FLAT_DIM));

		Model model = new Model(shape(1, FLAT_DIM));
		model.add(reshapeToSplitHalfRope(FLAT_DIM, HEADS, HEAD_SIZE));
		PackedCollection output = model.compile().forward(input);

		for (int h = 0; h < HEADS; h++) {
			for (int f = 0; f < FREQ_DIM; f++) {
				Assert.assertEquals("output[" + h + ", " + f + ", 0]",
						h * HEAD_SIZE + f, output.toDouble(h * FREQ_DIM * 2 + f * 2), 1e-9);
				Assert.assertEquals("output[" + h + ", " + f + ", 1]",
						h * HEAD_SIZE + FREQ_DIM + f, output.toDouble(h * FREQ_DIM * 2 + f * 2 + 1), 1e-9);
			}
		}
	}

	/**
	 * Verifies that the reverse reshape returns index 0 of the trailing dimension to the first
	 * half of each head and index 1 to the second half.
	 */
	@Test(timeout = 60000)
	public void reshapeFromSplitHalfRopeRejoinsHalves() {
		PackedCollection input = indexValued(shape(HEADS, FREQ_DIM, 2));

		Model model = new Model(shape(HEADS, FREQ_DIM, 2));
		model.add(reshapeFromSplitHalfRope(HEADS, HEAD_SIZE));
		PackedCollection output = model.compile().forward(input);

		for (int h = 0; h < HEADS; h++) {
			for (int f = 0; f < FREQ_DIM; f++) {
				Assert.assertEquals("output[" + h + ", " + f + "]",
						h * FREQ_DIM * 2 + f * 2, output.toDouble(h * HEAD_SIZE + f), 1e-9);
				Assert.assertEquals("output[" + h + ", " + (FREQ_DIM + f) + "]",
						h * FREQ_DIM * 2 + f * 2 + 1, output.toDouble(h * HEAD_SIZE + FREQ_DIM + f), 1e-9);
			}
		}
	}

	/**
	 * Verifies that the two reshapes are inverses, so that applying one after the other restores
	 * every element to its original position.
	 */
	@Test(timeout = 60000)
	public void splitHalfRopeReshapesAreInverses() {
		PackedCollection input = indexValued(shape(1, FLAT_DIM));

		Model model = new Model(shape(1, FLAT_DIM));
		model.add(reshapeToSplitHalfRope(FLAT_DIM, HEADS, HEAD_SIZE));
		model.add(reshapeFromSplitHalfRope(HEADS, HEAD_SIZE));
		PackedCollection output = model.compile().forward(input);

		for (int i = 0; i < FLAT_DIM; i++) {
			Assert.assertEquals("output[" + i + "]", i, output.toDouble(i), 1e-9);
		}
	}
}
