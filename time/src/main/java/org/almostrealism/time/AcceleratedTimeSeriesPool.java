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

package org.almostrealism.time;

import io.almostrealism.code.DataContext;
import org.almostrealism.hardware.cl.CLDataContext;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;
import org.almostrealism.hardware.mem.MemoryPool;

import java.util.Optional;

public class AcceleratedTimeSeriesPool extends MemoryPool<AcceleratedTimeSeries> {
	private static ContextSpecific<AcceleratedTimeSeriesPool> local;

	public AcceleratedTimeSeriesPool(int size, int count) {
		super(2 * size, count);
	}

	public static AcceleratedTimeSeriesPool getLocal() {
		initPool();
		return Optional.ofNullable(local).map(ContextSpecific::getValue).orElse(null);
	}

	private static void initPool() {
		if (local != null) return;
		doInitPool();
	}

	private static synchronized void doInitPool() {
		int size = Hardware.getLocalHardware().getTimeSeriesSize();
		int count = Hardware.getLocalHardware().getTimeSeriesCount();

		if (size > 0) {
			local = new DefaultContextSpecific<>(() -> {
				DataContext ctx = Hardware.getLocalHardware().getDataContext();
				int numSize = Hardware.getLocalHardware().getNumberSize();

				if (ctx instanceof CLDataContext == false || (2L * size * count * numSize) < ((CLDataContext) ctx).getMainDeviceInfo().getMaxAlloc()) {
					return new AcceleratedTimeSeriesPool(size, count);
				} else {
					int c = count;
					while ((2L * size * c * numSize) > ((CLDataContext) ctx).getMainDeviceInfo().getMaxAlloc()) c--;
					System.out.println("AcceleratedTimeSeriesPool: Allocation limit requires pool reduced to " + c);
					return new AcceleratedTimeSeriesPool(size, c);
				}
			}, pool -> pool.destroy());

			local.init();
		}
	}
}
