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

package org.almostrealism.collect.computations.test;

import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationAdapter;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class RepeatedTraversableComputationTests implements TestFeatures {
	@Test
	public void add() {
		OperationProfile profile = new OperationProfile("HardwareOperator");
		HardwareOperator.timingListener = profile.getTimingListener();

		int len = 60000;

		PackedCollection a = new PackedCollection(len).fill(Math::random);
		PackedCollection b = new PackedCollection(len).fill(Math::random);

		PackedCollection out = new PackedCollection(len);

		Evaluable<PackedCollection> ev = add(v(shape(-1), 0), v(shape(-1), 1)).get();

		verboseLog(() -> {
			for (int i = 0; i < 100; i++) {
				ev.into(out.traverse(1)).evaluate(a.traverse(1), b.traverse(1));
			}
		});

		IntStream.range(0, len).forEach(i -> {
			double expected = a.toDouble(i) + b.toDouble(i);
			double actual = out.toDouble(i);
			assertEquals(expected, actual);
		});

		out.clear();

		Evaluable<PackedCollection> rev = ((CollectionProducerComputationAdapter) add(v(shape(-1), 0), v(shape(-1), 1))).toRepeated().get();

		verboseLog(() -> {
			for (int i = 0; i < 100; i++) {
				rev.into(out).evaluate(a, b);
			}
		});

		IntStream.range(0, len).forEach(i -> {
			double expected = a.toDouble(i) + b.toDouble(i);
			double actual = out.toDouble(i);
			assertEquals(expected, actual);
		});

		profile.print();
	}
}
