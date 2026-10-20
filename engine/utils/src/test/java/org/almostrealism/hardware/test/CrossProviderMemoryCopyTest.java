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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.cl.CLMemoryProvider;
import org.almostrealism.hardware.cuda.CudaMemoryProvider;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.nio.NativeMemoryProvider;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for cross-provider memory copies between native (JNI) memory and the memory of an
 * accelerator backend (OpenCL or CUDA).
 *
 * <p>These copies run on every kernel dispatch whose arguments reside on a different
 * provider than the kernel (via memory replacement), so both directions must be
 * correct regardless of whether the bulk host-buffer transfer path or the
 * array-mediated fallback is taken. The tests exercise offsets on both sides and
 * confirm that regions outside the copied range are left untouched.</p>
 *
 * <p>Each test skips cleanly when the JNI backend or the accelerator backend it copies to
 * is unavailable.</p>
 *
 * @see org.almostrealism.hardware.cl.CLMemoryProvider
 * @see org.almostrealism.hardware.cuda.CudaMemoryProvider
 * @see org.almostrealism.nio.NativeMemoryProvider
 */
public class CrossProviderMemoryCopyTest extends TestSuiteBase {

	/**
	 * Copies a range of native memory into the middle of an OpenCL buffer and
	 * verifies the copied values and the sentinel values around them.
	 */
	@Test(timeout = 120000)
	public void nativeToCl() {
		assertNativeToDevice(provider(ComputeRequirement.CL, CLMemoryProvider.class));
	}

	/**
	 * Copies a range of an OpenCL buffer into the middle of native memory and
	 * verifies the copied values and the sentinel values around them.
	 */
	@Test(timeout = 120000)
	public void clToNative() {
		assertDeviceToNative(provider(ComputeRequirement.CL, CLMemoryProvider.class));
	}

	/**
	 * Round-trips data native → CL → native and verifies it arrives unchanged,
	 * mirroring the prepare and postprocess copies that surround a dispatch whose
	 * arguments were replaced onto the OpenCL provider.
	 */
	@Test(timeout = 120000)
	public void roundTrip() {
		assertRoundTrip(provider(ComputeRequirement.CL, CLMemoryProvider.class));
	}

	/**
	 * Copies a range of native memory into the middle of a CUDA allocation and
	 * verifies the copied values and the sentinel values around them.
	 */
	@Test(timeout = 120000)
	public void nativeToCuda() {
		assertNativeToDevice(provider(ComputeRequirement.CUDA, CudaMemoryProvider.class));
	}

	/**
	 * Copies a range of a CUDA allocation into the middle of native memory and
	 * verifies the copied values and the sentinel values around them.
	 */
	@Test(timeout = 120000)
	public void cudaToNative() {
		assertDeviceToNative(provider(ComputeRequirement.CUDA, CudaMemoryProvider.class));
	}

	/**
	 * Round-trips data native → CUDA → native and verifies it arrives unchanged,
	 * mirroring the prepare and postprocess copies that surround a dispatch whose
	 * arguments were replaced onto the CUDA provider.
	 */
	@Test(timeout = 120000)
	public void cudaRoundTrip() {
		assertRoundTrip(provider(ComputeRequirement.CUDA, CudaMemoryProvider.class));
	}

	/**
	 * Copies native memory of the other precision into a CUDA allocation, which must convert
	 * each element rather than take the bulk byte copy.
	 */
	@Test(timeout = 120000)
	public void nativeToCudaMixedPrecision() {
		assertMixedPrecisionNativeToDevice(provider(ComputeRequirement.CUDA, CudaMemoryProvider.class));
	}

	/**
	 * Copies a CUDA allocation into native memory of the other precision, which must convert
	 * each element rather than use the registered bulk writer.
	 */
	@Test(timeout = 120000)
	public void cudaToNativeMixedPrecision() {
		assertMixedPrecisionDeviceToNative(provider(ComputeRequirement.CUDA, CudaMemoryProvider.class));
	}

	/**
	 * Copies native memory of the other precision into an OpenCL buffer, which must convert
	 * each element rather than take the bulk write.
	 */
	@Test(timeout = 120000)
	public void nativeToClMixedPrecision() {
		assertMixedPrecisionNativeToDevice(provider(ComputeRequirement.CL, CLMemoryProvider.class));
	}

	/**
	 * Copies an OpenCL buffer into native memory of the other precision, which must convert
	 * each element rather than use the registered bulk writer.
	 */
	@Test(timeout = 120000)
	public void clToNativeMixedPrecision() {
		assertMixedPrecisionDeviceToNative(provider(ComputeRequirement.CL, CLMemoryProvider.class));
	}

