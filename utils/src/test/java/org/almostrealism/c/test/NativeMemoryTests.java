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

package org.almostrealism.c.test;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.RAM;
import org.junit.Assert;

public class NativeMemoryTests {
	// TODO  @Test
	public void readAndWrite() {
		NativeMemoryProvider provider = new NativeMemoryProvider(1024);
		RAM ram = provider.allocate(1);

		System.out.println("memory location = " + ram.getNativePointer());

		double input[] = new double[1];
		input[0] = 31.0;
		provider.setMem(ram, 0, input, 0, 1);

		double value[] = new double[1];
		provider.getMem(ram, 0, value, 0, 1);

		System.out.println(value[0]);
		Assert.assertEquals(31, value[0], Math.pow(10, -10));
	}

	// TODO  @Test
	public void scalarBank() {
		assert Hardware.getLocalHardware().getMemoryProvider(0) instanceof NativeMemoryProvider;

		PackedCollection<Scalar> bank = Scalar.scalarBank(20);
		bank.set(4, 25);
		bank.set(5, 30);
		bank.set(19, 75);

		assert bank.get(0).getValue() == 0;
		assert bank.get(1).getValue() == 0;
		assert bank.get(4).getValue() == 25;
		assert bank.get(5).getValue() == 30;
		assert bank.get(18).getValue() == 0;
		assert bank.get(19).getValue() == 75;
	}
}
