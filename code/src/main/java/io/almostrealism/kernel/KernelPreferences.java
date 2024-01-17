/*
 * Copyright 2024 Michael Murray
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

public class KernelPreferences {
	private static boolean requireUniformPrecision = false;
	private static boolean enableSharedMemory = false;

	private static boolean preferKernels = true;
	private static boolean preferLoops = false;
	private static boolean enableSubdivision = true;

	static {
		if (SystemUtils.isEnabled("AR_HARDWARE_PREFER_LOOPS").orElse(false)) {
			throw new UnsupportedOperationException();
		}

		Optional<Boolean> sharedMem = SystemUtils.isEnabled("AR_HARDWARE_SHARED_MEMORY");
		if (sharedMem.isPresent()) {
			KernelPreferences.enableSharedMemory = sharedMem.get();
		}
	}

	public static int getWorkSubdivisionMinimum() {
		return 512;
	}

	public static void requireUniformPrecision() {
		requireUniformPrecision = true;
	}

	public static boolean isRequireUniformPrecision() {
		return requireUniformPrecision;
	}

	public static void enableSharedMemory() {
		enableSharedMemory = true;
	}

	public static void setPreferKernels(boolean preferKernels) {
		KernelPreferences.preferKernels = preferKernels;
	}

	public static boolean isPreferKernels() {
		return preferKernels;
	}

	@Deprecated
	public static boolean isPreferLoops() { return preferLoops; }

	public static boolean isEnableSubdivision() { return enableSubdivision; }

	public static boolean isEnableSharedMemory() { return enableSharedMemory; }
}
