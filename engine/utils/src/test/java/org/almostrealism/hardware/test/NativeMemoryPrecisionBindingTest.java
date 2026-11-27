/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.test;

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeArrayCopy;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.nio.NativeRead;
import org.almostrealism.nio.NativeWrite;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that calloc-mode {@link NativeMemoryProvider}s at two different precisions cannot share one
 * precision's native copy code in a single JVM.
 *
 * <p>{@link NativeRead}/{@link NativeWrite} generate different C per precision but bind under the
 * per-class JNI symbol {@code Java_<class>_apply}, which carries no precision, and a class is loaded
 * once per JVM. Before the fail-fast guard, a second calloc provider built at the other precision
 * reused the first precision's already-loaded native code and copied the wrong element width,
 * overrunning its buffers and crashing the JVM with a SIGSEGV. The guard in
 * {@link org.almostrealism.c.BaseNative#loadNative(Class, String)} converts that cross-precision
 * rebind into a catchable {@link HardwareException}.</p>
 *
 * <p>Because the guard is JVM-wide and records whichever precision bound first, this test drives the
 * two precisions relative to the running hardware's precision — binding it first with a correct
 * round-trip, then proving the <em>other</em> precision fails fast. Every other test in the JVM uses
 * the hardware precision, so binding it first leaves the suite's bindings intact regardless of
 * Surefire fork reuse or execution order.</p>
 */
public class NativeMemoryPrecisionBindingTest extends TestSuiteBase {
	/** A length spanning two full FP32 conversion chunks and part of a third. */
	private static final int LENGTH = 2 * NativeArrayCopy.CONVERSION_CHUNK + 37;

	/** Maximum bytes each provider may allocate. */
	private static final long MEMORY_MAX = 1024L * 1024;

	/**
	 * A calloc provider at the hardware precision round-trips a whole block unchanged, and a second
	 * calloc provider at the other precision then fails fast with a {@link HardwareException} naming the
	 * operation and both precisions, rather than rebinding to the first precision's native code and
	 * crashing the JVM.
	 */
	@Test(timeout = 120000)
	public void crossPrecisionCallocFailsFast() {
		Precision hardware = Hardware.getLocalHardware().getPrecision();
		Precision other = hardware == Precision.FP64 ? Precision.FP32 : Precision.FP64;

		NativeMemoryProvider bound = calloc(hardware);
		RAM mem = bound.allocate(LENGTH);

		try {
			double[] values = sequence(LENGTH);
			bound.setMem(mem, 0, values, 0, LENGTH);
			Assert.assertArrayEquals(values, bound.toArray(mem, 0, LENGTH), 0.0);
		} finally {
			bound.deallocate(LENGTH, mem);
		}

		NativeMemoryProvider mismatched = calloc(other);
		RAM otherMem = mismatched.allocate(LENGTH);

		try {
			mismatched.setMem(otherMem, 0, sequence(LENGTH), 0, LENGTH);
			Assert.fail("A calloc provider at " + other + " bound after " + hardware +
					" was not rejected");
		} catch (HardwareException expected) {
			String message = expected.getMessage();
			Assert.assertTrue("Message should name NativeWrite: " + message,
					message.contains(NativeWrite.class.getName()));
			Assert.assertTrue("Message should name both precisions: " + message,
					message.contains(hardware.toString()) && message.contains(other.toString()));
		} finally {
			mismatched.deallocate(LENGTH, otherMem);
		}
	}

	/**
	 * Returns a provider that allocates with JNI calloc rather than direct buffers, at the given precision.
	 *
	 * @param precision the numeric precision for the provider's memory
	 * @return the provider
	 */
	private static NativeMemoryProvider calloc(Precision precision) {
		return new NativeMemoryProvider(precision, MEMORY_MAX, false, null, false);
	}

	/**
	 * Returns {@code i + 0.5} for each index; every value is exactly representable in FP32.
	 *
	 * @param length the number of values
	 * @return the values
	 */
	private static double[] sequence(int length) {
		double[] values = new double[length];
		for (int i = 0; i < length; i++) values[i] = i + 0.5;
		return values;
	}
}
