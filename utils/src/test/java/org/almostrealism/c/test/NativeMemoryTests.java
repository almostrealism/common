package org.almostrealism.c.test;

import org.almostrealism.c.NativeMemoryProvider;
import org.almostrealism.hardware.RAM;
import org.junit.Assert;
import org.junit.Test;

public class NativeMemoryTests {
	@Test
	public void readAndWrite() {
		NativeMemoryProvider provider = new NativeMemoryProvider();
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
}
