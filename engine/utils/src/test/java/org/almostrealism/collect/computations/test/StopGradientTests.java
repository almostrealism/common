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

package org.almostrealism.collect.computations.test;

import org.almostrealism.calculus.ReduceAxis;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.GradientFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionStopGradientComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;

/**
 * Verifies {@link GradientFeatures#stopGradient(io.almostrealism.relation.Producer)}: the
 * stopped producer evaluates to exactly the values of its input and has its shape, its delta with
 * respect to that input is zero, and a computation that consumes it is differentiated as though
 * the stopped value were a constant, in every {@link ReduceAxis mode}.
 *
 * @author Michael Murray
 */
public class StopGradientTests extends TestSuiteBase {
	/** A stopped producer evaluates to the values of its input, unchanged. */
	@Test(timeout = 60000)
	public void valuesPassThrough() {
		PackedCollection x = new PackedCollection(shape(5)).randnFill();

		CollectionProducer stopped = stopGradient(cp(x).multiply(2.0));
		Assert.assertTrue(stopped instanceof CollectionStopGradientComputation);
		assertEquals(cp(x).multiply(2.0).evaluate(), stopped.evaluate());
	}

	/**
	 * The Jacobian of a stopped producer with respect to the variable its input depends on is
	 * zero, although the input itself depends on that variable.
	 */
	@Test(timeout = 60000)
	public void jacobianIsZero() {
		PackedCollection x = new PackedCollection(shape(4)).randnFill();
		CollectionProducer in = cp(x);

		Assert.assertTrue(stopGradient(in.pow(2.0)).cannotMatch(in));
		assertEquals(new PackedCollection(shape(4, 4)), stopGradient(in.pow(2.0)).delta(in).evaluate());
	}

	/**
	 * {@code x - stopGradient(3x)} evaluates to {@code -2x}, but is differentiated as
	 * {@code x - c}: its Jacobian is the identity, its output-reduced delta is the weight itself,
	 * and its input-reduced delta is the tangent itself, rather than {@code -2} times each.
	 */
	@Test(timeout = 60000)
	public void consumerTreatsStoppedValueAsConstant() {
		int n = 4;
		PackedCollection x = new PackedCollection(shape(n)).randnFill();
		PackedCollection weight = new PackedCollection(shape(n)).randnFill();
		CollectionProducer in = cp(x);
		CollectionProducer f = in.subtract(stopGradient(in.multiply(3.0)));

		assertEquals(cp(x).multiply(-2.0).evaluate(), f.evaluate());
		assertEquals(identity(shape(n, n)).evaluate(), f.delta(in).evaluate());
		assertEquals(weight, f.delta(in, ReduceAxis.OUT, cp(weight)).evaluate());
		assertEquals(weight, f.delta(in, ReduceAxis.IN, cp(weight)).evaluate());
	}

	/** A stopped producer is still differentiated with respect to itself, as the identity. */
	@Test(timeout = 60000)
	public void deltaWithRespectToItselfIsIdentity() {
		int n = 3;
		PackedCollection x = new PackedCollection(shape(n)).randnFill();
		CollectionProducer stopped = stopGradient(cp(x).exp());

		assertEquals(identity(shape(n, n)).evaluate(), stopped.delta(stopped).evaluate());
	}

	/**
	 * A stopped producer has the shape of its input, traversal axis included, and takes the
	 * shape of the regenerated input when it is regenerated over its children, as the
	 * optimizer regenerates it.
	 */
	@Test(timeout = 60000)
	public void shapeIsThatOfTheInput() {
		PackedCollection x = new PackedCollection(shape(2, 3)).randnFill();
		CollectionProducer in = cp(x).traverse(1);

		CollectionStopGradientComputation stopped = new CollectionStopGradientComputation(in);
		Assert.assertEquals(in.getShape(), stopped.getShape());

		CollectionProducerParallelProcess regenerated = stopped.generate(new ArrayList<>(stopped.getChildren()));
		Assert.assertEquals(stopped.getShape(), regenerated.getShape());
		assertEquals(x, regenerated.evaluate());
	}
}
