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

package org.almostrealism.hardware;

import io.almostrealism.kernel.KernelPreferences;
import org.almostrealism.hardware.cl.CLMemoryProvider.Location;
import org.almostrealism.hardware.cuda.CudaDataContext;
import org.almostrealism.io.SystemUtils;

import java.util.Optional;

/**
 * The configuration {@link Hardware} initializes with, resolved from the environment before any
 * backend context exists: which backends to start, where OpenCL allocates, whether host kernels
 * share memory with an accelerator, and whether the NIO bridge provides that shared memory.
 *
 * <h2>Shared memory</h2>
 *
 * <p>When memory is shared, host kernels allocate from an accelerator's memory, so that the host
 * and the accelerator operate on the same allocations instead of copying arguments and results
 * between them for every kernel. That requires memory host code can address directly, which only
 * some backends on some devices provide, and it constrains every backend to the precision they
 * share. Both must be decided before any context is created, so the decision is made here.</p>
 *
 * <p>{@code AR_HARDWARE_SHARED_MEMORY} decides it when set ({@code enabled} or {@code disabled}).
 * Otherwise memory is shared when Metal, whose buffers live in the host's unified memory, is
 * requested (see {@link DriverSelection#sharesMemoryByDefault()}), and the bare {@code *} wildcard
 * on macOS also requests it, as it always has. CUDA is not shared by default, even on a device
 * whose managed memory host code can address ({@link CudaDataContext#isHostAccessibleMemoryAvailable()});
 * {@code enabled} still shares it.</p>
 */
final class HardwareSettings {
	/** The backends to start. */
	private final DriverSelection selection;

	/** Where OpenCL allocates memory. */
	private final Location location;

	/** Whether the NIO bridge provides memory shared between backends. */
	private final boolean nioMemory;

	/**
	 * Creates the settings.
	 *
	 * @param selection the backends to start
	 * @param location  where OpenCL allocates memory
	 * @param nioMemory whether the NIO bridge provides shared memory
	 */
	private HardwareSettings(DriverSelection selection, Location location, boolean nioMemory) {
		this.selection = selection;
		this.location = location;
		this.nioMemory = nioMemory;
	}

	/** Returns the backends to start. */
	DriverSelection getSelection() { return selection; }

	/** Returns where OpenCL allocates memory. */
	Location getLocation() { return location; }

	/** Returns whether the NIO bridge provides memory shared between backends. */
	boolean isNioMemory() { return nioMemory; }

	/**
	 * Resolves the settings from the environment, and applies the {@link KernelPreferences} they
	 * imply: uniform precision when OpenCL is named, and shared memory when it is decided on (see
	 * the class documentation).
	 *
	 * @param macOS   whether the host is macOS
	 * @param aarch64 whether the host is aarch64
	 * @return the settings
	 * @throws IllegalArgumentException if the requested OpenCL memory location cannot be used with
	 *                                  NIO shared memory
	 */
	static HardwareSettings resolve(boolean macOS, boolean aarch64) {
		DriverSelection selection = DriverSelection.parse(
				SystemUtils.getProperty("AR_HARDWARE_DRIVER", "*"), macOS, aarch64);

		if (selection.isUniformPrecisionRequired()) {
			KernelPreferences.requireUniformPrecision();
		}

		Optional<Boolean> requested = SystemUtils.isEnabled("AR_HARDWARE_SHARED_MEMORY");
		if (requested.orElseGet(() -> selection.isSharedMemoryPreferred() || selection.sharesMemoryByDefault())) {
			KernelPreferences.enableSharedMemory();
		}

		boolean nioMemory = SystemUtils.isEnabled("AR_HARDWARE_NIO_MEMORY")
				.orElse(selection.isSharedMemoryPreferred());
		return new HardwareSettings(selection, location(nioMemory), nioMemory);
	}

	/**
	 * Resolves where OpenCL allocates memory from {@code AR_HARDWARE_MEMORY_LOCATION}. With the NIO
	 * bridge, OpenCL must allocate through it ({@link Location#DELEGATE}).
	 *
	 * @param nioMemory whether the NIO bridge provides shared memory
	 * @return the location
	 * @throws IllegalArgumentException if the requested location cannot be used with NIO memory
	 */
	private static Location location(boolean nioMemory) {
		String memLocation = SystemUtils.getProperty("AR_HARDWARE_MEMORY_LOCATION");
		Location location = Location.DEVICE;
		if ("heap".equalsIgnoreCase(memLocation)) {
			location = Location.HEAP;
		} else if ("host".equalsIgnoreCase(memLocation)) {
			location = Location.HOST;
		} else if ("delegate".equalsIgnoreCase(memLocation)) {
			location = Location.DELEGATE;
		}

		if (!nioMemory) return location;

		if (memLocation != null) {
			if (location == Location.HOST) {
				Hardware.console.warn("NIO memory is enabled, location will be set to DELEGATE instead of HOST");
			} else if (location != Location.DELEGATE) {
				throw new IllegalArgumentException("Cannot use location " + memLocation + " with NIO memory");
			}
		}

		return Location.DELEGATE;
	}
}
