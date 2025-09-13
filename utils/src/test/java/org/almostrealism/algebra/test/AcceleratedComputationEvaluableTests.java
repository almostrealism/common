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

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class AcceleratedComputationEvaluableTests implements TestFeatures {
	@Test
	public void staticProducer() {
		Producer<Vector> res = vector(0.0, 1.0, 2.0);
		Vector v = res.get().evaluate();
		System.out.println(v);
		assert v.getX() == 0.0;
		assert v.getY() == 1.0;
		assert v.getZ() == 2.0;
	}

	@Test
	public void scalarFromVector() {
		CollectionProducer<PackedCollection<?>> res = y(vector(0.0, 1.0, 2.0));
		Evaluable<PackedCollection<?>> ev = res.get();
		try (PackedCollection<?> s = ev.evaluate()) {
			s.print();
			assertEquals(1.0, s);
		}
	}

	@Test
	public void scalarProduct() {
		CollectionProducer<PackedCollection<?>> x = c(3.0);
		Evaluable<PackedCollection<?>> res = multiply(x, c(0.5)).get();

		PackedCollection<?> s = res.evaluate();
		s.print();
		assertEquals(1.5, s.toDouble());
	}
}
