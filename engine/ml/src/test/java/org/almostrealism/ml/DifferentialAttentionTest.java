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

package org.almostrealism.ml;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.layers.ProjectionFactory;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Standalone tests for differential attention, the first {@link AttentionVariant} provided behind
 * the {@link AttentionFeatures#selfAttention} seam by {@link DifferentialAttentionFeatures}.
 *
 * <p>The differential variant projects a fused {@code dim*5} {@code to_qkv} whose five equal
 * sections are {@code [Q1, K1, K2, V, Q2]} and produces {@code (map1 - lambda*map2)*V}, where
 * {@code map1 = softmax(Q1.K1)} and {@code map2 = softmax(Q2.K2)}. Because matrix multiplication is
 * linear this equals {@code map1*V - lambda*(map2*V)}; with a per-head-uniform {@code lambda} the
 * subsequent output projection {@code Wo} (which mixes heads) factors out, so the whole block
 * satisfies {@code diff = s1 - lambda*s2}, where {@code s1} and {@code s2} are the framework's own
 * standard {@link AttentionFeatures#sequenceAttention} over {@code [Q1, K1, V]} and
 * {@code [Q2, K2, V]} respectively. The tests use that identity (a framework reference rather than a
 * hand-rolled softmax) to verify the numerics, and check that {@code lambda = 0} collapses the
 * variant onto the standard path and that the {@code STANDARD} variant is byte-for-byte the existing
 * {@code sequenceAttention}. No Stable Audio 3 weights are involved.</p>
 */
public class DifferentialAttentionTest extends TestSuiteBase implements DifferentialAttentionFeatures {

	/** Batch dimension; scaled-dot-product attention currently asserts a batch size of 1. */
	private static final int BATCH = 1;
	/** Sequence length. */
	private static final int SEQ_LEN = 4;
	/** Model dimension. */
	private static final int DIM = 16;
	/** Number of attention heads. */
	private static final int HEADS = 2;
	/** Per-head dimension ({@code DIM/HEADS} = 8, so RoPE rotates the first {@code dimHead/4 = 2} dims). */
	private static final int DIM_HEAD = DIM / HEADS;

	/**
	 * With {@code lambda = 0} the differential block must reduce exactly to standard
	 * scaled-dot-product attention over the {@code [Q1, K1, V]} sections — i.e. the variant
	 * collapses onto the default path when its learned contribution is switched off.
	 */
	@Test(timeout = 240000)
	public void differentialReducesToStandardWhenLambdaZero() {
		Weights w = new Weights();
		PackedCollection input = new PackedCollection(inputShape()).randnFill();

		PackedCollection diffOut = run(differentialSequenceAttention(
				BATCH, SEQ_LEN, DIM, HEADS,
				w.toQkv5, w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq, lambda(0.0)), input);

		PackedCollection standardOut = run(sequenceAttention(
				BATCH, SEQ_LEN, DIM, HEADS,
				w.standardQkv(0, 1, 3), w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq), input);

		double diff = compare(standardOut, diffOut);
		log("differential(lambda=0) vs standard difference = " + diff);
		assertTrue("Differential attention with lambda=0 must equal standard attention", diff < 1e-5);
	}

	/**
	 * With a per-head-uniform {@code lambda} the differential output must equal
	 * {@code s1 - lambda*s2}, where {@code s1} and {@code s2} are standard attention over the first
	 * and second query/key maps. This verifies the documented subtraction of the two attention maps
	 * against an independent framework reference.
	 */
	@Test(timeout = 240000)
	public void differentialMatchesTwoMapSubtraction() {
		double lambdaValue = 0.5;

		Weights w = new Weights();
		PackedCollection input = new PackedCollection(inputShape()).randnFill();

		PackedCollection diffOut = run(differentialSequenceAttention(
				BATCH, SEQ_LEN, DIM, HEADS,
				w.toQkv5, w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq, lambda(lambdaValue)), input);

		PackedCollection s1 = run(sequenceAttention(
				BATCH, SEQ_LEN, DIM, HEADS,
				w.standardQkv(0, 1, 3), w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq), input);

		PackedCollection s2 = run(sequenceAttention(
				BATCH, SEQ_LEN, DIM, HEADS,
				w.standardQkv(4, 2, 3), w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq), input);

		// Reference (host side, at the top of the call stack): expected = s1 - lambda*s2
		double[] s1v = s1.toArray();
		double[] s2v = s2.toArray();
		double[] diffv = diffOut.toArray();
		assertEquals(s1v.length, diffv.length);

		double maxError = 0.0;
		for (int i = 0; i < diffv.length; i++) {
			double expected = s1v[i] - lambdaValue * s2v[i];
			maxError = Math.max(maxError, Math.abs(expected - diffv[i]));
			assertEquals(expected, diffv[i], 1e-4);
		}
		log("differential(lambda=" + lambdaValue + ") vs (s1 - lambda*s2) max error = " + maxError);
	}

	/**
	 * The {@link AttentionVariant#STANDARD} path threaded through {@link #selfAttention} must be
	 * identical to the pre-existing {@link AttentionFeatures#sequenceAttention}, proving the seam
	 * leaves the default attention behaviour unchanged.
	 */
	@Test(timeout = 240000)
	public void standardVariantMatchesSequenceAttention() {
		Weights w = new Weights();
		PackedCollection input = new PackedCollection(inputShape()).randnFill();
		PackedCollection qkv = w.standardQkv(0, 1, 3);

		PackedCollection viaSeam = run(selfAttention(
				BATCH, SEQ_LEN, DIM, HEADS, AttentionVariant.STANDARD,
				qkv, w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq, null, ProjectionFactory.dense()), input);

		PackedCollection viaSequence = run(sequenceAttention(
				BATCH, SEQ_LEN, DIM, HEADS,
				qkv, w.toOut,
				w.qNormWeight, w.qNormBias, w.kNormWeight, w.kNormBias,
				w.invFreq), input);

		double diff = compare(viaSequence, viaSeam);
		log("selfAttention(STANDARD) vs sequenceAttention difference = " + diff);
		assertTrue("STANDARD variant must equal the pre-change sequenceAttention path", diff < 1e-6);
	}

	/**
	 * Verifies the differential lambda re-parameterization
	 * {@code lambda = exp(lambdaQ1 . lambdaK1) - exp(lambdaQ2 . lambdaK2) + lambdaInit} against a
	 * hand-computed reference, independent of the attention computation.
	 */
	@Test(timeout = 120000)
	public void lambdaReparameterization() {
		int heads = 2;
		int lambdaDim = 3;
		double lambdaInit = 0.05;

		PackedCollection lambdaQ1 = new PackedCollection(shape(heads, lambdaDim)).randnFill();
		PackedCollection lambdaK1 = new PackedCollection(shape(heads, lambdaDim)).randnFill();
		PackedCollection lambdaQ2 = new PackedCollection(shape(heads, lambdaDim)).randnFill();
		PackedCollection lambdaK2 = new PackedCollection(shape(heads, lambdaDim)).randnFill();

		Producer<PackedCollection> lambda =
				differentialLambda(lambdaQ1, lambdaK1, lambdaQ2, lambdaK2, lambdaInit);
		PackedCollection actual = evaluate(lambda);

		assertEquals(heads, actual.getShape().getTotalSize());

		for (int h = 0; h < heads; h++) {
			double dot1 = 0.0;
			double dot2 = 0.0;
			for (int d = 0; d < lambdaDim; d++) {
				dot1 += lambdaQ1.valueAt(h, d) * lambdaK1.valueAt(h, d);
				dot2 += lambdaQ2.valueAt(h, d) * lambdaK2.valueAt(h, d);
			}
			double expected = Math.exp(dot1) - Math.exp(dot2) + lambdaInit;
			assertEquals(expected, actual.valueAt(h), 1e-4);
		}
	}

	/**
	 * The {@code [batch, seqLen, dim]} model input shape shared by every block under test.
	 *
	 * @return the input shape
	 */
	private TraversalPolicy inputShape() {
		return shape(BATCH, SEQ_LEN, DIM);
	}

	/**
	 * Wraps a per-head-uniform lambda value as the {@code [heads]} producer consumed by the
	 * differential attention block.
	 *
	 * @param value the uniform per-head lambda
	 * @return a producer of the constant {@code [heads]} lambda collection
	 */
	private Producer<PackedCollection> lambda(double value) {
		return cp(new PackedCollection(shape(HEADS)).fill(pos -> value));
	}

	/**
	 * Compiles a single attention block into a model and runs one forward pass.
	 *
	 * @param block the attention block under test
	 * @param input the model input
	 * @return the forward-pass output
	 */
	private PackedCollection run(Block block, PackedCollection input) {
		Model model = new Model(inputShape());
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * Evaluates a collection producer at the top of the call stack (test boundary), using the
	 * optimization pass that the framework currently requires for standalone producer evaluation.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}

	/**
	 * Shared synthetic weights for the differential block and its standard-attention references. The
	 * fused {@code [5*dim, dim]} projection is generated once; the {@code [3*dim, dim]} standard
	 * projections are derived from it by row-slicing so that {@code Q1/K1/K2/V/Q2} are computed
	 * identically on every path.
	 */
	private final class Weights {
		/** Fused {@code [5*dim, dim]} differential projection, sections {@code [Q1, K1, K2, V, Q2]}. */
		private final PackedCollection toQkv5 = new PackedCollection(shape(5 * DIM, DIM)).randnFill();
		/** Output projection {@code [dim, dim]}, shared by the differential block and both references. */
		private final PackedCollection toOut = new PackedCollection(shape(DIM, DIM)).randnFill();
		/** Query-normalization scale, shared by {@code Q1} and {@code Q2}. */
		private final PackedCollection qNormWeight = new PackedCollection(shape(DIM_HEAD)).randnFill();
		/** Query-normalization bias, shared by {@code Q1} and {@code Q2}. */
		private final PackedCollection qNormBias = new PackedCollection(shape(DIM_HEAD)).randnFill();
		/** Key-normalization scale, shared by {@code K1} and {@code K2}. */
		private final PackedCollection kNormWeight = new PackedCollection(shape(DIM_HEAD)).randnFill();
		/** Key-normalization bias, shared by {@code K1} and {@code K2}. */
		private final PackedCollection kNormBias = new PackedCollection(shape(DIM_HEAD)).randnFill();
		/** RoPE inverse frequencies, shape {@code [dimHead/4]}. */
		private final PackedCollection invFreq = new PackedCollection(shape(DIM_HEAD / 4)).fill(pos -> 0.01);

		/**
		 * Builds a fused {@code [3*dim, dim]} standard {@code to_qkv} weight whose {@code Q}, {@code K}
		 * and {@code V} rows are copied from the requested sections of the fused {@code [5*dim, dim]}
		 * differential weight, where section index {@code s} occupies rows {@code [s*dim, (s+1)*dim)}.
		 *
		 * @param qSection source section index for the query rows
		 * @param kSection source section index for the key rows
		 * @param vSection source section index for the value rows
		 * @return the derived standard {@code to_qkv} weight
		 */
		private PackedCollection standardQkv(int qSection, int kSection, int vSection) {
			PackedCollection qkv = new PackedCollection(shape(3 * DIM, DIM));
			qkv.fill(pos -> {
				int row = pos[0];
				int col = pos[1];
				int section = row / DIM;
				int within = row % DIM;
				int srcSection = section == 0 ? qSection : section == 1 ? kSection : vSection;
				return toQkv5.valueAt(srcSection * DIM + within, col);
			});
			return qkv;
		}
	}
}
