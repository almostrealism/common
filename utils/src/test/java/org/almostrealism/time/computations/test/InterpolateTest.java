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

package org.almostrealism.time.computations.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Sum;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.time.computations.Interpolate;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.util.Arrays;

public class InterpolateTest implements TestFeatures {
	@Test
	public void interpolateTwoSeries() {
		PackedCollection<?> series = new PackedCollection(2, 10);
		series.setMem(0, 7, 5, 12, 13, 16, 14, 9, 12, 3, 12);
		series.setMem(10, 12, 3, 12, 10, 14, 16, 13, 12, 5, 7);
		System.out.println(series.traverse(1).getCount() + " series");

		PackedCollection<?> cursors = new PackedCollection(2, 1);
		cursors.setMem(0, 5.5, 3.5);
		System.out.println(cursors.traverse(1).getCount() + " cursors");

		PackedCollection<?> rate = new PackedCollection(2, 1);
		rate.setMem(0, 1.0, 1.0);

		Interpolate interpolate = new Interpolate(
				new PassThroughProducer<>(10, 0, 0),
				new PassThroughProducer<>(1, 1, 0),
				new PassThroughProducer<>(1, 2, 0),
				v -> new Sum(v, new Expression<>(Double.class, "1.0")));
		PackedCollection dest = interpolate.get().evaluate(series.traverse(1), cursors.traverse(1), rate.traverse(1));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(15, dest.toArray(0, 1)[0]);
		assertEquals(11, dest.toArray(1, 1)[0]);
	}

	@Test
	public void interpolateKernel() {
		PackedCollection<?> series = new PackedCollection(10);
		series.setMem(0, 7, 5, 12, 13, 16, 14, 9, 12, 3, 12);
		System.out.println(series.traverse(0).getCount() + " series");

		PackedCollection<?> cursors = new PackedCollection(2, 1);
		cursors.setMem(0, 5.5, 6.5);
		System.out.println(cursors.traverse(1).getCount() + " cursors");

		PackedCollection<?> rate = new PackedCollection(2, 1);
		rate.setMem(0, 1.0, 1.0);

		Interpolate interpolate = new Interpolate(
				new PassThroughProducer<>(10, 0, -1),
				new PassThroughProducer<>(1, 1),
				new PassThroughProducer<>(1, 2));
		PackedCollection<?> dest = new PackedCollection(2, 1);
		interpolate.get().kernelEvaluate(dest.traverse(1), series.traverse(0), cursors.traverse(1), rate.traverse(1));

		System.out.println(Arrays.toString(dest.toArray(0, 2)));
		assertEquals(11.5, dest.toArray(0, 1)[0]);
		assertEquals(10.5, dest.toArray(1, 1)[0]);
	}

	@Test
	public void interpolate() {
		PackedCollection series = new PackedCollection(10);
		series.setMem(0, 7, 5, 12, 13, 16, 14, 9, 12, 3, 12);

		PackedCollection cursor = new PackedCollection(1);
		cursor.setMem(0, 5.5);

		PackedCollection rate = new PackedCollection(1);
		rate.setMem(0, 1.0);

		Interpolate interpolate = new Interpolate(
				new PassThroughProducer<>(10, 0, 0),
				new PassThroughProducer<>(1, 1, -1),
				new PassThroughProducer<>(1, 2, 0),
				v -> new Sum(v, new Expression<>(Double.class, "1.0")));
		PackedCollection dest = interpolate.get().evaluate(series, cursor, rate);

		System.out.println(Arrays.toString(dest.toArray(0, 1)));
		assertEquals(15, dest.toArray(0, 1)[0]);
	}
}
