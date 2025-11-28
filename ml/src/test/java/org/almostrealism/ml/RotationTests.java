/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import java.util.stream.IntStream;

public class RotationTests implements RotationFeatures, TestFeatures {

	@Test
	public void permutationCompilation() {
		int batchSize = 1, seqLen = 4, heads = 2, dimHead = 8;
		TraversalPolicy inputShape = shape(batchSize, seqLen, heads, dimHead);

		PackedCollection input = new PackedCollection(inputShape).randnFill();

		// Test 1: Direct permutation evaluation
		CollectionProducer<PackedCollection> directPermute = c(p(input))
				.permute(0, 2, 1, 3)
				.permute(0, 2, 1, 3); // Should be identity
		PackedCollection directResult = directPermute.evaluate();

		// Test 2: Sequential model permutation compilation
		Model model = new Model(inputShape);
		SequentialBlock main = model.sequential();
		main.permute(0, 2, 1, 3);
		main.permute(0, 2, 1, 3); // Should be identity

		CompiledModel compiled = model.compile(false);
		PackedCollection compiledResult = compiled.forward(input);

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

	@Test
	public void batchCosineProduct() {
		int batchSize = 2;
		int heads = 3;
		int seqLen = 8;
		int rotaryDim = 16;

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> Math.random());
		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim))
				.fill(pos -> Math.random());

		// Expand freqs from (seqLen, rotaryDim) to (batchSize, heads, seqLen, rotaryDim)
		CollectionProducer<PackedCollection> expandedFreqs = cp(freqs)
				.repeat(0, batchSize)    // (batchSize, seqLen, rotaryDim)
				.repeat(1, heads);       // (batchSize, heads, seqLen, rotaryDim)

		CollectionProducer<PackedCollection> cosFreqs = cos(expandedFreqs);
		CollectionProducer<PackedCollection> product = cp(input).multiply(cosFreqs);

		PackedCollection cosValues = product.evaluate();

		PackedCollection expected = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim));

		expected.fill(pos -> {
			int b = pos[0];
			int h = pos[1];
			int s = pos[2];
			int d = pos[3];
			double freqValue = freqs.valueAt(s, d);
			return input.valueAt(b, h, s, d) * Math.cos(freqValue);
		});

		double diff = compare(expected, cosValues);
		log("Diff = " + diff);
		assertTrue("Cosine values should match expected", diff < 1e-6);
	}

	@Test
	public void batchRotarySum() {
		int batchSize = 2;
		int heads = 3;
		int seqLen = 8;
		int rotaryDim = 16;

		PackedCollection leftIn = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> Math.random());
		PackedCollection rightIn = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> Math.random());
		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim))
				.fill(pos -> Math.random());

		CollectionProducer<PackedCollection> expandedFreqs = cp(freqs)
				.repeat(0, batchSize)    // (batchSize, seqLen, rotaryDim)
				.repeat(1, heads);       // (batchSize, heads, seqLen, rotaryDim)

		CollectionProducer<PackedCollection> cosFreqs = cos(expandedFreqs);
		CollectionProducer<PackedCollection> sinFreqs = sin(expandedFreqs);

		// left * cos(freqs) + right * sin(freqs)
		CollectionProducer<PackedCollection> sum = cp(leftIn).multiply(cosFreqs).add(cp(rightIn).multiply(sinFreqs));
		PackedCollection result = sum.evaluate();

		PackedCollection expected = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> {
					int b = pos[0];
					int h = pos[1];
					int s = pos[2];
					int d = pos[3];

					double freqValue = freqs.valueAt(s, d);
					double cosFreq = Math.cos(freqValue);
					double sinFreq = Math.sin(freqValue);
					return leftIn.valueAt(b, h, s, d) * cosFreq +
							rightIn.valueAt(b, h, s, d) * sinFreq;
				});

		double diff = compare(expected, result);
		log("Diff = " + diff);
		assertTrue("Cosine values should match expected", diff < 1e-6);
	}

	@Test
	public void rotateHalf() {
		int batchSize = 1;
		int heads = 2;
		int seqLen = 8;
		int rotaryDim = 16;

		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim)).randFill();

		PackedCollection out = rotateHalf(cp(input), batchSize, heads, seqLen, rotaryDim).evaluate();

		int halfDim = rotaryDim / 2;

		for (int h = 0; h < heads; h++) {
			for (int s = 0; s < seqLen; s++) {
				for (int d = 0; d < rotaryDim; d++) {
					double actual = out.valueAt(0, h, s, d);

					if (d < halfDim) {
						// First half should be -input[..., halfDim:] (negated second half of input)
						double expected = -input.valueAt(0, h, s, d + halfDim);
						assertEquals(expected, actual);
					} else {
						// Second half should be input[..., :halfDim] (first half of input)
						double expected = input.valueAt(0, h, s, d - halfDim);
						assertEquals(expected, actual);
					}
				}
			}
		}
	}

	@Test
	public void rotateHalfSum() {
		int batchSize = 1;
		int heads = 2;
		int seqLen = 8;
		int rotaryDim = 16;

		PackedCollection input = new PackedCollection(
				shape(batchSize, heads, seqLen, rotaryDim)).randFill();

		CollectionProducer<PackedCollection> sum =
				cp(input).add(rotateHalf(cp(input), batchSize, heads, seqLen, rotaryDim));
		PackedCollection result = sum.evaluate();

		int halfDim = rotaryDim / 2;

		PackedCollection expected = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> {
					int b = pos[0];
					int h = pos[1];
					int s = pos[2];
					int d = pos[3];

					double orig = input.valueAt(b, h, s, d);
					if (d < halfDim) {
						// First half should be -input[..., halfDim:] (negated second half of input)
						return orig - input.valueAt(b, h, s, d + halfDim);
					} else {
						// Second half should be input[..., :halfDim] (first half of input)
						return orig + input.valueAt(b, h, s, d - halfDim);
					}
				});

		double diff = compare(expected, result);
		log("Diff = " + diff);
		assertTrue("Average difference exceeded", diff < 1e-6);
	}

	@Test
	public void rotateHalfProductSum() {
		int batchSize = 1;
		int heads = 2;
		int seqLen = 8;
		int rotaryDim = 16;

		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim))
				.fill(pos -> Math.random());
		PackedCollection input = new PackedCollection(
				shape(batchSize, heads, seqLen, rotaryDim)).randFill();

		CollectionProducer<PackedCollection> in = cp(input);

		// Expand freqs from (seqLen, rotaryDim) to (batchSize, heads, seqLen, rotaryDim)
		CollectionProducer<PackedCollection> expandedFreqs = cp(freqs)
				.repeat(0, batchSize)    // (batchSize, seqLen, rotaryDim)
				.repeat(1, heads);       // (batchSize, heads, seqLen, rotaryDim)

		CollectionProducer<PackedCollection> cosFreqs = cos(expandedFreqs);
		CollectionProducer<PackedCollection> sinFreqs = sin(expandedFreqs);

		CollectionProducer<PackedCollection> rotateHalfInput =
				rotateHalf(in, batchSize, heads, seqLen, rotaryDim);
		CollectionProducer<PackedCollection> sum =
				in.multiply(cosFreqs).add(rotateHalfInput.multiply(sinFreqs));
		PackedCollection result = sum.evaluate();

		int halfDim = rotaryDim / 2;

		PackedCollection expected = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> {
					int b = pos[0];
					int h = pos[1];
					int s = pos[2];
					int d = pos[3];

					// Get the frequency value for this position and dimension
					double freqValue = freqs.valueAt(s, d);
					double cosFreq = Math.cos(freqValue);
					double sinFreq = Math.sin(freqValue);

					// Get the input value
					double inputValue = input.valueAt(b, h, s, d);

					// Compute rotate_half value for this position
					double rotateHalfValue;
					if (d < halfDim) {
						// First half gets negated second half
						rotateHalfValue = -input.valueAt(b, h, s, d + halfDim);
					} else {
						// Second half gets first half
						rotateHalfValue = input.valueAt(b, h, s, d - halfDim);
					}

					// input * cos(freq) + rotate_half(input) * sin(freq)
					return inputValue * cosFreq + rotateHalfValue * sinFreq;
				});

		double diff = compare(expected, result);
		log("Diff = " + diff);
		assertTrue("Average difference exceeded", diff < 1e-6);
	}

	@Test
	public void applyRotaryTransform() {
		int batchSize = 2;
		int heads = 3;
		int seqLen = 8;
		int rotaryDim = 16;

		// Create random input and frequency tensors
		PackedCollection input = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> Math.random());
		PackedCollection freqs = new PackedCollection(shape(seqLen, rotaryDim))
				.fill(pos -> Math.random());

		// Apply the rotary transform using the method under test
		CollectionProducer<PackedCollection> transform = applyRotaryTransform(
				cp(input), cp(freqs), batchSize, heads, seqLen, rotaryDim);
		PackedCollection result = transform.evaluate();

		int halfDim = rotaryDim / 2;

		PackedCollection expected = new PackedCollection(shape(batchSize, heads, seqLen, rotaryDim))
				.fill(pos -> {
					int b = pos[0];
					int h = pos[1];
					int s = pos[2];
					int d = pos[3];

					// Get the frequency value for this position and dimension
					double freqValue = freqs.valueAt(s, d);
					double cosFreq = Math.cos(freqValue);
					double sinFreq = Math.sin(freqValue);

					// Get the input value
					double inputValue = input.valueAt(b, h, s, d);

					// Compute rotate_half value for this position
					double rotateHalfValue;
					if (d < halfDim) {
						// First half gets negated second half
						rotateHalfValue = -input.valueAt(b, h, s, d + halfDim);
					} else {
						// Second half gets first half
						rotateHalfValue = input.valueAt(b, h, s, d - halfDim);
					}

					// input * cos(freq) + rotate_half(input) * sin(freq)
					return inputValue * cosFreq + rotateHalfValue * sinFreq;
				});

		double diff = compare(expected, result);
		log("Diff = " + diff);
		assertTrue("Average difference exceeded", diff < 1e-6);
	}

	@Test
	public void applyRotaryTransformCompare() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		String referenceDir = "/Users/michael/Documents/AlmostRealism/models/rotary_transform";

		// Load reference data
		StateDictionary referenceData = new StateDictionary(referenceDir);
		referenceData.keySet()
				.forEach(key -> System.out.println("\t" + key + " " + referenceData.get(key).getShape()));

		// Extract test configuration
		PackedCollection testConfig = referenceData.get("test_config");
		int batchSize = (int) testConfig.valueAt(0);
		int seqLen = (int) testConfig.valueAt(1);
		int heads = (int) testConfig.valueAt(2);
		int dimHead = (int) testConfig.valueAt(3);
		int rotaryDim = (int) testConfig.valueAt(4);

		log("Test configuration:");
		log("  batchSize=" + batchSize + ", seqLen=" + seqLen +
				", heads=" + heads + ", dimHead=" + dimHead + ", rotaryDim=" + rotaryDim);

		// Load test data
		PackedCollection input = referenceData.get("input");
		PackedCollection freqs = referenceData.get("freqs");
		PackedCollection invFreq = referenceData.get("inv_freq");
		PackedCollection expectedOutput = referenceData.get("expected_output");

		log("\n=== Testing computeRotaryFreqs ===");
		PackedCollection computedFreqs = computeRotaryFreqs(seqLen, invFreq);
		log("Computed freqs shape - " + computedFreqs.getShape());
		log("Expected freqs shape - " + freqs.getShape());

		double freqDiff = compare(freqs, computedFreqs);
		log("Total frequency difference = " + freqDiff);
		assertTrue(freqDiff < 1e-6);


		log("\n=== Testing applyRotaryPositionEmbedding ===");
		Model model = new Model(shape(batchSize, heads, seqLen, dimHead));
		SequentialBlock main = model.sequential();
		main.add(applyRotaryPositionEmbedding(invFreq));

		CompiledModel compiled = model.compile(false);
		PackedCollection actualOutput = compiled.forward(input);

		double diff = compare(expectedOutput, actualOutput);
		log("Expected output shape - " + expectedOutput.getShape());
		log("Actual output shape - " + actualOutput.getShape());
		log("Average difference per element = " + diff);
		assertTrue(expectedOutput.getShape().equalsIgnoreAxis(actualOutput.getShape()));
		assertTrue(diff < 1e-4);
	}

	@Test
	public void ropeRotation() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		int heads = 12;
		int headSize = 32;

		TraversalPolicy shape = shape(heads, headSize, 2);

		PackedCollection in = new PackedCollection(shape).randFill();
		PackedCollection weights = new PackedCollection(shape(1024, headSize, 2)).randFill();

		int p = 28;

		Producer<PackedCollection> pos = c(p, 0, 0);

		CollectionProducer<PackedCollection> q = c(p(in)).traverse(2);
		CollectionProducer<PackedCollection> r = subset(shape(1, headSize, 2),
				c(p(weights)), pos);
		// r = c(p(r.get().evaluate()));

		// CollectionProducer<PackedCollection> o = multiplyComplex(traverse(1, p(in)), r.reshape(headSize, 2));
		CollectionProducer<PackedCollection> o = multiplyComplex(traverse(1, p(in)), r.traverse(1));

		// TODO  Optimization should not be necessary
		// PackedCollection out = o.get().evaluate();
		PackedCollection out = ((Evaluable<PackedCollection>) ((ParallelProcess) o).optimize().get()).evaluate();

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
}