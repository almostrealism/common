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

package org.almostrealism.collect;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.KernelizedOperation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;
import org.almostrealism.hardware.mem.MemoryDataAdapter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PackedCollection<T extends MemoryData> extends MemoryDataAdapter implements MemoryBank<T>, Shape<PackedCollection<T>>, Cloneable {
	private static ContextSpecific<KernelizedOperation> clear;

	static {
		clear = new DefaultContextSpecific<>(() ->
				(KernelizedOperation)
						new Assignment<>(1, new PassThroughProducer(1, 0),
						new PassThroughProducer<>(1, 1)).getKernel());
	}

	private final TraversalPolicy shape;
	private Function<DelegateSpec, T> supply;

	public PackedCollection(int... shape) {
		this(new TraversalPolicy(shape));
	}

	public PackedCollection(TraversalPolicy shape) {
		this(shape, shape.getTraversalAxis(), null, 0);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis) {
		this(shape, traversalAxis, null);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, Function<DelegateSpec, T> supply) {
		this(shape, traversalAxis, supply, null, 0);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, Function<DelegateSpec, T> supply, MemoryData delegate, int delegateOffset) {
		this(shape, traversalAxis, delegate, delegateOffset);
		this.supply = supply;
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, MemoryData delegate, int delegateOffset) {
		this.shape = shape.traverse(traversalAxis);
		setDelegate(delegate, delegateOffset);
		init();
	}

	@Override
	public T get(int index) {
		if (shape.getTraversalAxis() == 1 && supply != null) {
			return supply.apply(new DelegateSpec(this, shape.size(1) * index));
		} else {
			return (T) new PackedCollection(
					shape.subset(shape.getTraversalAxis()), shape.getTraversalAxis(),
					null, this, shape.getSize() * index);
		}
	}

	@Override
	public void set(int index, T value) {
		set(index, value.toArray(0, value.getMemLength()));
	}

	public void set(int index, double... values) {
		setMem(index * getAtomicMemLength(), values, 0, values.length);
	}

	public void copyFrom(PackedCollection<T> source) {
		if (source.getShape().getTotalSize() != getShape().getTotalSize()) {
			throw new UnsupportedOperationException();
		}

		setMem(0, source, 0, source.getMemLength());
	}

	@Override
	public int getCount() {
		return shape.getCount();
	}

	@Override
	public int getMemLength() {
		return shape.getTotalSize();
	}

	@Override
	public int getAtomicMemLength() {
		return shape.getSize();
	}

	public TraversalPolicy getShape() { return shape; }

	public Stream<T> stream() {
		return IntStream.range(0, getCount()).mapToObj(this::get);
	}

	public void fill(Function<int[], Double> f) {
		getShape().stream().forEach(pos -> setMem(getShape().index(pos), f.apply(pos)));
	}

	public void forEach(Consumer<T> consumer) {
		stream().forEach(consumer);
	}

	public void clear() {
		clear.getValue().kernelOperate(this.traverseEach(), new PackedCollection(1));
	}

	public PackedCollection<T> range(TraversalPolicy shape) {
		return range(shape, 0);
	}

	public PackedCollection<T> range(TraversalPolicy shape, int start) {
		if (start + shape.getTotalSize() > getShape().getTotalSize()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		return new PackedCollection(shape, shape.getTraversalAxis(), this, start);
	}

	public PackedCollection<T> value(int pos) {
		return range(new TraversalPolicy(1), pos);
	}

	public double valueAt(int... pos) {
		return toDouble(getShape().index(pos));
	}

	// TODO  Accelerated version
	@Deprecated
	public double lengthSq() {
		double data[] = toArray(0, getMemLength());
		return IntStream.range(0, getMemLength())
				.mapToDouble(i -> data[i])
				.map(v -> v * v)
				.sum();
	}

	// TODO  Accelerated version
	@Deprecated
	public double length() {
		return Math.sqrt(lengthSq());
	}

	@Override
	public PackedCollection reshape(TraversalPolicy shape) {
		if (shape.getTotalSize() != getMemLength()) {
			throw new IllegalArgumentException("Shape size (" + shape.getSize() +
					") does not match collection size (" + getMemLength() + ")");
		}

		int axis = shape.getTraversalAxis();

		if (axis <= 0) {
			return new PackedCollection(shape, axis, this, 0);
		} else if (axis == 1) {
			return new PackedCollection<>(shape, axis,
					delegateSpec ->
							(T) new PackedCollection<>(shape.subset(1), 0,
									delegateSpec.getDelegate(), delegateSpec.getOffset()),
					this, 0);
		} else {
			// TODO  This could also provide the supply argument, so that PackedCollection::get is supported
			// TODO  it's just a little more complicated to implement
			return new PackedCollection(shape, axis, this, 0);
		}
	}

	public <T extends MemoryData> Stream<T> extract(IntFunction<T> factory) {
		AtomicInteger idx = new AtomicInteger();

		return Stream.generate(() -> {
			T v = factory.apply(getAtomicMemLength() / getShape().size(getShape().getTraversalAxis() + 1));
			if (v.getMemLength() != getAtomicMemLength()) {
				throw new UnsupportedOperationException("Cannot extract entries of length " + getAtomicMemLength() +
														" into memory of length " + v.getMemLength());
			}
			// TODO  Reassigning the delegate after the MemoryData is constructed
			// TODO  results in memory being unnecessarily allocated and then left
			// TODO  unused. MemoryDataAdapter::init should be lazily invoked so
			// TODO  memory is not allocated until it is known to be needed
			v.setDelegate(this, idx.getAndIncrement() * getAtomicMemLength());
			return v;
		}).limit(getMemLength() / getAtomicMemLength());
	}

	public PackedCollection<?> delegate(int offset, int length) {
		return new PackedCollection(new TraversalPolicy(length), 0, this, offset);
	}

	public PackedCollection<T> clone() {
		PackedCollection<T> clone = new PackedCollection<>(getShape(), getShape().getTraversalAxis());
		clone.setMem(0, toArray(0, getMemLength()), 0, getMemLength());
		return clone;
	}

	public static PackedCollection<?> range(MemoryData data, TraversalPolicy shape, int start) {
		if (start + shape.getTotalSize() > data.getMemLength()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		return new PackedCollection(shape, shape.getTraversalAxis(), data, start);
	}

	public static DynamicCollectionProducer blank(int... dims) {
		return blank(new TraversalPolicy(dims));
	}

	public static DynamicCollectionProducer blank(TraversalPolicy shape) {
		return new DynamicCollectionProducer(shape, args -> new PackedCollection(shape));
	}

	public static <T extends MemoryData> IntFunction<MemoryBank<PackedCollection<?>>> bank(TraversalPolicy atomicShape) {
		return len -> new PackedCollection(atomicShape.prependDimension(len));
	}

	public static <T extends MemoryData> BiFunction<Integer, Integer, MemoryBank<T>> table(TraversalPolicy atomicShape) {
		return (width, count) -> new PackedCollection<>(atomicShape.prependDimension(width).prependDimension(count));
	}

	public static <T extends MemoryData> BiFunction<Integer, Integer, MemoryBank<T>> table(TraversalPolicy atomicShape, BiFunction<DelegateSpec, Integer, T> supply) {
		return (width, count) -> new PackedCollection<>(atomicShape.prependDimension(width).prependDimension(count), 1, delegateSpec -> supply.apply(delegateSpec, width));
	}

	public static class DelegateSpec {
		private MemoryData data;
		private int offset;

		public DelegateSpec(MemoryData data, int offset) {
			this.data = data;
			setOffset(offset);
		}

		public MemoryData getDelegate() { return data; }

		public int getOffset() { return offset; }
		public void setOffset(int offset) { this.offset = offset; }
	}
}
