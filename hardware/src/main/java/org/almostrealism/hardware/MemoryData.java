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

package org.almostrealism.hardware;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public interface MemoryData extends TraversableExpression<Double>, Delegated<MemoryData>, Destroyable, Node {

	Memory getMem();

	default void reallocate(MemoryProvider<?> provider) {
		reassign(provider.reallocate(getMem(), getOffset(), getMemLength()));
	}

	void reassign(Memory mem);

	default boolean isDestroyed() {
		return getMem() == null;
	}

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
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			return getDelegateOffset() + getDelegate().getOffset();
		}
	}

	int getMemLength();

	default int getAtomicMemLength() {
		return getMemLength();
	}

	default int getDelegatedLength() {
		if (getDelegateOrdering() == null) {
			return getMemLength();
		} else {
			return getDelegateOrdering().getLength().orElse(getMemLength());
		}
	}

	/**
	 * If a delegate is set using this method, then the {@link Memory} for the delegate
	 * should be used to store and retrieve data, with the specified offset. The offset
	 * size is based on the size of a double, it indicates the number of double values
	 * to skip over to get to the location in the {@link Memory} where data should be
	 * kept.
	 */
	default void setDelegate(MemoryData m, int offset) {
		if (m == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		}

		setDelegate(m, offset, null);
	}

	/**
	 * If a delegate is set using this method, then the {@link Memory} for the delegate
	 * should be used to store and retrieve data, with the specified offset. The offset
	 * size is based on the size of a double, it indicates the number of double values
	 * to skip over to get to the location in the {@link Memory} where data should be
	 * kept.
	 */
	void setDelegate(MemoryData m, int offset, TraversalOrdering order);

	@Override
	MemoryData getDelegate();

	int getDelegateOffset();

	TraversalOrdering getDelegateOrdering();

	default TraversalOrdering getMemOrdering() {
		if (getDelegate() == null) {
			return getDelegateOrdering();
		} else if (getDelegateOrdering() == null) {
			return getDelegate().getMemOrdering();
		} else {
			return getDelegateOrdering().compose(getDelegate().getMemOrdering());
		}
	}

	default DoubleStream doubleStream() {
		return doubleStream(0, getMemLength());
	}

	default DoubleStream doubleStream(int offset, int length) {
		if (getDelegateOrdering() == null) {
			return DoubleStream.of(toArray(offset, length));
		} else {
			return IntStream.range(offset, offset + length).mapToDouble(this::toDouble);
		}
	}

	default double toDouble(int index) {
		if (getMemOrdering() == null) {
			return index < 0 ? 0.0 : toArray(index, 1)[0];
		} else if (getDelegate() == null) {
			index = getMemOrdering().indexOf(index);
			if (index < 0) return 0.0;

			return getMem().toArray(getOffset() + index, 1)[0];
		} else {
			index = getDelegateOrdering().indexOf(index);
			if (index < 0) return 0.0;

			return getDelegate().toDouble(getDelegateOffset() + getDelegateOrdering().indexOf(index));
		}
	}

	default double[] toArray(int offset, int length) {
		if (offset + length > getMemLength()) {
			throw new IllegalArgumentException("Array extends beyond the length of this MemoryData");
		}

		if (getMemOrdering() == null) {
			return getMem().toArray(getOffset() + offset, length);
		} else {
			return doubleStream(offset, length).toArray();
		}
	}

	default double[] toArray() {
		return toArray(0, getMemLength());
	}

	default String toArrayString(int offset, int length) {
		return Arrays.toString(toArray(offset, length));
	}

	default String toArrayString() {
		return Arrays.toString(toArray());
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

		if (getMem() == null) {
			throw new UnsupportedOperationException();
		} else if (getMem().getProvider().getNumberSize() == 8) {
			double out[] = new double[1];
			getMem(i, out, 0, 1);
			return new DoubleConstant(out[0]);
		} else {
			float out[] = new float[1];
			getMem(i, out, 0, 1);
			return new DoubleConstant((double) out[0]);
		}
	}

	@Override
	default Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
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
			if (offset + length > getMemLength()) {
				throw new IllegalArgumentException("Array extends beyond the length of this MemoryData");
			}

			setMem(getMem(), getOffset() + offset, source, srcOffset, length);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, source, srcOffset, length);
		}
	}

	default void setMem(int offset, MemoryData src) {
		setMem(offset, src, 0, src.getMemLength());
	}

	default void setMem(int offset, MemoryData src, int srcOffset, int length) {
		if (src.getMemLength() < srcOffset + length) {
			throw new IllegalArgumentException("Source MemoryData is not long enough to provide the requested data");
		} else if (offset + length > getMemLength()) {
			throw new IllegalArgumentException("MemoryData region extends beyond the length of this MemoryData");
		}

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
