/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.algebra.Tensor;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CollectionPolymorphismTests implements TestFeatures {
	// @Test
	public void traversalStream() {
		TraversalPolicy shape = new TraversalPolicy(2, 3, 3);
		shape.stream().map(Arrays::toString).forEach(System.out::println);
	}

	@Test
	public void tensorToScalarBank() {
		Tensor<Scalar> t = new Tensor<>();
		t.insert(new Scalar(1), 0, 0);
		t.insert(new Scalar(2), 0, 1);
		t.insert(new Scalar(3), 0, 2);
		t.insert(new Scalar(4), 1, 0);
		t.insert(new Scalar(5), 1, 1);
		t.insert(new Scalar(6), 1, 2);
		List<PackedCollection<Scalar>> banks = t.pack().traverse(1)
				.extract(Scalar::scalarBank)
				.collect(Collectors.toList());
		assertEquals(3, banks.get(0).get(2));
		assertEquals(5, banks.get(1).get(1));
	}
}