	/**
	 * Copies between two {@link NativeMemoryProvider} instances configured at different
	 * precisions, exercising the {@code double[]}-mediated fallback that {@link
	 * NativeMemoryProvider#setMem(RAM, int, io.almostrealism.code.Memory, int, int)} takes when
	 * the source and destination precisions differ. A same-precision {@code NativeBuffer}-to-
	 * {@code NativeBuffer} copy takes a raw byte-copy path instead; without the precision guard,
	 * that path would reinterpret each 8-byte FP64 element as a pair of unrelated FP32 values
	 * rather than converting it, so this confirms the mismatched-precision case is still correct.
	 */
	@Test(timeout = 60000)
	public void nativeToNativeMixedPrecision() {
		NativeMemoryProvider fp64 = new NativeMemoryProvider(Precision.FP64, 1024 * 1024, false, null, true);
		NativeMemoryProvider fp32 = new NativeMemoryProvider(Precision.FP32, 1024 * 1024, false, null, true);

		int n = 16;
		RAM source = fp64.allocate(n);
		RAM dest = fp32.allocate(n);

		try {
			double[] values = new double[n];
			for (int i = 0; i < n; i++) values[i] = i + 0.5;
			fp64.setMem(source, 0, values, 0, n);

			double[] sentinel = new double[n];
			for (int i = 0; i < n; i++) sentinel[i] = -1.0;
			fp32.setMem(dest, 0, sentinel, 0, n);

			// Copy all 16 elements from the FP64 source into the FP32 destination
			fp32.setMem(dest, 0, source, 0, n);

			double[] result = fp32.toArray(dest, 0, n);
			for (int i = 0; i < n; i++) {
				assertEquals("index " + i, i + 0.5, result[i]);
			}
		} finally {
			fp32.deallocate(n, dest);
			fp64.deallocate(n, source);
		}
	}

	/**
	 * Copies native memory to memory from {@code device}, as {@link #copyNativeToDevice} does,
	 * using the native provider of the active hardware configuration. Logs and returns when
	 * either provider is unavailable.
	 */
	private <T extends Memory> void assertNativeToDevice(MemoryProvider<T> device) {
		NativeMemoryProvider nativeProvider = nativeProvider();

		if (nativeProvider == null || device == null) {
			log("skipping, JNI or accelerator memory provider unavailable");
			return;
		}

		copyNativeToDevice(nativeProvider, device);
	}

	/**
	 * Copies memory from {@code device} to native memory, as {@link #copyDeviceToNative} does,
	 * using the native provider of the active hardware configuration. Logs and returns when
	 * either provider is unavailable.
	 */
	private <T extends Memory> void assertDeviceToNative(MemoryProvider<T> device) {
		NativeMemoryProvider nativeProvider = nativeProvider();

		if (nativeProvider == null || device == null) {
			log("skipping, JNI or accelerator memory provider unavailable");
			return;
		}

		copyDeviceToNative(nativeProvider, device);
	}

	/**
	 * Copies native memory to memory from {@code device} and back into other native memory,
	 * and verifies the values arrive unchanged. Logs and returns when either provider is
	 * unavailable.
	 */
	private <T extends Memory> void assertRoundTrip(MemoryProvider<T> device) {
		NativeMemoryProvider nativeProvider = nativeProvider();

		if (nativeProvider == null || device == null) {
			log("skipping, JNI or accelerator memory provider unavailable");
			return;
		}

		int n = 1024;
		RAM original = nativeProvider.allocate(n);
		T onDevice = device.allocate(n);
		RAM returned = nativeProvider.allocate(n);

		try {
			double[] values = new double[n];
			for (int i = 0; i < n; i++) values[i] = 0.5 * i;
			nativeProvider.setMem(original, 0, values, 0, n);

			device.setMem(onDevice, 0, original, 0, n);
			nativeProvider.setMem(returned, 0, onDevice, 0, n);

			double[] result = nativeProvider.toArray(returned, 0, n);
			for (int i = 0; i < n; i++) {
				assertEquals("index " + i, 0.5 * i, result[i]);
			}
		} finally {
			nativeProvider.deallocate(n, returned);
			device.deallocate(n, onDevice);
			nativeProvider.deallocate(n, original);
		}
	}

