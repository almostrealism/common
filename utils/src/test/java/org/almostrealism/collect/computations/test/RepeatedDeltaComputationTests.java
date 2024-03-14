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

import io.almostrealism.relation.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.metal.MetalProgram;
import org.almostrealism.util.TestFeatures;
import org.almostrealism.util.TestSettings;
import org.junit.Test;

public class RepeatedDeltaComputationTests implements TestFeatures {
	static {
		NativeCompiler.enableInstructionSetMonitoring = !TestSettings.skipLongTests;
		MetalProgram.enableProgramMonitoring = !TestSettings.skipLongTests;
	}

	@Test
	public void sum() {
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2).traverse(1);
		PackedCollection<?> out = cp(in).sum().delta(cp(in)).evaluate();
		out.print();

		assertEquals(1.0, out.valueAt(0, 0, 0, 0));
		assertEquals(1.0, out.valueAt(0, 0, 0, 1));
		assertEquals(0.0, out.valueAt(0, 0, 1, 0));
		assertEquals(0.0, out.valueAt(0, 0, 1, 1));
		assertEquals(0.0, out.valueAt(1, 0, 0, 0));
		assertEquals(0.0, out.valueAt(1, 0, 0, 1));
		assertEquals(1.0, out.valueAt(1, 0, 1, 0));
		assertEquals(1.0, out.valueAt(1, 0, 1, 1));
	}

	@Test
	public void productSum() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2).traverse(1);
		PackedCollection<?> in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2).traverse(1);
		PackedCollection<?> out = cp(in).multiply(cp(multiplier)).sum().delta(cp(in)).evaluate();
		out.print();

		assertEquals(4.0, out.valueAt(0, 0, 0, 0));
		assertEquals(3.0, out.valueAt(0, 0, 0, 1));
		assertEquals(0.0, out.valueAt(0, 0, 1, 0));
		assertEquals(0.0, out.valueAt(0, 0, 1, 1));
		assertEquals(0.0, out.valueAt(1, 0, 0, 0));
		assertEquals(0.0, out.valueAt(1, 0, 0, 1));
		assertEquals(2.0, out.valueAt(1, 0, 1, 0));
		assertEquals(1.0, out.valueAt(1, 0, 1, 1));
	}

	@Test
	public void productSumEnumerate() {
		PackedCollection<?> multiplier = pack(4.0, 3.0, 2.0, 1.0).reshape(2, 2).traverse(1);
		PackedCollection<?> in = pack(1.0, 1.0, 1.0, 1.0).reshape(2, 2).traverse(1);

		CollectionProducer<PackedCollection<?>> c = cp(in).multiply(cp(multiplier)).sum().delta(cp(in)).reshape(2, 4).enumerate(1, 1);
		// PackedCollection<?> out = c.evaluate();
		PackedCollection<?> out = Process.optimized(c).get().evaluate();
		out.traverse(1).print();

		assertEquals(4.0, out.valueAt(0, 0));
		assertEquals(3.0, out.valueAt(1, 0));
		assertEquals(0.0, out.valueAt(2, 0));
		assertEquals(0.0, out.valueAt(3, 0));
		assertEquals(0.0, out.valueAt(0, 1));
		assertEquals(0.0, out.valueAt(1, 1));
		assertEquals(2.0, out.valueAt(2, 1));
		assertEquals(1.0, out.valueAt(3, 1));
	}
}
