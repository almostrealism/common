/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.cuda;

import java.util.stream.Stream;

/**
 * A CUDA device.
 *
 * <p>Exposes the device attributes the backend depends on and compiles CUDA C++ source
 * for this device's architecture with NVRTC. The device's primary context is obtained
 * with {@link #retainPrimaryContext()}.</p>
 */
public class CUDevice {
	/** The ordinal this device was obtained with. */
	private final int ordinal;

	/** The driver's device handle. */
	private final int handle;

	/** Wraps a device handle. Obtain instances with {@link #get(int)}. */
	private CUDevice(int ordinal, int handle) {
		this.ordinal = ordinal;
		this.handle = handle;
	}

	/** Returns the ordinal this device was obtained with. */
	public int getOrdinal() { return ordinal; }

	/** Returns the device handle, for use with {@link CU} only. */
	int getHandle() { return handle; }

	/** Returns the device name reported by the driver. */
	public String getName() { return CU.deviceName(handle); }

	/** Returns the value of a {@code CU_DEVICE_ATTRIBUTE_*} attribute (see the constants on {@link CU}). */
	public int getAttribute(int attribute) { return CU.deviceAttribute(handle, attribute); }

	/** Returns the total memory of this device in bytes. */
	public long getTotalMemory() { return CU.totalMemory(handle); }

	/** Returns the major compute capability. */
	public int getComputeCapabilityMajor() {
		return getAttribute(CU.DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR);
	}

	/** Returns the minor compute capability. */
	public int getComputeCapabilityMinor() {
		return getAttribute(CU.DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR);
	}

	/** Returns the maximum number of threads in a block. */
	public int getMaxThreadsPerBlock() { return getAttribute(CU.DEVICE_ATTRIBUTE_MAX_THREADS_PER_BLOCK); }

	/** Returns the maximum number of blocks in the x dimension of a grid. */
	public int getMaxGridDimX() { return getAttribute(CU.DEVICE_ATTRIBUTE_MAX_GRID_DIM_X); }

	/** Returns true if this device shares physical memory with the host. */
	public boolean isIntegrated() { return getAttribute(CU.DEVICE_ATTRIBUTE_INTEGRATED) != 0; }

	/**
	 * Returns true if managed memory can be accessed by host and device concurrently
	 * on this device, which is the condition for using managed allocations as memory
	 * shared zero-copy with host code.
	 */
	public boolean isConcurrentManagedAccess() {
		return getAttribute(CU.DEVICE_ATTRIBUTE_MANAGED_MEMORY) != 0 &&
				getAttribute(CU.DEVICE_ATTRIBUTE_CONCURRENT_MANAGED_ACCESS) != 0;
	}

	/** Returns the NVRTC virtual/real architecture suffix for this device, such as {@code 121}. */
	public String getArchitecture() {
		return getComputeCapabilityMajor() + "" + getComputeCapabilityMinor();
	}

	/**
	 * Compiles CUDA C++ source for this device into a CUBIN image.
	 *
	 * @param source  the source to compile
	 * @param name    the program name used in diagnostics
	 * @param options additional NVRTC options
	 * @return the compiled image, to be loaded with {@link CUContext#loadModule(byte[])}
	 * @throws org.almostrealism.hardware.HardwareException if compilation fails; the
	 *         message includes the NVRTC log
	 */
	public byte[] compile(String source, String name, String... options) {
		String[] all = Stream.concat(Stream.of("--gpu-architecture=sm_" + getArchitecture()),
				Stream.of(options)).toArray(String[]::new);
		return CU.compile(source, name, all, true);
	}

	/**
	 * Retains this device's primary context. Each retain must be matched by a
	 * {@link CUContext#release()}.
	 */
	public CUContext retainPrimaryContext() {
		return new CUContext(this, CU.primaryContextRetain(handle));
	}

	@Override
	public String toString() { return "CUDevice[" + ordinal + ": " + getName() + "]"; }

	/** Returns the number of CUDA devices visible to this process. */
	public static int getDeviceCount() { return CU.deviceCount(); }

	/** Returns the device with the given ordinal. */
	public static CUDevice get(int ordinal) {
		return new CUDevice(ordinal, CU.device(ordinal));
	}
}
