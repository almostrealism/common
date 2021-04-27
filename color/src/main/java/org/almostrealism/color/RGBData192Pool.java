/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.color;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.mem.MemoryPool;

public class RGBData192Pool extends MemoryPool<RGBData192> {
	private static RGBData192Pool local;

	public RGBData192Pool(int size) {
		super(3, size);
	}

	public static RGBData192Pool getLocal() {
		initPool();
		return local;
	}

	private static void initPool() {
		if (local != null) return;
		doInitPool();
	}

	private static synchronized void doInitPool() {
		int size = 4 * Hardware.getLocalHardware().getDefaultPoolSize();
		if (size > 0) local = new RGBData192Pool(size);
	}
}