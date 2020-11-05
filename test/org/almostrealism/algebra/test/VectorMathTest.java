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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.CodeFeatures;
import org.junit.Assert;
import org.junit.Test;

public class VectorMathTest implements CodeFeatures {
	@Test
	public void crossProduct() {
		VectorProducer cp = vector(0.0, 0.0, -1.0)
				.crossProduct(vector(100.0, -100.0, 0.0)
						.subtract(vector(0.0, 100.0, 0.0)));

		Vector v = cp.evaluate();
		System.out.println(v);

		Assert.assertEquals(-200, v.getX(), Math.pow(10, -10));
		Assert.assertEquals(-100, v.getY(), Math.pow(10, -10));
		Assert.assertEquals(0, v.getZ(), Math.pow(10, -10));
	}
}
