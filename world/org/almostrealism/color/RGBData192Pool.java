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

package org.almostrealism.color;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryPool;

public class RGBData192Pool extends MemoryPool<RGBData192> {
	private static final RGBData192Pool local =
			new RGBData192Pool(3 * Hardware.getLocalHardware().getDefaultPoolSize());

	public RGBData192Pool(int size) {
		super(3, size);
	}

	public static RGBData192Pool getLocal() { return local; }
}
