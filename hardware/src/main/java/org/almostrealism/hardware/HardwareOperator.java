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

package org.almostrealism.hardware;

import io.almostrealism.code.Execution;

public abstract class HardwareOperator implements Execution, KernelWork {
	public static boolean enableLog;
	public static boolean enableVerboseLog;
	public static boolean enableDimensionMasks = true;
	public static boolean enableAtomicDimensionMasks = true;

	public static void verboseLog(Runnable r) {
		boolean log = enableVerboseLog;
		enableVerboseLog = true;
		r.run();
		enableVerboseLog = log;
	}

	public static void disableDimensionMasks(Runnable r) {
		boolean masks = enableDimensionMasks;
		enableDimensionMasks = false;
		r.run();
		enableDimensionMasks = masks;
	}
}
