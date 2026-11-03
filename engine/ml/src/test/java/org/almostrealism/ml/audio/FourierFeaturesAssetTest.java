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

package org.almostrealism.ml.audio;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.Layer;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.GradientRecorder;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Pins the Fourier feature projection built from the {@code fourier_features.pdsl} asset (see
 * {@link DiffusionTransformerFeatures#fourierFeatures} / {@link DiffusionTransformerFeatures#expoFourierFeatures})
 * against two references that do not depend on the asset's implementation:
 * <ul>
 *   <li>{@link #hostReference}, the {@code [cos(2π · x · Wᵀ) | sin(2π · x · Wᵀ)]} arithmetic written out
 *       in plain double precision on the host, so the value, the {@code 2π} factor and the cosine-then-sine
 *       ordering are checked independently of the framework layers; and</li>
 *   <li>the Java producer assembly that preceded the asset (the {@code concat(cos(...), sin(...))} producer
 *       form of {@link DiffusionTransformerFeatures#fourierFeatures(int, int, Producer, PackedCollection)},
 *       which the pre-migration block form wrapped in a single {@code layer}), so the migration is pinned
 *       against the exact structure it replaced.</li>
 * </ul>
 *
 * <p>Both the batch-one and batch-three cases are covered, because the asset concatenates the cosine and
 * sine planes flat and transposes them back into an interleaved {@code [batch, 2·half]} layout; a single
 * batch row would not exercise that transpose. The {@link #learnedFrequenciesReceiveGradient} case checks
 * that the bound frequency matrix still trains through the asset, as it did when the block form registered
 * it as the layer's weight.</p>
 */
public class FourierFeaturesAssetTest extends TestSuiteBase implements DiffusionTransformerFeatures, ModelTestFeatures {

	/** Absolute tolerance between the single-precision block and the double-precision references. */
	private static final double TOLERANCE = 1e-4;

	/** Step of the central finite differences. */
	private static final double FINITE_DIFFERENCE_STEP = 1e-2;

	/** Agreement required with the finite differences, relative to the largest gradient compared. */
	private static final double GRADIENT_TOLERANCE = 0.02;

	/**
	 * For a single batch row the asset reproduces the host {@code [cos | sin]} reference and the producer
	 * assembly it replaced.
	 */
	@Test(timeout = 300000)
	public void fourierAssetMatchesReferencesBatchOne() {
		assertMatchesReferences(1, 4, 7L);
	}

	/**
	 * For three batch rows the asset still reproduces both references: this exercises the flat
	 * concatenation of the cosine and sine planes and the transpose that interleaves them back into the
	 * per-row {@code [cos | sin]} layout.
	 */
	@Test(timeout = 300000)
	public void fourierAssetMatchesReferencesBatchThree() {
		assertMatchesReferences(3, 5, 13L);
	}

	/**
	 * The deterministic (expo) variant reproduces the host reference over its geometric frequency ladder at
	 * a batch size the locked {@link ExpoFourierFeaturesTest} does not cover, so the asset is exercised with
	 * a computed rather than a drawn frequency matrix.
	 */
	@Test(timeout = 300000)
	public void expoFourierAssetMatchesHostReference() {
		int batch = 2;
		int outFeatures = 8;
		double minFreq = 0.5;
		double maxFreq = 50.0;

		PackedCollection timestep = seededInput(batch, 1, new Random(21));
		PackedCollection frequencies = evaluate(expoFourierFrequencies(outFeatures, minFreq, maxFreq));

		double[] expected = hostReference(timestep, frequencies, batch, outFeatures / 2);
		double[] actual = run(expoFourierFeatures(batch, outFeatures, minFreq, maxFreq), timestep, shape(batch, 1));
		assertClose("expo asset vs host", expected, actual);
	}

	/**
	 * The bound frequency matrix is a registered weight of the asset: the backward pass delivers to it the
	 * gradient that central finite differences of {@code L = sum(output * G)} imply, for a fixed output
	 * gradient {@code G}, so the migrated projection trains exactly as the block form it replaced did when it
	 * registered the same matrix as its weight.
	 */
	@Test(timeout = 300000)
	public void learnedFrequenciesReceiveGradient() {
		int batch = 2;
		int inFeatures = 1;
		int half = 3;
		int outFeatures = 2 * half;

		Random random = new Random(29);
		PackedCollection frequencies = uniform(shape(half, inFeatures), -0.3, 0.3, random);
		PackedCollection input = seededInput(batch, inFeatures, random);
		PackedCollection outputGradient = seededInput(batch, outFeatures, random);

		TraversalPolicy inputShape = shape(batch, inFeatures);
		GradientRecorder recorder = new GradientRecorder();
		Model model = new Model(inputShape, recorder);
		model.add(fourierFeatures(batch, inFeatures, outFeatures, frequencies));
		try (CompiledModel compiled = model.compile(true, true)) {
			compiled.forward(input);
			compiled.backward(outputGradient);

			TraversalPolicy outputShape = shape(batch, outFeatures);
			Evaluable<PackedCollection> loss = multiply(cv(outputShape, 0), cv(outputShape, 1)).sum().get();
			DoubleSupplier objective = () -> loss.evaluate(compiled.forward(input), outputGradient).toDouble();

			assertGradientMatches("frequencies",
					centralDifferences(frequencies, FINITE_DIFFERENCE_STEP, objective),
					recorder.gradientOfSize(frequencies.getShape().getTotalSize()), GRADIENT_TOLERANCE, 0.0);
		}
	}

	/**
	 * The deterministic (expo) frequency ladder must stay fixed. Its projection is a weightless
	 * producer, so the block registers no trainable weight that an optimizer could update, unlike the
	 * learned {@link DiffusionTransformerFeatures#fourierFeatures(int, int, int, PackedCollection)}
	 * path whose matrix the asset's {@code dense} stage registers as a trainable weight (pinned by
	 * {@link #learnedFrequenciesReceiveGradient}). This guards the regression where routing the expo
	 * ladder through the asset would have made the fixed frequencies trainable.
	 */
	@Test(timeout = 120000)
	public void expoFourierFeaturesRegistersNoTrainableWeight() {
		Block features = expoFourierFeatures(2, 8, 0.5, 50.0);
		Assert.assertTrue("expo Fourier features should build a layer", features instanceof Layer);
		Assert.assertTrue("the deterministic frequency ladder must not be a trainable weight",
				((Layer) features).getWeights().isEmpty());
	}

	/**
	 * Builds the asset for a learned frequency matrix and compares it with the host reference and the
	 * producer assembly it replaced.
	 *
	 * @param batch the batch size
	 * @param half  the number of frequencies (the output width is {@code 2 · half})
	 * @param seed  the seed of the frequency matrix and the input
	 */
	private void assertMatchesReferences(int batch, int half, long seed) {
		int inFeatures = 1;
		int outFeatures = 2 * half;

		Random random = new Random(seed);
		PackedCollection frequencies = uniform(shape(half, inFeatures), -0.5, 0.5, random);
		PackedCollection input = seededInput(batch, inFeatures, random);

		double[] asset = run(fourierFeatures(batch, inFeatures, outFeatures, frequencies), input, shape(batch, inFeatures));
		double[] host = hostReference(input, frequencies, batch, half);
		double[] producer = run(producerAssembly(batch, inFeatures, outFeatures, frequencies), input, shape(batch, inFeatures));

		assertClose("asset vs host reference", host, asset);
		assertClose("asset vs producer assembly", producer, asset);
	}

	/**
	 * The pre-migration block form: the producer-level Fourier projection wrapped in a single layer, exactly
	 * as {@link DiffusionTransformerFeatures#fourierFeatures(int, int, int, PackedCollection)} built it before
	 * it became an asset loader.
	 *
	 * @param batchSize   the batch size
	 * @param inFeatures  the input width
	 * @param outFeatures the output width
	 * @param frequencies the projection matrix
	 * @return the reference block
	 */
	private Block producerAssembly(int batchSize, int inFeatures, int outFeatures, PackedCollection frequencies) {
		return layer("fourierFeaturesReference",
				shape(batchSize, inFeatures),
				shape(batchSize, outFeatures),
				in -> fourierFeatures(batchSize, outFeatures, in, frequencies));
	}

	/**
	 * The {@code [cos(2π · x · Wᵀ) | sin(2π · x · Wᵀ)]} projection in plain double precision: an oracle for
	 * one forward pass, independent of the framework layers and the asset. Row {@code b} of the output holds
	 * the {@code half} cosine components followed by the {@code half} sine components of that row's phases.
	 *
	 * @param input       the input, shape {@code [batch, in]}
	 * @param frequencies the projection matrix, shape {@code [half, in]}
	 * @param batch       the batch size
	 * @param half        the number of frequencies
	 * @return the expected {@code [batch, 2 · half]} output, row-major
	 */
	private double[] hostReference(PackedCollection input, PackedCollection frequencies, int batch, int half) {
		int inFeatures = frequencies.getShape().length(1);
		double[] x = input.doubleStream().toArray();
		double[] w = frequencies.doubleStream().toArray();
		double[] out = new double[batch * 2 * half];

		for (int b = 0; b < batch; b++) {
			for (int k = 0; k < half; k++) {
				double phase = 0.0;
				for (int i = 0; i < inFeatures; i++) {
					phase += x[b * inFeatures + i] * w[k * inFeatures + i];
				}
				phase *= 2.0 * Math.PI;
				out[b * 2 * half + k] = Math.cos(phase);
				out[b * 2 * half + half + k] = Math.sin(phase);
			}
		}
		return out;
	}

	/** Compiles {@code block} in a model of the given input shape and runs one forward pass. */
	private double[] run(Block block, PackedCollection input, TraversalPolicy inputShape) {
		Model model = new Model(inputShape);
		model.add(block);
		try (CompiledModel compiled = model.compile(false)) {
			return compiled.forward(input).doubleStream().toArray();
		}
	}

	/** A {@code [batch, features]} input drawn uniformly from {@code [-0.6, 0.6)} on the device. */
	private PackedCollection seededInput(int batch, int features, Random random) {
		return uniform(shape(batch, features), -0.6, 0.6, random);
	}

	/** Draws a tensor of the given shape uniformly in {@code [min, max)} from the seeded source, on the device. */
	private PackedCollection uniform(TraversalPolicy shape, double min, double max, Random random) {
		return rand(shape, random).multiply(max - min).add(min).reshape(shape).evaluate();
	}

	/** Evaluates a producer at the test boundary. */
	private PackedCollection evaluate(CollectionProducer producer) {
		return producer.evaluate();
	}

	/** Compares two vectors element-wise within {@link #TOLERANCE}. */
	private static void assertClose(String label, double[] expected, double[] actual) {
		Assert.assertEquals(label + " length", expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals(label + " element " + i, expected[i], actual[i], TOLERANCE);
		}
	}
}
