/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.util.CodeFeatures;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.junit.Assert;
import org.junit.Test;

public class PassThroughProducerCompactionTest implements HardwareFeatures, CodeFeatures {
	protected DynamicProducerComputationAdapter<Scalar, Scalar> sum() {
		return new ScalarSum(
				PassThroughEvaluable.of(Scalar.class, 0),
				PassThroughEvaluable.of(Scalar.class, 1));
	}

	@Test
	public void applySum() {
		DefaultScalarEvaluable ev = (DefaultScalarEvaluable) sum().get();
		System.out.println(ev.getFunctionDefinition());
		Scalar s = ev.evaluate(new Scalar(1.0), new Scalar(2.0));
		Assert.assertEquals(3.0, s.getValue(), Math.pow(10, -10));
	}

	protected AcceleratedComputationEvaluable<Scalar> product() {
		return (AcceleratedComputationEvaluable)
				new ScalarProduct(sum(),
					PassThroughEvaluable.of(Scalar.class, 2)).get();
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
		AcceleratedComputationEvaluable<Scalar> p = product();
		// p.compact();
		System.out.println(p.getFunctionDefinition());

		Scalar s = p.evaluate(new Scalar(1.0), new Scalar(2.0), new Scalar(3.0));
		Assert.assertEquals(9.0, s.getValue(), Math.pow(10, -10));
	}
}
