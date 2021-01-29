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

package org.almostrealism.algebra;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryPool;

public class VectorPool extends MemoryPool<Pair> {
	private static VectorPool local;

	public VectorPool(int size) {
		super(3, size);
	}

	public static VectorPool getLocal() {
		initPool();
		return local;
	}

	private static void initPool() {
		if (local != null) return;
		doInitPool();
	}

	private static synchronized void doInitPool() {
		int size = 2 * Hardware.getLocalHardware().getDefaultPoolSize();
		if (size > 0) local = new VectorPool(size);
	}
}
