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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ComputeContext;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.CodeFeatures;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.junit.Test;

public class KernelTest implements CodeFeatures {
	@Test
	public void copyRay() {
		ComputeContext c = Hardware.getLocalHardware().getComputeContext();
		if (!(c instanceof CLComputeContext)) return;

		TestKernel k = new TestKernel((CLComputeContext) c, Ray.blank(), ray(1, 2, 3, 4, 5, 6));
		Ray r = k.evaluate();
		System.out.println(r);
		assert r.equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 6)));
	}

	@Test
	public void copyRayKernel() {
		ComputeContext c = Hardware.getLocalHardware().getComputeContext();
		if (!(c instanceof CLComputeContext)) return;

		int count = 2;

		TestKernel k = new TestKernel((CLComputeContext) c, Ray.blank(), Input.value(Ray.shape(), 0));

		PackedCollection<Ray> output = Ray.bank(count, Ray.blank());
		PackedCollection<Ray> input = Ray.bank(count, ray(1, 2, 3,4, 5, 7));

		k.into(output).evaluate(input);

		for (int i = 0; i < count; i++) {
			System.out.println(output.get(i));
			assert output.get(i).equals(new Ray(new Vector(1, 2, 3), new Vector(4, 5, 7)));
		}
	}
}
