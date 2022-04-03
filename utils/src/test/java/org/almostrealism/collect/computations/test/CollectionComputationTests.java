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

package org.almostrealism.collect.computations.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ScalarFromPackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class CollectionComputationTests implements TestFeatures {
	@Test
	public void scalarFromCollection() {
		Tensor<Scalar> values = new Tensor<>();
		values.insert(new Scalar(1.0), 0);
		values.insert(new Scalar(2.0), 1);
		values.insert(new Scalar(3.0), 2);

		PackedCollection collection = values.pack();

		ScalarFromPackedCollection scalar = new ScalarFromPackedCollection(collection.getShape(), p(collection), v(1));
		assertEquals(2.0, scalar.get().evaluate());
	}
}
