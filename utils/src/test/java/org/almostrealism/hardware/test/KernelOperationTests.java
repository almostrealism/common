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

package org.almostrealism.hardware.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class KernelOperationTests implements TestFeatures {
	@Test
	public void assignment() {
		PackedCollection<?> x = new PackedCollection<>(shape(10)).traverse();
		PackedCollection<?> a = tensor(shape(10)).pack().traverse();
		PackedCollection<?> b = tensor(shape(10)).pack().traverse();

		HardwareOperator.verboseLog(() -> {
			OperationList op = new OperationList();
			op.add(a(1, traverse(1, p(x)), add(traverse(1, p(a)), traverse(1, p(b)))));
			op.get().run();
		});

		for (int i = 0; i < x.getShape().length(0); i++) {
			assertEquals(a.toDouble(i) + b.toDouble(i), x.toDouble(i));
		}
	}
}
