/*
 * Copyright 2024 Michael Murray
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
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.TranslationMatrix;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.CodeFeatures;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class TransformTest implements HardwareFeatures, TestFeatures {
	protected TransformMatrix matrix() {
		return new TranslationMatrix(vector(0.0, 10.0, 0.0)).evaluate();
	}

	protected Evaluable<Vector> transformAsLocation() {
		return transformAsLocation(matrix(), vector(1.0, 2.0, 3.0)).get();
	}

	@Test
	public void applyAsLocation() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		Vector v = transformAsLocation().evaluate();
		locationAssertions(v);
	}

	protected void locationAssertions(Vector v) {
		Assert.assertEquals(v.getX(), 1.0, Math.pow(10, -10));
		Assert.assertEquals(v.getY(), 12.0, Math.pow(10, -10));
		Assert.assertEquals(v.getZ(), 3.0, Math.pow(10, -10));
	}
}
