/*
 * Copyright 2020 Michael Murray
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

public class ScalarPool extends MemoryPool<Scalar> {
	private static final ScalarPool local =
			new ScalarPool(Hardware.getLocalHardware().getDefaultPoolSize() / 2);

	public ScalarPool(int size) {
		super(2, size);
	}

	public static ScalarPool getLocal() { return local; }
}
