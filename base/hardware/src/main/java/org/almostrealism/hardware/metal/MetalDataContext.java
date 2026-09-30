/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.AcceleratorDataContext;
import org.almostrealism.io.SystemUtils;

import java.util.Optional;

/**
 * {@link io.almostrealism.code.DataContext} for Apple Metal GPU backend.
 *
 * <p>Manages Metal {@link MTLDevice}, {@link MetalMemoryProvider}, and {@link MetalComputeContext}
 * for GPU-accelerated computation on macOS and iOS platforms. The lazy start, the shared
 * compute context and the deferred release of the device are provided by
 * {@link AcceleratorDataContext}.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * MetalDataContext context = new MetalDataContext(
 *     "Metal",
 *     1024 * 1024 * 1024,  // 1GB max
 *     1024 * 1024);         // 1MB threshold
 * context.init();
 *
 * MTLDevice device = context.getDevice();
 * MetalMemoryProvider memory = (MetalMemoryProvider) context.getMemoryProvider();
 * }</pre>
 *
 * <h2>Precision Support</h2>
 *
 * <pre>{@code
 * // Default: FP32
 * Precision p = context.getPrecision();  // FP32
 *
 * // Enable FP16: Set environment variable
 * // AR_HARDWARE_PRECISION=FP16
 * Precision p = context.getPrecision();  // FP16
 * }</pre>
 *
 * @see MetalComputeContext
 * @see MetalMemoryProvider
 * @see MTLDevice
 */
public class MetalDataContext extends AcceleratorDataContext<MetalMemoryProvider> {
	/**
	 * True if FP16 (half precision) mode is enabled via AR_HARDWARE_PRECISION=FP16 environment variable.
	 */
	public static final boolean fp16 = SystemUtils.getProperty("AR_HARDWARE_PRECISION", "FP32").equals("FP16");

	/** The primary Metal device used for all GPU buffer allocations and kernel execution. */
	private MTLDevice mainDevice;

	/**
	 * Creates a Metal data context with specified memory limits.
	 *
	 * @param name Display name for this context
	 * @param maxReservation Maximum memory in elements (not bytes) that can be allocated
	 * @param offHeapSize Threshold in elements below which JVM heap is used instead of Metal buffers
	 */
	public MetalDataContext(String name, long maxReservation, int offHeapSize) {
		super(name, maxReservation, offHeapSize);
	}

	/**
	 * Identifies and initializes the Metal device.
	 *
	 * <p>Creates the system default Metal device if not already initialized.
	 * Called lazily on first access to the device or memory providers.</p>
	 */
	protected void identifyDevices() {
		if (mainDevice != null) return;

		mainDevice = MTLDevice.createSystemDefaultDevice();
		log("Hardware[" + getName() + "]: Using the system default GPU for kernels");
	}

	/**
	 * Locates the system GPU and creates a {@link MetalMemoryProvider} sized to the
	 * configured max reservation.
	 */
	@Override
	protected MetalMemoryProvider startDevice() {
		identifyDevices();
		return new MetalMemoryProvider(this, getPrecision().bytes(),
				getMaxReservation() * getPrecision().bytes());
	}

	/** Creates a {@link MetalComputeContext} backed by the main device. */
	@Override
	protected ComputeContext<MemoryData> newComputeContext() {
		MetalComputeContext cc = new MetalComputeContext(this);
		cc.init(mainDevice);
		return cc;
	}

	/**
	 * Returns the precision mode for this context.
	 *
	 * @return {@link Precision#FP16} if AR_HARDWARE_PRECISION=FP16, otherwise {@link Precision#FP32}
	 */
	@Override
	public Precision getPrecision() { return fp16 ? Precision.FP16 : Precision.FP32; }

	/**
	 * Returns the Metal device for this context.
	 *
	 * <p>Triggers lazy initialization if not yet started.</p>
	 *
	 * @return The {@link MTLDevice} instance for the system default GPU
	 */
	public MTLDevice getDevice() {
		ensureStarted();
		return mainDevice;
	}

	/**
	 * Returns or creates the shared memory provider for memory-mapped buffers.
	 *
	 * <p>Creates a {@link MetalMemoryProvider} in shared mode for CPU/GPU accessible
	 * memory backed by memory-mapped files.</p>
	 *
	 * @return {@link MetalMemoryProvider} in shared storage mode
	 */
	@Override
	protected MemoryProvider getSharedMemoryProvider() {
		return Optional.ofNullable(super.getSharedMemoryProvider())
				.orElseGet(() -> new MetalMemoryProvider(this, getPrecision().bytes(),
						getMaxReservation() * getPrecision().bytes(), true));
	}

	/** Releases the underlying Metal device, once, if it has not already been released. */
	@Override
	protected synchronized void releaseDevice() {
		if (mainDevice != null) {
			mainDevice.release();
			mainDevice = null;
		}
	}
}
