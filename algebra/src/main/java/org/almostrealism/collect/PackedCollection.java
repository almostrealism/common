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

package org.almostrealism.collect;

import io.almostrealism.collect.DefaultTraversalOrdering;
import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversalOrdering;
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
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.mem.Heap;
import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.hardware.mem.MemoryDataCopy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PackedCollection<T extends MemoryData> extends MemoryDataAdapter
		implements MemoryBank<T>, Shape<PackedCollection<T>>, CollectionFeatures, Cloneable {
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

	public PackedCollection(PackedCollection<T> src) {
		this(src.getShape(), src.getShape().getTraversalAxis(), src.supply);
		new MemoryDataCopy("PackedCollection constructor", src, this).get().run();
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
		this(shape, traversalAxis, delegate, delegateOffset, null);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, MemoryData delegate, int delegateOffset, TraversalOrdering order) {
		this.shape = shape.traverse(order).traverse(traversalAxis);
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
		if (index * getAtomicMemLength() + values.length > getMemLength()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		setMem(index * getAtomicMemLength(), values, 0, values.length);
	}

	public void copyFrom(PackedCollection<T> source) {
		if (source.getShape().getTotalSize() != getShape().getTotalSize()) {
			throw new UnsupportedOperationException();
		}

		setMem(0, source, 0, source.getMemLength());
	}

	@Override
	public long getCountLong() {
		return shape.getCountLong();
	}

	@Override
	public int getMemLength() {
		return shape.getTotalInputSize();
	}

	@Override
	public int getAtomicMemLength() {
		return shape.getInputSize();
	}

	public TraversalPolicy getShape() { return shape; }

	@Override
	public TraversalOrdering getDelegateOrdering() {
		if (getShape().getOrder() == null) return null;
		return getShape().getOrder().getLength().stream()
				.mapToObj(DefaultTraversalOrdering::new)
				.findFirst()
				.orElseGet(DefaultTraversalOrdering::new);
	}

	public double toDouble() {
		if (getShape().getTotalSizeLong() != 1) {
			throw new UnsupportedOperationException();
		}

		return toDouble(0);
	}

	@Override
	public double toDouble(int index) {
		if (getShape().getOrder() == null) return super.toDouble(index);
		return super.toDouble(getShape().getOrder().indexOf(index));
	}

	public Stream<T> stream() {
		return IntStream.range(0, getCount()).mapToObj(this::get);
	}

	public Stream<String> stringStream() {
		int colWidth = getShape().getSize();
		return IntStream.range(0, getCount()).mapToObj(r -> toArrayString(r * colWidth, colWidth));
	}

	public void print(Consumer<String> out) {
		stringStream().forEach(out);
	}

	public void print() {
		print(System.out::println);
	}

	public PackedCollection<T> fill(double... value) {
		double data[] = IntStream.range(0, getMemLength()).mapToDouble(i -> value[i % value.length]).toArray();
		setMem(0, data);
		return this;
	}

	public PackedCollection<T> fill(DoubleSupplier values) {
		double data[] = IntStream.range(0, getMemLength()).mapToDouble(i -> values.getAsDouble()).toArray();
		setMem(0, data);
		return this;
	}

	public PackedCollection<T> fill(Function<int[], Double> f) {
		double data[] = new double[getMemLength()];
		getShape().stream().forEach(pos -> data[getShape().index(pos)] = f.apply(pos));
		setMem(0, data);
		return this;
	}

	public PackedCollection<?> replace(DoubleUnaryOperator f) {
		double in[] = toArray(0, getMemLength());
		double data[] = IntStream.range(0, getMemLength()).mapToDouble(i -> f.applyAsDouble(in[i])).toArray();
		setMem(0, data);
		return this;
	}

	public PackedCollection<?> identityFill() {
		return fill(pos -> {
			for (int i = 0; i < pos.length; i++) {
				if (pos[i] != pos[0]) {
					return 0.0;
				}
			}

			return 1.0;
		});
	}

	public PackedCollection<T> randFill() {
		rand(getShape()).get().into(this).evaluate();
		return this;
	}

	public PackedCollection<T> randnFill() {
		randn(getShape()).get().into(this).evaluate();
		return this;
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
		int required = shape.getOrder() == null ? shape.getTotalInputSize() :
				shape.getOrder().getLength().orElse(shape.getTotalInputSize());

		if (start + required > getShape().getTotalSize()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		if (getDelegate() == null || getDelegateOffset() != 0 || getDelegateOrdering() != null) {
			return new PackedCollection(shape, shape.getTraversalAxis(), this, start);
		} else {
			return new PackedCollection<>(shape, shape.getTraversalAxis(), getDelegate(), start);
		}
	}

	public PackedCollection<T> repeat(int count) {
		int len = getShape().getTotalSize();
		TraversalPolicy shape = getShape().prependDimension(count)
				.withOrder(new RepeatTraversalOrdering(len));
		return range(shape);
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

	public int argmax() {
		return IntStream.range(0, getMemLength())
				.reduce((a, b) -> toDouble(a) > toDouble(b) ? a : b)
				.orElse(-1);
	}

	@Override
	public PackedCollection<T> traverse(int axis) {
		return reshape(getShape().traverse(axis));
	}

	@Override
	public PackedCollection<T> reshape(TraversalPolicy shape) {
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

	public void store(File f) throws IOException {
		store(new FileOutputStream(f));
	}

	public void store(OutputStream out) throws IOException {
		try (DataOutputStream dos = new DataOutputStream(out)) {
			getShape().store(dos);

			try {
				doubleStream().forEach(d -> {
					try {
						dos.writeDouble(d);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
			} catch (RuntimeException e) {
				if (e.getCause() instanceof IOException) {
					throw (IOException) e.getCause();
				} else {
					throw e;
				}
			}

			dos.flush();
		}
	}

	@Override
	public String describe() {
		if (getShape().getTotalSize() == 1) {
			return getShape() + " " + toDouble(0);
		} else {
			return getShape().toStringDetail();
		}
	}

	public PackedCollection<T> clone() {
		PackedCollection<T> clone = new PackedCollection<>(getShape(), getShape().getTraversalAxis());
		clone.setMem(0, toArray(0, getMemLength()), 0, getMemLength());
		return clone;
	}

	public static PackedCollection<?> of(double... values) {
		PackedCollection<?> collection = factory().apply(values.length);
		collection.setMem(0, values, 0, values.length);
		return collection;
	}

	public static IntFunction<PackedCollection<?>> factory() {
		Heap heap = Heap.getDefault();
		return heap == null ? PackedCollection::new : factory(heap::allocate);
	}

	public static IntFunction<PackedCollection<?>> factory(IntFunction<Bytes> allocator) {
		return len -> {
			Bytes data = allocator.apply(len);
			return new PackedCollection<>(new TraversalPolicy(len), 0, data.getDelegate(), data.getDelegateOffset());
		};
	}

	public static PackedCollection<?> range(MemoryData data, TraversalPolicy shape, int start) {
		if (start + shape.getTotalSize() > data.getMemLength()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		return new PackedCollection(shape, shape.getTraversalAxis(), data, start);
	}

	public static Iterable<PackedCollection<?>> loadCollections(File src) {
		try {
			return loadCollections(new FileInputStream(src));
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	public static Iterable<PackedCollection<?>> loadCollections(InputStream in) {
		DataInputStream dis = new DataInputStream(in);

		return () -> new Iterator<>() {
			@Override
			public boolean hasNext() {
				try {
					if (dis.available() <= 0) {
						dis.close();
						return false;
					}

					return true;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			@Override
			public PackedCollection next() {
				try {
					TraversalPolicy shape = TraversalPolicy.load(dis);
					PackedCollection<?> collection = new PackedCollection<>(shape);
					double input[] = new double[shape.getTotalSize()];

					for (int i = 0; i < shape.getTotalSize(); i++) {
						input[i] = dis.readDouble();
					}

					collection.setMem(0, input, 0, input.length);
					return collection;
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		};
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
