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

import io.almostrealism.code.AdaptEvaluable;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.computations.ScalarBankAdd;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.PassThroughEvaluable;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryBankAdapter;

import java.util.stream.IntStream;

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
	public ScalarBank(int count, MemWrapper delegate, int delegateOffset, CacheLevel cacheLevel) {
		super(2, count, delegateSpec ->
						new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	// TODO  Add unit tests for this
	public ScalarBank range(int offset, int length) {
		if (offset * getAtomicMemLength() >= getMemLength()) {
			throw new IllegalArgumentException("Range extends beyond the length of this bank");
		}
		return new ScalarBank(length, this, offset * getAtomicMemLength(), null);
	}

	// TODO  Accelerated version
	public Scalar sum() {
		return new Scalar(IntStream.range(0, getCount())
				.mapToObj(this::get)
				.mapToDouble(Scalar::getValue)
				.sum());
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