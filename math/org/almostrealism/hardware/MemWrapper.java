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
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.cl_mem;

public interface MemWrapper {
	int sizeOf = Hardware.getLocalHardware().getNumberSize();

	cl_mem getMem();

	default int getOffset() {
		if (getDelegate() == null) {
			return getDelegateOffset();
		} else {
			return getDelegateOffset() + getDelegate().getOffset();
		}
	}
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

	MemWrapper getDelegate();

	int getDelegateOffset();

	default void setMem(double[] source) {
		setMem(0, source, 0, getMemLength());
	}

	default void setMem(double[] source, int offset) {
		setMem(0, source, offset, getMemLength());
	}

	default void setMem(int offset, double[] source, int srcOffset, int length) {
		if (getDelegate() == null) {
			setMem(getMem(), getOffset() + offset, source, srcOffset, length);
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, source, srcOffset, length);
		}
	}

	default void setMem(int offset, MemWrapper src, int srcOffset, int length) {
		if (getDelegate() == null) {
			setMem(getMem(), getOffset() + offset, src, srcOffset, length);
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, src, srcOffset, length);
		}
	}

	default void getMem(int sOffset, double out[], int oOffset, int length) {
		if (getDelegate() == null) {
			getMem(getMem(), getOffset() + sOffset, out, oOffset, length);
		} else {
			getDelegate().getMem(getDelegateOffset() + sOffset, out, oOffset, length);
		}
	}

	default void getMem(double out[], int offset) { getMem(0, out, offset, getMemLength()); }

	static void setMem(cl_mem mem, int offset, double[] source, int srcOffset, int length) {
		if (Hardware.getLocalHardware().isDoublePrecision()) {
			Pointer src = Pointer.to(source).withByteOffset(srcOffset * sizeOf);
			CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
					offset * sizeOf, length * sizeOf,
					src, 0, null, null);
		} else {
			float f[] = new float[length];
			for (int i = 0; i < f.length; i++) f[i] = (float) source[srcOffset + i];
			Pointer src = Pointer.to(f).withByteOffset(0);
			CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
					offset * sizeOf, length * sizeOf,
					src, 0, null, null);
		}
	}

	static void setMem(cl_mem mem, int offset, MemWrapper src, int srcOffset, int length) {
		if (src.getDelegate() == null) {
			try {
				CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.getMem(), mem,
						srcOffset * sizeOf,
						offset * sizeOf, length * sizeOf,
						0, null, null);
			} catch (CLException e) {
				throw e;
			}
		} else {
			setMem(mem, offset, src.getDelegate(), src.getDelegateOffset() + srcOffset, length);
		}
	}

	static void getMem(cl_mem mem, int sOffset, double out[], int oOffset, int length) {
		if (Hardware.getLocalHardware().isDoublePrecision()) {
			Pointer dst = Pointer.to(out).withByteOffset(oOffset * sizeOf);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * sizeOf,
					length * sizeOf, dst, 0,
					null, null);
		} else {
			float f[] = new float[length];
			Pointer dst = Pointer.to(f).withByteOffset(0);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * sizeOf,
					length * sizeOf, dst, 0,
					null, null);
			for (int i = 0; i < f.length; i++) out[oOffset + i] = f[i];
		}
	}

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
