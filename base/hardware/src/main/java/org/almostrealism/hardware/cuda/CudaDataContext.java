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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AcceleratorDataContext;
import org.almostrealism.io.SystemUtils;

/**
 * {@link io.almostrealism.code.DataContext} for NVIDIA GPUs through CUDA.
 *
 * <p>The device is selected with {@code AR_HARDWARE_CUDA_DEVICE} (default {@code 0}), and
 * its primary context is retained lazily, the first time the context is used, so that
 * constructing and initializing a {@link CudaDataContext} never touches the driver. The lazy
 * start, the shared compute context and the deferred release of the device are provided by
 * {@link AcceleratorDataContext}.</p>
 *
 * <p>Allocations are managed memory when the device shares physical memory with the host
 * and supports concurrent managed access, and device memory otherwise.
 * {@code AR_HARDWARE_CUDA_MEMORY} ({@code managed} or {@code device}) overrides the choice.</p>
 */
public class CudaDataContext extends AcceleratorDataContext<CudaMemoryProvider> {
	/** The ordinal of the device to use. */
	private static final int DEVICE_ORDINAL = SystemUtils.getInt("AR_HARDWARE_CUDA_DEVICE").orElse(0);

	/** The explicitly requested allocation mode ({@code managed} or {@code device}), or null. */
	private static final String MEMORY_MODE = SystemUtils.getProperty("AR_HARDWARE_CUDA_MEMORY");

	/** The device, once started. */
	private CUDevice device;

	/** The device's retained primary context, once started. */
	private CUContext cudaContext;

	/**
	 * Creates a data context. No driver call is made until the context is first used.
	 *
	 * @param name           the context name
	 * @param maxReservation the maximum number of values that may be allocated at once
	 * @param offHeapSize    allocations of fewer values than this are made on the JVM heap
	 */
	public CudaDataContext(String name, long maxReservation, int offHeapSize) {
		super(name, maxReservation, offHeapSize);
	}

	/** Retains the device's primary context and creates the memory provider. */
	@Override
	protected CudaMemoryProvider startDevice() {
		device = CUDevice.get(DEVICE_ORDINAL);
		cudaContext = device.retainPrimaryContext();

		boolean managed = MEMORY_MODE == null ?
				device.isIntegrated() && device.isConcurrentManagedAccess() :
				"managed".equalsIgnoreCase(MEMORY_MODE);

		log("Hardware[" + getName() + "]: Using " + device.getName() + " (sm_" +
				device.getArchitecture() + ") with " + (managed ? "managed" : "device") + " memory");

		return new CudaMemoryProvider(this, getPrecision().bytes(),
				getMaxReservation() * getPrecision().bytes(), managed);
	}

	/** Creates a {@link CudaComputeContext} with a stream of its own. */
	@Override
	protected ComputeContext<MemoryData> newComputeContext() {
		return new CudaComputeContext(this);
	}

	@Override
	public Precision getPrecision() { return Precision.FP32; }

	/** Returns the device, starting it if necessary. */
	public CUDevice getDevice() {
		ensureStarted();
		return device;
	}

	/** Returns the device's primary context, starting it if necessary. */
	public CUContext getCudaContext() {
		ensureStarted();
		return cudaContext;
	}

	/** Releases the retained primary context. */
	@Override
	protected synchronized void releaseDevice() {
		if (cudaContext != null) {
			cudaContext.release();
			cudaContext = null;
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + getName() + "]";
	}
}
