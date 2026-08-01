/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.kernel;

import org.almostrealism.io.SystemUtils;

import java.util.Optional;

/**
 * Global hardware-level preferences that control kernel scheduling, memory layout,
 * and work subdivision for GPU/CPU acceleration backends.
 *
 * <p>Settings can be overridden via system properties and environment variables
 * (e.g. {@code AR_HARDWARE_SHARED_MEMORY}, {@code AR_HARDWARE_EVAL_PARALLELISM},
 * {@code AR_HARDWARE_CPU_PARALLELISM}).</p>
 */
public class KernelPreferences {
	/** When {@code true}, all arguments must use the same floating-point precision. */
	private static boolean requireUniformPrecision = false;

	/** When {@code true}, shared GPU memory is used for intermediate results. */
	private static boolean enableSharedMemory = false;

	/** When {@code true}, GPU kernel dispatch is preferred over CPU evaluation for operations. */
	private static boolean preferKernels = true;

	/** When {@code true}, large work items are split into smaller units for parallel execution. */
	private static boolean enableSubdivision = true;

	static {
		Optional<Boolean> sharedMem = SystemUtils.isEnabled("AR_HARDWARE_SHARED_MEMORY");
		if (sharedMem.isPresent()) {
			KernelPreferences.enableSharedMemory = sharedMem.get();
		}
	}

	/**
	 * Returns the number of threads used for parallel evaluation of operations.
	 * Reads {@code AR_HARDWARE_EVAL_PARALLELISM}; defaults to {@code max(2, availableProcessors * 2 / cpuParallelism)}.
	 *
	 * @return the evaluation parallelism
	 */
	public static int getEvaluationParallelism() {
		return SystemUtils.getInt("AR_HARDWARE_EVAL_PARALLELISM")
				.orElse(Math.max(2, Runtime.getRuntime().availableProcessors() * 2 / getCpuParallelism()));
	}

	/**
	 * Returns the number of CPU threads used for parallel kernel execution.
	 * Reads {@code AR_HARDWARE_CPU_PARALLELISM}; defaults to {@code max(2, availableProcessors / 2)}.
	 *
	 * @return the CPU parallelism
	 */
	public static int getCpuParallelism() {
		return SystemUtils.getInt("AR_HARDWARE_CPU_PARALLELISM")
				.orElse(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
	}

	/**
	 * Returns the minimum work size before work subdivision is applied, expressed as
	 * a multiple of {@link #getWorkSubdivisionUnit()}.
	 *
	 * @return the minimum work size for subdivision
	 */
	public static int getWorkSubdivisionMinimum() {
		return getWorkSubdivisionUnit() * 8;
	}

	/**
	 * Returns the granularity at which large work items are subdivided for parallel execution.
	 *
	 * @return the work subdivision unit size
	 */
	public static int getWorkSubdivisionUnit() {
		return 32;
	}

	/**
	 * Enables the requirement that all kernel arguments use the same floating-point precision.
	 */
	public static void requireUniformPrecision() {
		requireUniformPrecision = true;
	}

	/**
	 * Returns {@code true} if uniform floating-point precision is required across all kernel arguments.
	 *
	 * @return {@code true} if uniform precision is required
	 */
	public static boolean isRequireUniformPrecision() {
		return requireUniformPrecision;
	}

	/**
	 * Enables the use of shared GPU memory for intermediate kernel results.
	 */
	public static void enableSharedMemory() {
		enableSharedMemory = true;
	}

	/**
	 * Sets whether GPU kernel dispatch is preferred over CPU evaluation.
	 *
	 * @param preferKernels {@code true} to prefer GPU kernels; {@code false} to prefer CPU evaluation
	 */
	public static void setPreferKernels(boolean preferKernels) {
		KernelPreferences.preferKernels = preferKernels;
	}

	/**
	 * Returns {@code true} if GPU kernel dispatch is preferred over CPU evaluation.
	 *
	 * @return {@code true} if GPU kernels are preferred
	 */
	public static boolean isPreferKernels() {
		return preferKernels;
	}

	/**
	 * Returns {@code true} if work subdivision is enabled for large kernel operations.
	 *
	 * @return {@code true} if subdivision is enabled
	 */
	public static boolean isEnableSubdivision() { return enableSubdivision; }

	/**
	 * Returns {@code true} if shared GPU memory is enabled for intermediate results.
	 *
	 * @return {@code true} if shared memory is enabled
	 */
	public static boolean isEnableSharedMemory() { return enableSharedMemory; }
}
