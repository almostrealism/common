/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.junit.Assert;
import org.junit.Test;

public class PassThroughProducerCompactionTest implements HardwareFeatures, CodeFeatures {
	protected ScalarExpressionComputation sum() {
		return scalarAdd(
				PassThroughEvaluable.of(Scalar.class, 0),
				PassThroughEvaluable.of(Scalar.class, 1));
	}

	@Test
	public void applySum() {
		Evaluable<Scalar> ev = sum().get();
		Scalar s = ev.evaluate(new Scalar(1.0), new Scalar(2.0));
		Assert.assertEquals(3.0, s.getValue(), Math.pow(10, -10));
	}

	protected Evaluable<Scalar> product() {
		return scalarsMultiply(sum(), PassThroughEvaluable.of(Scalar.class, 2)).get();
	}

	@Test
	public void applyProduct() {
		Scalar s = product().evaluate(v(1.0).get().evaluate(),
									v(2.0).get().evaluate(),
									v(3.0).get().evaluate());
		System.out.println(s.getValue());
		System.out.println(s.getValue());
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}

	@Test
	public void applyProductCompact() {
		Evaluable<Scalar> p = product();

		Scalar s = p.evaluate(new Scalar(1.0), new Scalar(2.0), new Scalar(3.0));
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}
}
