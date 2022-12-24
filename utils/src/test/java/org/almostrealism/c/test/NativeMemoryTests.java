package org.almostrealism.c.test;

import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.RAM;
import org.junit.Assert;
import org.junit.Test;

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

		ScalarBank bank = new ScalarBank(20);
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
