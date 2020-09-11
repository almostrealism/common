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

package org.almostrealism.hardware;

import org.almostrealism.algebra.Pair;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.cl_mem;

public interface MemWrapper {
	cl_mem getMem();
	default int getOffset() { return 0; }
	int getMemLength();

	default int getAtomicMemLength() {
		return getMemLength();
	}

	void destroy();

	/**
	 * If a delegate is set using this method, then the {@link cl_mem} for the delegate
	 * should be used to store and retrieve data, with the specified offset. The offset
	 * size is based on the size of a double, it indicates the number of double values
	 * to skip over to get to the location in the {@link cl_mem} where data should be
	 * kept.
	 */
	void setDelegate(MemWrapper m, int offset);

	// TODO  This could be faster by moving directly between cl_mems
	static Pair fromMem(cl_mem mem, int sOffset, int length) {
		if (length != 1 && length != 2) {
			throw new IllegalArgumentException(String.valueOf(length));
		}

		if (Hardware.getLocalHardware().isDoublePrecision()) {
			double out[] = new double[length];
			Pointer dst = Pointer.to(out).withByteOffset(0);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * Hardware.getLocalHardware().getNumberSize(),
					length * Hardware.getLocalHardware().getNumberSize(), dst, 0,
					null, null);
			if (length == 1) {
				return new Pair(out[0], 0);
			} else {
				return new Pair(out[0], out[1]);
			}
		} else {
			float out[] = new float[length];
			Pointer dst = Pointer.to(out).withByteOffset(0);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * Hardware.getLocalHardware().getNumberSize(),
					length * Hardware.getLocalHardware().getNumberSize(), dst, 0,
					null, null);
			if (length == 1) {
				return new Pair(out[0], 0);
			} else {
				return new Pair(out[0], out[1]);
			}
		}
	}
}
