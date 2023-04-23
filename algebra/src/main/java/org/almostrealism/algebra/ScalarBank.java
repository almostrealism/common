/*
 * Copyright 2022 Michael Murray
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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A collection of {@link Scalar}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class ScalarBank extends PackedCollection<Scalar> {
	public ScalarBank(int count) {
		super(new TraversalPolicy(count, 2), 1, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()));
		if (count == 1) {
			System.out.println("WARN: Creating a ScalarBank of a single Scalar");
		}
	}

	// TODO  Need to respect CacheLevel, but the parent constructor that does
	//       respect cache level does this init stuff that we don't want
	public ScalarBank(int count, MemoryData delegate, int delegateOffset) {
		super(new TraversalPolicy(count, 2), 1, delegateSpec ->
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

	// TODO  Add unit tests for this
	public ScalarBank range(int offset, int length) {
		if (offset * getAtomicMemLength() >= getMemLength()) {
			throw new IllegalArgumentException("Range extends beyond the length of this bank");
		}
		return new ScalarBank(length, this, offset * getAtomicMemLength());
	}

	// TODO  Accelerated version
	@Deprecated
	public Scalar sum() {
		return new Scalar(IntStream.range(0, getCount())
				.mapToObj(this::get)
				.mapToDouble(Scalar::getValue)
				.sum());
	}

	// TODO  Accelerated version
	@Deprecated
	public double lengthSq() {
		double data[] = toArray(0, getMemLength());
		return IntStream.range(0, getCount()).map(i -> 2 * i)
				.mapToDouble(i -> data[i])
				.map(v -> v * v)
				.sum();
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

	public static BiFunction<MemoryData, Integer, ScalarBank> postprocessor() {
		return (output, offset) -> new ScalarBank(output.getMemLength() / 2, output, offset);
	}

	public static Collector<Double, ?, ScalarBank> doubleCollector(int total) {
		ScalarBank out = new ScalarBank(total);
		Collector<Double, ?, List<Double>> listCollector = Collectors.toList();

		return new Collector<>() {
			@Override
			public Supplier<Object> supplier() {
				return (Supplier<Object>) listCollector.supplier();
			}

			@Override
			public BiConsumer<Object, Double> accumulator() {
				return (BiConsumer<Object, Double>) listCollector.accumulator();
			}

			@Override
			public BinaryOperator<Object> combiner() {
				return (BinaryOperator<Object>) listCollector.combiner();
			}

			@Override
			public Function<Object, ScalarBank> finisher() {
				return obj -> {
					List l = (List) ((Function) listCollector.finisher()).apply(obj);
					for (int i = 0; i < l.size(); i++) {
						out.set(i, (Double) l.get(i));
					}

					return out;
				};
			}

			@Override
			public Set<Characteristics> characteristics() {
				return listCollector.characteristics();
			}
		};
	}
}