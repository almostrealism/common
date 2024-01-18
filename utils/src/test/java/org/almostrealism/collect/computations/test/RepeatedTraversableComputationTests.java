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

import io.almostrealism.code.Computation;
import io.almostrealism.code.OperationProfile;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.KernelProducerComputationAdapter;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.io.TimingMetric;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.stream.IntStream;

public class RepeatedTraversableComputationTests implements TestFeatures {
	@Test
	public void add() {
		HardwareOperator.profile = new OperationProfile("HardwareOperator");

		int len = 60000;

		PackedCollection<?> a = new PackedCollection<>(len).fill(Math::random);
		PackedCollection<?> b = new PackedCollection<>(len).fill(Math::random);

		PackedCollection<?> out = new PackedCollection<>(len);

		Evaluable<PackedCollection<?>> ev = add(v(shape(1), 0), v(shape(1), 1)).get();

		for (int i = 0; i < 100; i++) {
			ev.into(out.traverse(1)).evaluate(a.traverse(1), b.traverse(1));
		}

		IntStream.range(0, len).forEach(i -> {
			double expected = a.toDouble(i) + b.toDouble(i);
			double actual = out.toDouble(i);
			assertEquals(expected, actual);
		});

		out.clear();

		Evaluable<PackedCollection<?>> rev = ((KernelProducerComputationAdapter) add(v(shape(1), 0), v(shape(1), 1))).toRepeated().get();

		for (int i = 0; i < 100; i++) {
			rev.into(out).evaluate(a, b);
		}

		IntStream.range(0, len).forEach(i -> {
			double expected = a.toDouble(i) + b.toDouble(i);
			double actual = out.toDouble(i);
			assertEquals(expected, actual);
		});

		HardwareOperator.profile.print();
	}

	@Test
	public void relativeOperations() {
		TraversalPolicy parameterShape = shape(6, 1).traverse(1);
		TraversalPolicy inputShape = shape(100).traverse(1);

		PackedCollection<?> parameters = pack(0.0, 90.0, 1.1, 1.0, 1.0, 0.0).reshape(parameterShape);
		PackedCollection<?> input = new PackedCollection<>(inputShape);
		PackedCollection<?> dest = new PackedCollection<>(inputShape);

		input.fill(pos -> pos[0] / 10.0);

		PassThroughProducer p = new PassThroughProducer<>(parameterShape.traverse(0), 1);
		PassThroughProducer in = new PassThroughProducer<>(inputShape, 0);

		CollectionProducerComputation polyWaveLength = c(p, 1);
		CollectionProducerComputation polyExp = c(p, 2);
		CollectionProducerComputation initial = c(p, 3);
		CollectionProducerComputation scale = c(p, 4);
		CollectionProducerComputation offset = c(p, 5);

		double min = 0.0;
		double max = 1000.0;

		CollectionProducerComputationBase pos = relativeSubtract(in, offset);
		CollectionProducerComputationBase c = relativeBound(pos._greaterThan(c(0.0),
						relativeAdd(polyWaveLength.pow(c(-1.0))
								.relativeMultiply(pos).pow(polyExp)
								.relativeMultiply(scale), initial), initial),
				min, max);

		c.get().into(dest).evaluate(input, parameters.traverse(0));
		System.out.println(dest.toArrayString(0, 10));

		PackedCollection altDest = new PackedCollection<>(inputShape);
		c.toRepeated().get().into(altDest).evaluate(input, parameters.traverse(0));

		System.out.println(altDest.toArrayString(0, 10));

		IntStream.range(0, inputShape.getCount()).forEach(i -> {
			assertEquals(dest.toDouble(i), altDest.toDouble(i));
		});
	}
}
