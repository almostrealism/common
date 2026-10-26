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

import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Pins the contract example of the automatic-differentiation internals page
 * ({@code docs/internals/automatic-differentiation.md}, section "The contract"): for the scalar
 * output {@code y = Σ xᵢ²}, the full Jacobian {@link ReduceAxis#NONE} is {@code 2·xⱼ} in the shape
 * {@code (1, n)}, the {@link ReduceAxis#OUT} delta is the vector-Jacobian product in the target's
 * shape, and the {@link ReduceAxis#IN} delta is the Jacobian-vector product in the output's shape.
 * These complement {@link ReducedDeltaTest}, which pins the per-computation rules (product, sum,
 * repeat); here the subject is the end-to-end {@code pow(2).sum()} example the page shows, so the
 * snippet in the documentation cannot drift from the behaviour.
 *
 * @author Michael Murray
 */
public class AutomaticDifferentiationExamplesTest extends TestSuiteBase {

	/**
	 * The reduced-delta contract on worked examples, each result checked against its definition
	 * computed on the host: the full Jacobian ({@link ReduceAxis#NONE}) and vector-Jacobian product
	 * ({@link ReduceAxis#OUT}) of {@code y = Σ xᵢ²}, and the Jacobian-vector product
	 * ({@link ReduceAxis#IN}) of the element-wise square {@code s = x²}, whose shape is the
	 * computation's rather than a reduced scalar.
	 */
	@Test(timeout = 2 * 60000)
	public void squaredSumContract() {
		int n = 6;
		PackedCollection x = new PackedCollection(shape(n)).randnFill();
		PackedCollection g = new PackedCollection(shape(1)).randnFill();
		PackedCollection t = new PackedCollection(shape(n)).randnFill();

		Producer<PackedCollection> px = p(x);
		CollectionProducer y = c(px).pow(2.0).sum();

		double tolerance = 1e-4;

		// NONE: the full Jacobian J has shape (1, n) and equals 2·xⱼ in column j.
		PackedCollection jacobian = y.delta(px).evaluate();
		Assert.assertEquals(2, jacobian.getShape().getDimensions());
		Assert.assertEquals(1, jacobian.getShape().length(0));
		Assert.assertEquals(n, jacobian.getShape().length(1));
		for (int j = 0; j < n; j++) {
			Assert.assertEquals(2.0 * x.toDouble(j), jacobian.toDouble(j), tolerance);
		}

		// OUT: the vector-Jacobian product gᵀJ has the target's shape (n); here g is a single output.
		PackedCollection out = y.delta(px, ReduceAxis.OUT, cp(g)).evaluate();
		Assert.assertEquals(1, out.getShape().getDimensions());
		Assert.assertEquals(n, out.getShape().length(0));
		for (int j = 0; j < n; j++) {
			Assert.assertEquals(g.toDouble(0) * 2.0 * x.toDouble(j), out.toDouble(j), tolerance);
		}

		// IN: the Jacobian-vector product J t has the computation's shape. For the element-wise
		// square s = x² the Jacobian is diagonal (2·xⱼ), so J t is 2·xⱼ·tⱼ in the input's shape (n).
		CollectionProducer square = c(px).pow(2.0);
		PackedCollection in = square.delta(px, ReduceAxis.IN, cp(t)).evaluate();
		Assert.assertEquals(1, in.getShape().getDimensions());
		Assert.assertEquals(n, in.getShape().length(0));
		for (int j = 0; j < n; j++) {
			Assert.assertEquals(2.0 * x.toDouble(j) * t.toDouble(j), in.toDouble(j), tolerance);
		}
	}
}
