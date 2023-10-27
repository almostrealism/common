/*
 * Copyright 2023 Michael Murray
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

public class KernelPreferences {
	public static boolean enableSharedMemory = false;

	private static boolean preferLoops = false;
	private static boolean enableSubdivision = true;

	public static int getWorkSubdivisionMinimum() {
		return 512;
	}

	public static void setPreferLoops(boolean preferLoops) {
		KernelPreferences.preferLoops = preferLoops;
	}

	public static boolean isPreferLoops() { return preferLoops; }

	public static void setEnableSubdivision(boolean enableSubdivision) {
		KernelPreferences.enableSubdivision = enableSubdivision;
	}

	public static boolean isEnableSubdivision() { return enableSubdivision; }

	public static void optimizeForMetal() {
		KernelPreferences.enableSharedMemory = true;
		KernelPreferences.setPreferLoops(true);
		KernelPreferences.setEnableSubdivision(false);
	}
}
