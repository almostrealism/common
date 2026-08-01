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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Correctness harness for the selection-contraction gather collapse.
 *
 * <p>A "selection contraction" is a reduction whose summand is a single-index mask
 * {@code Mask(col == source(row), value)} — the shape produced by the gradient
 * (delta) of subset, concat, and convolution-style {@code multiply().sum()}
 * operations. Summing the masked column reduces to a gather {@code value[source(row)]}.
 * The optimization being developed makes that collapse reachable through the
 * isolation boundary at {@link Process#optimize()} time; because an incorrect
 * collapse yields silently wrong gradients, this harness pins the <em>values</em> of
 * these contractions <strong>before</strong> any behavioral change, so the
 * optimization can be held to producing identical results.</p>
 *
 * <p>Every case here is deliberately small enough to evaluate via the current dense
 * reduction quickly (no timeout dependence). The large-scale versions of these same
 * shapes are {@code RepeatedDeltaComputationTests.convDelta*} (wide/deep) and
 * {@code RotaryEmbeddingGradientTests.subsetGradient*} (narrow/shallow); this harness
 * is their cheap, oracle-checked proxy.</p>
 *
 * <p>Two guards per shape:</p>
 * <ol>
 *   <li><strong>Oracle</strong>: the gradient is compared against its closed form,
 *       which is independent of how the reduction is compiled.</li>
 *   <li><strong>Differential</strong>: the directly-evaluated graph is compared
 *       against {@link Process#optimized(java.util.function.Supplier)} of the same
 *       graph. Today both take the dense path, so they agree; once the collapse is
 *       enabled, the optimized side takes the gather path and this assertion becomes
 *       the collapse-on-vs-off check that catches a wrong collapse.</li>
 * </ol>
 *
 * @author Michael Murray
 */
public class SelectionContractionCollapseTests extends TestSuiteBase {

	/**
	 * Subset-sum gradient — the canonical selection contraction.
	 *
	 * <p>{@code d(sum(subset(x, [k] at off)))/dx[j]} is {@code 1} when
	 * {@code off <= j < off + k} and {@code 0} otherwise.</p>
	 */
	@Test(timeout = 60000)
	public void subsetSumDeltaOracle() {
		int n = 10;
		int k = 4;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		PackedCollection out = cp(in).subset(shape(k), off).sum().delta(cp(in)).evaluate();

		for (int j = 0; j < n; j++) {
			assertEquals((j >= off && j < off + k) ? 1.0 : 0.0, out.toDouble(j));
		}
	}

	/**
	 * Subset-sum gradient — differential: directly-evaluated graph versus the
	 * optimized graph must produce identical output. Guards against a wrong gather
	 * collapse once the optimization is enabled.
	 */
	@Test(timeout = 60000)
	public void subsetSumDeltaCollapseMatchesDense() {
		int n = 12;
		int k = 5;
		int off = 4;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		PackedCollection dense = cp(in).subset(shape(k), off).sum().delta(cp(in)).evaluate();
		PackedCollection optimized = (PackedCollection)
				Process.optimized(cp(in).subset(shape(k), off).sum().delta(cp(in)))
						.get().evaluate();

		for (int j = 0; j < n; j++) {
			assertEquals(dense.toDouble(j), optimized.toDouble(j));
		}
	}

	/**
	 * Weighted subset-sum gradient — the selection carries a non-constant value.
	 *
	 * <p>{@code d(sum(w * subset(x, [k] at off)))/dx[j]} is {@code w[j - off]} when
	 * {@code off <= j < off + k} and {@code 0} otherwise. This is the case the
	 * value-based non-zero predicate cannot recognize (the masked value is a buffer
	 * read, not a constant) — the offset must come from the mask guard.</p>
	 */
	@Test(timeout = 60000)
	public void weightedSubsetSumDeltaOracle() {
		int n = 10;
		int k = 4;
		int off = 2;

		PackedCollection in = new PackedCollection(shape(n)).randFill();
		PackedCollection w = new PackedCollection(shape(k)).randFill();

		PackedCollection out = cp(in).subset(shape(k), off)
				.multiply(cp(w)).sum().delta(cp(in)).evaluate();

		for (int j = 0; j < n; j++) {
			double expected = (j >= off && j < off + k) ? w.toDouble(j - off) : 0.0;
			assertEquals(expected, out.toDouble(j));
		}
	}

	/**
	 * Weighted subset-sum gradient — differential against the optimized graph.
	 */
	@Test(timeout = 60000)
	public void weightedSubsetSumDeltaCollapseMatchesDense() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();
		PackedCollection w = new PackedCollection(shape(k)).randFill();

		PackedCollection dense = cp(in).subset(shape(k), off)
				.multiply(cp(w)).sum().delta(cp(in)).evaluate();
		PackedCollection optimized = (PackedCollection)
				Process.optimized(cp(in).subset(shape(k), off).multiply(cp(w)).sum().delta(cp(in)))
						.get().evaluate();

		for (int j = 0; j < n; j++) {
			assertEquals(dense.toDouble(j), optimized.toDouble(j));
		}
	}

	/**
	 * Concatenation-sum gradient — a selection over two source regions.
	 *
	 * <p>{@code sum(concat(a, b))} differentiated with respect to {@code a} is
	 * {@code 1} for every element of {@code a} (each contributes once to the sum) and
	 * the concat selection routes each output row to exactly one source element.</p>
	 */
	@Test(timeout = 60000)
	public void concatSumDeltaOracle() {
		int na = 4;
		int nb = 3;

		PackedCollection a = new PackedCollection(shape(na)).randFill();
		PackedCollection b = new PackedCollection(shape(nb)).randFill();

		PackedCollection out = concat(cp(a), cp(b)).sum().delta(cp(a)).evaluate();

		for (int j = 0; j < na; j++) {
			assertEquals(1.0, out.toDouble(j));
		}
	}

	/**
	 * Convolution-style {@code multiply().sum()} gradient — the wide/deep case.
	 *
	 * <p>This is the miniature of {@code convDelta}: a windowed multiply reduced by
	 * {@code sum}, then differentiated. {@code d(sum(x * f))/dx[j] = f[j]}. Kept tiny
	 * so the dense reduction evaluates immediately while still producing the
	 * selection-contraction shape that the large {@code convDelta} times out on.</p>
	 */
	@Test(timeout = 60000)
	public void multiplySumDeltaOracle() {
		int n = 6;

		PackedCollection in = new PackedCollection(shape(n)).randFill();
		PackedCollection f = new PackedCollection(shape(n)).randFill();

		PackedCollection out = cp(in).multiply(cp(f)).sum().delta(cp(in)).evaluate();

		for (int j = 0; j < n; j++) {
			assertEquals(f.toDouble(j), out.toDouble(j));
		}
	}

	/**
	 * Convolution-style {@code multiply().sum()} gradient — differential against the
	 * optimized graph, the direct proxy for the {@code convDelta} collapse.
	 */
	@Test(timeout = 60000)
	public void multiplySumDeltaCollapseMatchesDense() {
		int n = 8;

		PackedCollection in = new PackedCollection(shape(n)).randFill();
		PackedCollection f = new PackedCollection(shape(n)).randFill();

		PackedCollection dense = cp(in).multiply(cp(f)).sum().delta(cp(in)).evaluate();
		PackedCollection optimized = (PackedCollection)
				Process.optimized(cp(in).multiply(cp(f)).sum().delta(cp(in)))
						.get().evaluate();

		for (int j = 0; j < n; j++) {
			assertEquals(dense.toDouble(j), optimized.toDouble(j));
		}
	}
}
