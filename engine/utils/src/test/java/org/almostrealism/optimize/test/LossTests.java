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

package org.almostrealism.optimize.test;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.optimize.MeanSquaredError;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

public class LossTests extends TestSuiteBase {
	@Test(timeout = 10000)
	public void meanSquaredError() {
		TraversalPolicy outputShape = new TraversalPolicy(1, 1, 28, 28).traverseEach();
		PackedCollection input = new PackedCollection(shape(1, 1, 28, 28));
		PackedCollection target = new PackedCollection(shape(1, 1, 28, 28));

		MeanSquaredError mse = new MeanSquaredError(outputShape);
		PackedCollection grad = mse.gradient(cv(outputShape, 0), cv(outputShape, 1)).get()
				.evaluate(input.each(), target.each());
		Assert.assertEquals(outputShape, grad.getShape());
	}
}
