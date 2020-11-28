/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.relation.Producer;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

import static org.almostrealism.util.Ops.ops;

public class VectorMathTest implements CodeFeatures {
	@Test
	public void productFromVectors1() {
		VectorProducer a = vector(1.0, 2.0, 3.0);
		VectorProducer b = vector(4.0, 5.0, 6.0);
		ScalarProducer s = a.y().multiply(b.z());
		s.compact();
		DynamicAcceleratedOperation so = (DynamicAcceleratedOperation) s.get();
		System.out.println(so.getFunctionDefinition());
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productFromVectors2() {
		VectorProducer a = vector(1.0, 2.0, 3.0);
		VectorProducer b = vector(4.0, 5.0, 6.0);
		ScalarProducer s = a.y().multiply(b.z()).add(1);
		s.compact();
		DynamicAcceleratedOperation so = (DynamicAcceleratedOperation) s.get();
		System.out.println(so.getFunctionDefinition());
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productFromVectors3() {
		VectorProducer a = vector(1.0, 2.0, 3.0);
		VectorProducer b = vector(4.0, 5.0, 6.0);
		ScalarProducer s = a.y().multiply(b.z()).subtract(1);
		s.compact();
		DynamicAcceleratedOperation so = (DynamicAcceleratedOperation) s.get();
		System.out.println(so.getFunctionDefinition());
		Assert.assertEquals(1, so.getArgsCount());
	}

	@Test
	public void productDifference() {
		VectorProducer a = vector(1.0, 2.0, 3.0);
		VectorProducer b = vector(4.0, 5.0, 6.0);
		ScalarProducer s = ops().y(a).multiply(ops().z(b))
				.subtract(ops().z(a).multiply(ops().y(b)));
		s.compact();
		DynamicAcceleratedOperation so = (DynamicAcceleratedOperation) s.get();
		System.out.println(so.getFunctionDefinition());
		Assert.assertEquals(1, so.getArgsCount());
	}

	protected VectorProducer crossProduct(Producer<Vector> v) {
		return vector(0.0, 0.0, -1.0).crossProduct(v);
	}

	@Test
	public void crossProduct() {
		VectorProducer cp = crossProduct(vector(100.0, -100.0, 0.0)
						.subtract(vector(0.0, 100.0, 0.0)));

		Vector v = cp.get().evaluate();
		System.out.println(v);

		Assert.assertEquals(-200, v.getX(), Math.pow(10, -10));
		Assert.assertEquals(-100, v.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, v.getZ(), Math.pow(10, -10));
	}

	@Test
	public void crossProductCompact() {
		VectorProducer cp = crossProduct(vector(100.0, -200.0, 0.0));

		cp.compact();
		DynamicAcceleratedOperation cpo = (DynamicAcceleratedOperation) cp.get();
		System.out.println(cpo.getFunctionDefinition());
		Assert.assertEquals(1, cpo.getArgsCount());

		Vector v = cp.get().evaluate();
		System.out.println(v);

		Assert.assertEquals(-200, v.getX(), Math.pow(10, -10));
		Assert.assertEquals(-100, v.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, v.getZ(), Math.pow(10, -10));
	}
}
