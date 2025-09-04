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

package org.almostrealism.hardware.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class StreamingEvaluableTests implements TestFeatures {
	@Test
	public void product() {
		int count = 1;

		PackedCollection<?> a = new PackedCollection<>(shape(count)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(count)).randFill();

		CollectionProducer<PackedCollection<?>> pa = func(shape(count), args -> {
			log("Providing value 'a' on thread " + Thread.currentThread().getName());
			return a;
		});

		CollectionProducer<PackedCollection<?>> pb = func(shape(count), args -> {
			log("Providing value 'b' on thread " + Thread.currentThread().getName());
			return b;
		});

		try (PackedCollection<?> result = multiply(pa, pb).get().evaluate()) {
			double aTotal = a.doubleStream().sum();
			double bTotal = b.doubleStream().sum();
			assertEquals(aTotal * bTotal, result.toDouble());
		}
	}

	@Test
	public void sumProduct() {
		int count = 100;

		PackedCollection<?> a = new PackedCollection<>(shape(count)).randFill();
		PackedCollection<?> b = new PackedCollection<>(shape(count)).randFill();

		try (PackedCollection<?> result = sum(cp(a)).multiply(sum(cp(b))).get().evaluate()) {
			double aTotal = a.doubleStream().sum();
			double bTotal = b.doubleStream().sum();
			assertEquals(aTotal * bTotal, result.toDouble());
		}
	}
}
