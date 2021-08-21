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

import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryBankAdapter;

import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A collection of {@link Scalar}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class ScalarBank extends MemoryBankAdapter<Scalar> {
	public ScalarBank(int count) {
		super(2, count, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public ScalarBank(int count, CacheLevel cacheLevel) {
		super(2, count, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()), cacheLevel);
	}

	// TODO  Need to respect CacheLevel, but the parent constructor that does
	//       respect cache level does this init stuff that we don't want
	public ScalarBank(int count, MemoryData delegate, int delegateOffset, CacheLevel cacheLevel) {
		super(2, count, delegateSpec ->
						new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public void setZero() {
		int size = getCount();
		for (int i = 0; i < size; i++) set(i, 0.0, 1.0);
	}

	// TODO  Accelerated version
	public void addMatVec(ScalarTable matrix, ScalarBank vector) {
		int m = matrix.getCount();
		int n = matrix.getWidth();
		assert n == vector.getCount();

		for (int i = 0; i < m; i++) {
			double v = 0;

			for (int j = 0; j < n; j++) {
				v += matrix.get(i, j).getValue() * vector.get(j).getValue();
			}

			set(i, v, 1.0);
		}
	}

	public void forEach(Consumer<Scalar> consumer) {
		stream().forEach(consumer);
	}

	public Stream<Scalar> stream() {
		return IntStream.range(0, getCount()).mapToObj(this::get);
	}

	// TODO  Add unit tests for this
	public ScalarBank range(int offset, int length) {
		if (offset * getAtomicMemLength() >= getMemLength()) {
			throw new IllegalArgumentException("Range extends beyond the length of this bank");
		}
		return new ScalarBank(length, this, offset * getAtomicMemLength(), null);
	}

	// TODO  Accelerated version
	@Deprecated
	public Scalar sum() {
		return new Scalar(IntStream.range(0, getCount())
				.mapToObj(this::get)
				.mapToDouble(Scalar::getValue)
				.sum());
	}

	@Deprecated
	public void mulElements(ScalarBank vals) {
		int size = getCount();
		assert size == vals.getCount();

		IntStream.range(0, size)
				.forEach(i -> set(i, get(i).getValue() * vals.get(i).getValue(), get(i).getCertainty() * vals.get(i).getCertainty()));
	}

	// TODO  Use cl_mem copy
	public void copyFromVec(ScalarBank bank) {
		assert getCount() == bank.getCount();

		for (int i = 0; i < getCount(); i++)
			set(i, bank.get(i));
	}

	public void copyColFromMat(Tensor<Scalar> mat, int col) {
		assert col < mat.length(0);
		assert getCount() == mat.length();
		for (int i = 0; i < getCount(); i++) set(i, mat.get(i, col));
	}

	public void applyFloor(double floor) {
		for (int i = 0; i < getCount(); i++) {
			double v = get(i).getValue();
			if (v < floor) set(i, floor);
		}
	}

	public void applyLog() {
		for (int i = 0; i < getCount(); i++) {
			set(i, Math.log(get(i).getValue()));
		}
	}
}