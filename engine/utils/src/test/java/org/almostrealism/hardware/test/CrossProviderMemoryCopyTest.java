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

import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLMemory;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for cross-provider memory copies between native (JNI) and OpenCL memory.
 *
 * <p>These copies run on every kernel dispatch whose arguments reside on a different
 * provider than the kernel (via memory replacement), so both directions must be
 * correct regardless of whether the bulk host-buffer transfer path or the
 * array-mediated fallback is taken. The tests exercise offsets on both sides and
 * confirm that regions outside the copied range are left untouched.</p>
 *
 * <p>All tests skip cleanly when the OpenCL or JNI backend is unavailable.</p>
 *
 * @see org.almostrealism.hardware.cl.CLMemoryProvider
 * @see org.almostrealism.nio.NativeMemoryProvider
 */
public class CrossProviderMemoryCopyTest extends TestSuiteBase {

	/**
	 * Copies a range of native memory into the middle of an OpenCL buffer and
	 * verifies the copied values and the sentinel values around them.
	 */
	@Test(timeout = 120000)
	public void nativeToCl() {
		NativeMemoryProvider nativeProvider = nativeProvider();
		CLMemoryProvider clProvider = clProvider();

		if (nativeProvider == null || clProvider == null) {
			log("skipping, JNI or CL memory provider unavailable");
			return;
		}

		int n = 96;
		RAM source = nativeProvider.allocate(n);
		CLMemory dest = clProvider.allocate(n);

		try {
			double[] values = new double[n];
			for (int i = 0; i < n; i++) values[i] = i + 1.0;
			nativeProvider.setMem(source, 0, values, 0, n);

			double[] sentinel = new double[n];
			for (int i = 0; i < n; i++) sentinel[i] = -1.0;
			clProvider.setMem(dest, 0, sentinel, 0, n);

			// Copy 32 elements from source position 8 to destination position 16
			clProvider.setMem(dest, 16, source, 8, 32);

			double[] result = clProvider.toArray(dest, 0, n);

			for (int i = 0; i < n; i++) {
				double expected = (i >= 16 && i < 48) ? (i - 16) + 9.0 : -1.0;
				assertEquals("index " + i, expected, result[i]);
			}
		} finally {
			clProvider.deallocate(n, dest);
			nativeProvider.deallocate(n, source);
		}
	}

	/**
	 * Copies a range of an OpenCL buffer into the middle of native memory and
	 * verifies the copied values and the sentinel values around them.
	 */
	@Test(timeout = 120000)
	public void clToNative() {
		NativeMemoryProvider nativeProvider = nativeProvider();
		CLMemoryProvider clProvider = clProvider();

		if (nativeProvider == null || clProvider == null) {
			log("skipping, JNI or CL memory provider unavailable");
			return;
		}

		int n = 96;
		CLMemory source = clProvider.allocate(n);
		RAM dest = nativeProvider.allocate(n);

		try {
			double[] values = new double[n];
			for (int i = 0; i < n; i++) values[i] = i + 1.0;
			clProvider.setMem(source, 0, values, 0, n);

			double[] sentinel = new double[n];
			for (int i = 0; i < n; i++) sentinel[i] = -1.0;
			nativeProvider.setMem(dest, 0, sentinel, 0, n);

			// Copy 32 elements from source position 8 to destination position 16
			nativeProvider.setMem(dest, 16, source, 8, 32);

			double[] result = nativeProvider.toArray(dest, 0, n);

			for (int i = 0; i < n; i++) {
				double expected = (i >= 16 && i < 48) ? (i - 16) + 9.0 : -1.0;
				assertEquals("index " + i, expected, result[i]);
			}
		} finally {
			nativeProvider.deallocate(n, dest);
			clProvider.deallocate(n, source);
		}
	}

	/**
	 * Round-trips data native → CL → native and verifies it arrives unchanged,
	 * mirroring the prepare and postprocess copies that surround a dispatch whose
	 * arguments were replaced onto the OpenCL provider.
	 */
	@Test(timeout = 120000)
	public void roundTrip() {
		NativeMemoryProvider nativeProvider = nativeProvider();
		CLMemoryProvider clProvider = clProvider();

		if (nativeProvider == null || clProvider == null) {
			log("skipping, JNI or CL memory provider unavailable");
			return;
		}

		int n = 1024;
		RAM original = nativeProvider.allocate(n);
		CLMemory device = clProvider.allocate(n);
		RAM returned = nativeProvider.allocate(n);

		try {
			double[] values = new double[n];
			for (int i = 0; i < n; i++) values[i] = 0.5 * i;
			nativeProvider.setMem(original, 0, values, 0, n);

			clProvider.setMem(device, 0, original, 0, n);
			nativeProvider.setMem(returned, 0, device, 0, n);

			double[] result = nativeProvider.toArray(returned, 0, n);
			for (int i = 0; i < n; i++) {
				assertEquals("index " + i, 0.5 * i, result[i]);
			}
		} finally {
			nativeProvider.deallocate(n, returned);
			clProvider.deallocate(n, device);
			nativeProvider.deallocate(n, original);
		}
	}

	/**
	 * Returns the OpenCL memory provider from the active hardware configuration,
	 * or null when no OpenCL backend is available.
	 */
	private static CLMemoryProvider clProvider() {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, ComputeRequirement.CL).stream()
					.flatMap(c -> c.getDataContext().getMemoryProviders().stream())
					.filter(CLMemoryProvider.class::isInstance)
					.map(CLMemoryProvider.class::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Returns the native (JNI) memory provider from the active hardware configuration,
	 * or null when no JNI backend is available.
	 */
	private static NativeMemoryProvider nativeProvider() {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, ComputeRequirement.JNI).stream()
					.flatMap(c -> c.getDataContext().getMemoryProviders().stream())
					.filter(NativeMemoryProvider.class::isInstance)
					.map(NativeMemoryProvider.class::cast)
					.findFirst().orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
