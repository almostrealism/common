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

package org.almostrealism.hardware;

import io.almostrealism.code.Memory;
import io.almostrealism.code.expressions.MultiExpression;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.relation.Delegated;
import org.almostrealism.hardware.cl.CLMemory;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.cl_mem;

public interface MemoryData extends MultiExpression<Double>, Delegated<MemoryData> {
	int sizeOf = Hardware.getLocalHardware().getNumberSize();

	Memory getMem();

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
	void setDelegate(MemoryData m, int offset);

	@Override
	MemoryData getDelegate();

	int getDelegateOffset();

	@Override
	default Expression<Double> getValue(int pos) {
		double out[] = new double[1];
		getMem(pos, out, 0, 1);

		String s = Hardware.getLocalHardware().stringForDouble(out[0]);
		if (s.contains("Infinity")) {
			throw new IllegalArgumentException("Infinity is not supported");
		}

		return new Expression<>(Double.class, s);
	}

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

	default void setMem(int offset, MemoryData src, int srcOffset, int length) {
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

	static void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, source, srcOffset, length);
	}

	static void setMem(Memory mem, int offset, MemoryData src, int srcOffset, int length) {
		if (mem.getProvider() != src.getMem().getProvider()) {
			throw new IllegalArgumentException("Memory cannot be moved across different MemoryProviders");
		}

		mem.getProvider().setMem(mem, offset, src.getMem(), src.getOffset() + srcOffset, length);
	}

	static void getMem(Memory mem, int sOffset, double out[], int oOffset, int length) {
		mem.getProvider().getMem(mem, sOffset, out, oOffset, length);
	}
}