	/**
	 * Copies native memory stored at the precision {@code device} does not use to memory from
	 * {@code device}, as {@link #copyNativeToDevice} does. A bulk byte copy would reinterpret
	 * the elements instead of converting them. Logs and returns when the device provider is
	 * unavailable.
	 */
	private <T extends Memory> void assertMixedPrecisionNativeToDevice(MemoryProvider<T> device) {
		if (device == null) {
			log("skipping, accelerator memory provider unavailable");
			return;
		}

		copyNativeToDevice(otherPrecision(device), device);
	}

	/**
	 * Copies memory from {@code device} to native memory stored at the precision
	 * {@code device} does not use, as {@link #copyDeviceToNative} does. A bulk byte copy would
	 * reinterpret the elements instead of converting them. Logs and returns when the device
	 * provider is unavailable.
	 */
	private <T extends Memory> void assertMixedPrecisionDeviceToNative(MemoryProvider<T> device) {
		if (device == null) {
			log("skipping, accelerator memory provider unavailable");
			return;
		}

		copyDeviceToNative(otherPrecision(device), device);
	}

	/**
	 * Copies 32 elements from position 8 of memory from {@code nativeProvider} to position 16
	 * of memory from {@code device}, and verifies the copied values and the sentinel values
	 * around them.
	 */
	private <T extends Memory> void copyNativeToDevice(NativeMemoryProvider nativeProvider,
														 MemoryProvider<T> device) {
		int n = 96;
		RAM source = nativeProvider.allocate(n);
		T dest = device.allocate(n);

		try {
			nativeProvider.setMem(source, 0, sequence(n), 0, n);
			device.setMem(dest, 0, sentinel(n), 0, n);

			device.setMem(dest, 16, source, 8, 32);

			double[] result = device.toArray(dest, 0, n);

			for (int i = 0; i < n; i++) {
				double expected = (i >= 16 && i < 48) ? (i - 16) + 9.0 : -1.0;
				assertEquals("index " + i, expected, result[i]);
			}
		} finally {
			device.deallocate(n, dest);
			nativeProvider.deallocate(n, source);
		}
	}

	/**
	 * Copies 32 elements from position 8 of memory from {@code device} to position 16 of
	 * memory from {@code nativeProvider}, and verifies the copied values and the sentinel
	 * values around them.
	 */
	private <T extends Memory> void copyDeviceToNative(NativeMemoryProvider nativeProvider,
														 MemoryProvider<T> device) {
		int n = 96;
		T source = device.allocate(n);
		RAM dest = nativeProvider.allocate(n);

		try {
			device.setMem(source, 0, sequence(n), 0, n);
			nativeProvider.setMem(dest, 0, sentinel(n), 0, n);

			nativeProvider.setMem(dest, 16, source, 8, 32);

			double[] result = nativeProvider.toArray(dest, 0, n);

			for (int i = 0; i < n; i++) {
				double expected = (i >= 16 && i < 48) ? (i - 16) + 9.0 : -1.0;
				assertEquals("index " + i, expected, result[i]);
			}
		} finally {
			nativeProvider.deallocate(n, dest);
			device.deallocate(n, source);
		}
	}

	/**
	 * Returns a {@link NativeMemoryProvider} whose elements are not the width of those of
	 * {@code device}: FP32 when {@code device} stores doubles, FP64 otherwise.
	 */
	private static NativeMemoryProvider otherPrecision(MemoryProvider<?> device) {
		Precision precision = device.getNumberSize() == Double.BYTES ? Precision.FP32 : Precision.FP64;
		return new NativeMemoryProvider(precision, 1024 * 1024, false, null, true);
	}

	/** Returns {@code n} values counting up from 1. */
	private static double[] sequence(int n) {
		double[] values = new double[n];
		for (int i = 0; i < n; i++) values[i] = i + 1.0;
		return values;
	}

	/** Returns {@code n} copies of the sentinel value -1. */
	private static double[] sentinel(int n) {
		double[] values = new double[n];
		for (int i = 0; i < n; i++) values[i] = -1.0;
		return values;
	}

	/**
	 * Returns the memory provider of type {@code type} from the compute contexts the active
	 * hardware configuration offers for {@code requirement}, or null when there is none.
	 */
	private static <P> P provider(ComputeRequirement requirement, Class<P> type) {
		try {
			return Hardware.getLocalHardware()
					.getComputeContexts(false, true, requirement).stream()
					.flatMap(c -> c.getDataContext().getMemoryProviders().stream())
					.filter(type::isInstance)
					.map(type::cast)
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
		return provider(ComputeRequirement.JNI, NativeMemoryProvider.class);
	}
}
