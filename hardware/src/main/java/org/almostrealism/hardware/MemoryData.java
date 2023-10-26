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

import io.almostrealism.code.Memory;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public interface MemoryData extends TraversableExpression<Double>, Delegated<MemoryData>, Node {

	Memory getMem();

	void reassign(Memory mem);

	default void load(byte b[]) {
		ByteBuffer buf = ByteBuffer.allocate(8 * getMemLength());

		for (int i = 0; i < getMemLength() * 8; i++) {
			buf.put(b[i]);
		}

		buf.position(0);

		for (int i = 0; i < getMemLength(); i++) {
			getMem().set(getOffset() + i, buf.getDouble());
		}
	}

	default void load(InputStream in) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(8 * getMemLength());

		for (int i = 0; i < getMemLength(); i++) {
			buf.put(in.readNBytes(8));
		}

		buf.position(0);

		for (int i = 0; i < getMemLength(); i++) {
			getMem().set(getOffset() + i, buf.getDouble());
		}
	}

	default byte[] persist() {
		return getMem().getBytes(getOffset(), getMemLength()).array();
	}

	default void persist(OutputStream out) throws IOException {
		out.write(getMem().getBytes(getOffset(), getMemLength()).array());
	}

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
	 * If a delegate is set using this method, then the {@link Memory} for the delegate
	 * should be used to store and retrieve data, with the specified offset. The offset
	 * size is based on the size of a double, it indicates the number of double values
	 * to skip over to get to the location in the {@link Memory} where data should be
	 * kept.
	 */
	void setDelegate(MemoryData m, int offset);

	@Override
	MemoryData getDelegate();

	int getDelegateOffset();

	default double toDouble(int index) {
		return toArray(index, 1)[0];
	}

	default double[] toArray(int offset, int length) {
		return getMem().toArray(getOffset() + offset, length);
	}

	@Override
	default Expression<Double> getValue(Expression... pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	default Expression<Double> getValueAt(Expression pos) {
		int i = pos.intValue().orElseThrow(UnsupportedOperationException::new);

		if (i > getMemLength()) {
			throw new IllegalArgumentException(i + " is out of bounds for MemoryData of length " + getMemLength());
		}

		if (getMem().getProvider().getNumberSize() == 8) {
			double out[] = new double[1];
			getMem(i, out, 0, 1);
			return new DoubleConstant(out[0]);
		} else {
			float out[] = new float[1];
			getMem(i, out, 0, 1);
			return new DoubleConstant((double) out[0]);
		}
	}

	default void setMem(int offset, double... values) {
		setMem(offset, values, 0, values.length);
	}

	default void setMem(float... source) {
		setMem(0, source, 0, source.length);
	}

	default void setMem(double... source) {
		setMem(0, source, 0, source.length);
	}

	default void setMem(double[] source, int srcOffset) {
		setMem(0, source, srcOffset, source.length - srcOffset);
	}

	default void setMem(int offset, float[] source, int srcOffset, int length) {
		if (getDelegate() == null) {
			setMem(getMem(), getOffset() + offset, source, srcOffset, length);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, source, srcOffset, length);
		}
	}

	default void setMem(int offset, double[] source, int srcOffset, int length) {
		if (getDelegate() == null) {
			setMem(getMem(), getOffset() + offset, source, srcOffset, length);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
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

	default void getMem(int sOffset, float out[], int oOffset, int length) {
		if (getDelegate() == null) {
			getMem(getMem(), getOffset() + sOffset, out, oOffset, length);
		} else {
			getDelegate().getMem(getDelegateOffset() + sOffset, out, oOffset, length);
		}
	}

	default void getMem(int sOffset, double out[], int oOffset, int length) {
		if (getDelegate() == null) {
			getMem(getMem(), getOffset() + sOffset, out, oOffset, length);
		} else {
			getDelegate().getMem(getDelegateOffset() + sOffset, out, oOffset, length);
		}
	}

	static void setMem(Memory mem, int offset, float[] source, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, source, srcOffset, length);
	}

	static void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, source, srcOffset, length);
	}

	static void setMem(Memory mem, int offset, MemoryData src, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, src.getMem(), src.getOffset() + srcOffset, length);
	}

	static void getMem(Memory mem, int sOffset, float out[], int oOffset, int length) {
		mem.getProvider().getMem(mem, sOffset, out, oOffset, length);
	}

	static void getMem(Memory mem, int sOffset, double out[], int oOffset, int length) {
		mem.getProvider().getMem(mem, sOffset, out, oOffset, length);
	}
}
