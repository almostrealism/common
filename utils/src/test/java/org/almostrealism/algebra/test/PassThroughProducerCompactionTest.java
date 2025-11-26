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

package org.almostrealism.algebra.test;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.Input;
import org.junit.Assert;
import org.junit.Test;

public class PassThroughProducerCompactionTest implements CodeFeatures {
	protected CollectionProducer<PackedCollection<?>> sum() {
		return add(
				Input.value(shape(1), 0),
				Input.value(shape(1), 1));
	}

	@Test
	public void applySum() {
		Evaluable<PackedCollection<?>> ev = sum().get();
		PackedCollection<?> s = ev.evaluate(pack(1.0), pack(2.0));
		Assert.assertEquals(3.0, s.toDouble(0), Math.pow(10, -10));
	}

	protected Evaluable<PackedCollection<?>> product() {
		return multiply(sum(), Input.value(shape(1), 2)).get();
	}

	@Test
	public void applyProduct() {
		PackedCollection<?> s = product().evaluate(
									c(1.0).get().evaluate(),
									c(2.0).get().evaluate(),
									c(3.0).get().evaluate());
		System.out.println(s.toDouble(0));
		System.out.println(s.toDouble(0));
		Assert.assertEquals(9.0, s.toDouble(0), Math.pow(10, -10));
	}

	@Test
	public void applyProductCompact() {
		Evaluable<PackedCollection<?>> p = product();

		PackedCollection<?> s = p.evaluate(pack(1.0), pack(2.0), pack(3.0));
		Assert.assertEquals(9.0, s.toDouble(0), Math.pow(10, -10));
	}
}
