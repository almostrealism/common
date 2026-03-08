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

package org.almostrealism.hardware.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.util.TestDepth;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

public class MemoryAllocationTest extends TestSuiteBase {
	@Test(timeout = 5 * 60000)
	@TestDepth(3)
	public void allocateAndDestroy() throws InterruptedException {
		long gb = 1024L * 1024L * 1024L;
		long limit = 256L * gb;
		int size = 256 * 1024 * 1024;
		int len = size / Hardware.getLocalHardware().getPrecision().bytes();

		long allocated = 0;
		while (allocated < limit) {
			PackedCollection b = new PackedCollection(len);
			allocated += size;
			for (int i = 0; i < 10; i++) b.setMem(Math.random() * len, Math.random());

			b.destroy();
			if (allocated % (32L * gb) == 0) {
				System.out.println("Allocated " + allocated / gb + "GB");
				Thread.sleep(10 * 1000L);
			}
		}

		Thread.sleep(120 * 1000L);
	}
}
