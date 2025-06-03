/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class RotationTests implements RotationFeatures, TestFeatures {
	@Test
	public void ropeRotation() {
		int heads = 12;
		int headSize = 32;

		TraversalPolicy shape = shape(heads, headSize, 2);

		PackedCollection<?> in = new PackedCollection<>(shape).randFill();
		PackedCollection<?> weights = new PackedCollection<>(shape(1024, headSize, 2)).randFill();

		int p = 28;

		Producer<PackedCollection<?>> pos = c(p, 0, 0);

		CollectionProducer<PackedCollection<?>> q = c(p(in)).traverse(2);
		CollectionProducer<PackedCollection<?>> r = subset(shape(1, headSize, 2),
															c(p(weights)), pos);
		// r = c(p(r.get().evaluate()));

		// CollectionProducer<PackedCollection<?>> o = multiplyComplex(traverse(1, p(in)), r.reshape(headSize, 2));
		CollectionProducer<PackedCollection<?>> o = multiplyComplex(traverse(1, p(in)), r.traverse(1));

		// TODO  Optimization should not be necessary
		// PackedCollection<?> out = o.get().evaluate();
		PackedCollection<?> out = ((Evaluable<PackedCollection<?>>) ((ParallelProcess) o).optimize().get()).evaluate();

		for (int h = 0; h < heads; h++) {
			for (int i = 0; i < headSize; i++) {
				double q0 = in.valueAt(h, i, 0);
				double q1 = in.valueAt(h, i, 1);
				double fcr = weights.valueAt(p, i, 0);
				double fci = weights.valueAt(p, i, 1);

				double expected = q0 * fcr - q1 * fci;
				double actual = out.valueAt(h, i, 0);
				System.out.println("RotationTests[" + h + "][" + i + "]: " + expected + " vs " + actual);
				assertEquals(expected, actual);

				expected = q0 * fci + q1 * fcr;
				actual = out.valueAt(h, i, 1);
				System.out.println("RotationTests[" + h + "][" + i + "]: " + expected + " vs " + actual);
				assertEquals(expected, actual);
			}
		}
	}

	@Test
	public void rotateHalf() {
		int batchSize = 1;
		int heads = 2;
		int seqLen = 4;
		int rotaryDim = 4;

		PackedCollection<?> input = new PackedCollection<>(shape(batchSize, heads, seqLen, rotaryDim)).randFill();

		PackedCollection<?> out = rotateHalf(cp(input), batchSize, heads, seqLen, rotaryDim).evaluate();

		PackedCollection<?> dividedInput = input.reshape(batchSize, heads, seqLen, rotaryDim / 2, 2);

		for (int h = 0; h < heads; h++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < rotaryDim; d++) {
					double actual = out.valueAt(0, h, s, d);

					int dHalf = d % (rotaryDim / 2);

					if (d < rotaryDim / 2) {
						// Negated odd elements
						double expected = -dividedInput.valueAt(0, h, s, dHalf, 1);
						assertEquals(expected, actual);
					} else {
						// Followed by even elements
						double expected = dividedInput.valueAt(0, h, s, dHalf, 0);
						assertEquals(expected, actual);
					}
				}
			}
		}
	}

	@Test
	public void permutationCompilation() {
		int batchSize = 1, seqLen = 4, heads = 2, dimHead = 8;
		TraversalPolicy inputShape = shape(batchSize, seqLen, heads, dimHead);

		PackedCollection<?> input = new PackedCollection<>(inputShape).randnFill();

		// Test 1: Direct permutation evaluation
		CollectionProducer<PackedCollection<?>> directPermute = c(p(input))
				.permute(0, 2, 1, 3)
				.permute(0, 2, 1, 3); // Should be identity
		PackedCollection<?> directResult = directPermute.evaluate();

		// Test 2: Sequential model permutation compilation
		Model model = new Model(inputShape);
		SequentialBlock main = model.sequential();
		main.permute(0, 2, 1, 3);
		main.permute(0, 2, 1, 3); // Should be identity

		CompiledModel compiled = model.compile(false);
		PackedCollection<?> compiledResult = compiled.forward(input);

		log("Input total: " + input.doubleStream().sum());
		log("Direct result total: " + directResult.doubleStream().sum());
		log("Compiled result total: " + compiledResult.doubleStream().sum());

		double diff = compare(input, compiledResult);
		log("Permutation compilation difference: " + diff);

		if (Math.abs(diff) > 1e-6) {
			log("ERROR: SequentialBlock permutation compilation is broken!");

			// Print detailed comparison
			for (int i = 0; i < Math.min(20, input.getShape().getTotalSize()); i++) {
				log("  [" + i + "] input=" + input.toDouble(i) +
						", compiled=" + compiledResult.toDouble(i));
			}
		}

		assertEquals(input, compiledResult);
	}
}