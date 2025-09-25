/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.collect.Collection;
import io.almostrealism.collect.DefaultTraversalOrdering;
import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PackedCollection<T extends MemoryData> extends MemoryDataAdapter
		implements MemoryBank<T>, Collection<T, PackedCollection<T>>, CollectionFeatures, Cloneable {
	private static ContextSpecific<Evaluable<PackedCollection<?>>> clear;

	static {
		// TODO  The use of ContextSpecific as a container for Evaluable
		// TODO  should no longer be required as HardwareEvaluable does
		// TODO  this for every operation where it matters
		clear = new DefaultContextSpecific<>(() ->
				CollectionFeatures.getInstance().multiply(
						Input.value(new TraversalPolicy(false, false, 1), 0),
						CollectionFeatures.getInstance().c(1)).get());
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
		if (shape.getTotalSizeLong() == 0) {
			throw new IllegalArgumentException("Collection must have a non-zero size");
		}

		this.shape = shape.traverse(order).traverse(traversalAxis);
		setDelegate(delegate, delegateOffset);
		init();
	}

	@Override
	protected void init() {
		if (shape.getTotalInputSizeLong() > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException(String.valueOf(shape.getTotalInputSizeLong()));
		}

		super.init();
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

	public PackedCollection<?> get(int index, TraversalPolicy memberShape) {
		return range(memberShape, index * memberShape.getTotalSize());
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

	@Override
	public void setDelegate(MemoryData m, int offset, TraversalOrdering order) {
		if (m instanceof PackedCollection && ((PackedCollection<?>) m).getShape().equals(getShape())) {
			if (getClass() == PackedCollection.class) {
				warn("Creating a collection identical to the delegate");
			} else {
				// Subclasses often have a valid reason for doing this
			}
		}

		super.setDelegate(m, offset, order);
	}

	public DoubleStream doubleStream() {
		return doubleStream(0, getShape().getTotalSize());
	}

	public DoubleStream doubleStream(int offset, int length) {
		if (getDelegateOrdering() == null && getShape().isRegular()) {
			return DoubleStream.of(toArray(offset, length));
		} else {
			return IntStream.range(offset, offset + length).mapToDouble(this::toDouble);
		}
	}

	public double toDouble() {
		if (getShape().getTotalSizeLong() != 1) {
			throw new UnsupportedOperationException();
		}

		return toDouble(0);
	}

	@Override
	public double toDouble(int index) {
		if (getShape().isRegular()) return super.toDouble(index);
		return super.toDouble(getShape().inputIndex(index));
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

	public PackedCollection<T> transpose() {
		if (getShape().getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		PackedCollection result = new PackedCollection<>(
				getShape().length(1),
				getShape().length(0)).traverse(1);
		double data[] = toArray();
		result.setMem(IntStream.range(0, result.getShape().getTotalSize())
				.mapToObj(result.getShape()::position)
				.mapToInt(pos -> getShape().index(pos[1], pos[0]))
				.mapToDouble(i -> data[i])
				.toArray());
		return result;
	}

	public void clear() {
		clear.getValue().into(this.traverseEach()).evaluate(new PackedCollection<>(1));
	}

	public PackedCollection<T> range(TraversalPolicy shape) {
		return range(shape, 0);
	}

	public PackedCollection<T> range(TraversalPolicy shape, int start) {
		int required = shape.getOrder() == null ? shape.getTotalInputSize() :
				shape.getOrder().getLength().orElse(shape.getTotalInputSize());

		if (start + required > getShape().getTotalSize()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		} else if (start == 0 && shape.equals(getShape())) {
			return this;
		}

		if (getDelegate() == null || getDelegateOffset() != 0 || getDelegateOrdering() != null) {
			return new PackedCollection(shape, shape.getTraversalAxis(), this, start);
		} else if (getDelegate() instanceof PackedCollection) {
			return ((PackedCollection<T>) getDelegate()).range(shape, start + getDelegateOffset());
		} else {
			return new PackedCollection<>(shape, shape.getTraversalAxis(), getDelegate(), start);
		}
	}

	/**
	 * Creates a new PackedCollection with this collection's data repeated the specified number of times.
	 * 
	 * <p>This method creates a view of the current collection that appears to contain
	 * the same data repeated multiple times along a new leading dimension. The operation
	 * is memory-efficient as it doesn't actually duplicate the underlying data, but
	 * rather uses a {@link RepeatTraversalOrdering} to map indices appropriately.</p>
	 * 
	 * <h4>Shape Transformation:</h4>
	 * <ul>
	 * <li>Input shape: (d1, d2, ..., dn)</li>
	 * <li>Output shape: (count, d1, d2, ..., dn)</li>
	 * <li>Total size: count × original_size</li>
	 * </ul>
	 * 
	 * <h4>Usage Examples:</h4>
	 * <pre>{@code
	 * // Create a 2x3 collection
	 * PackedCollection<?> original = new PackedCollection<>(shape(2, 3));
	 * original.fill(pos -> pos);  // [0, 1, 2, 3, 4, 5]
	 * 
	 * // Repeat it 4 times
	 * PackedCollection<?> repeated = original.repeat(4);
	 * // Shape: (4, 2, 3)
	 * // Data appears as: [[0,1,2,3,4,5], [0,1,2,3,4,5], [0,1,2,3,4,5], [0,1,2,3,4,5]]
	 * 
	 * // Access repeated data
	 * double value = repeated.valueAt(2, 1, 0);  // Gets original.valueAt(1, 0)
	 * }</pre>
	 * 
	 * <h4>Performance Notes:</h4>
	 * <ul>
	 * <li>Memory efficient - no data duplication</li>
	 * <li>Fast creation - only creates a new view</li>
	 * <li>Access time depends on the underlying traversal ordering</li>
	 * </ul>
	 * 
	 * @param count the number of times to repeat this collection (must be positive)
	 * @return a new PackedCollection representing the repeated data
	 * 
	 * @see RepeatTraversalOrdering
	 * @see TraversalPolicy#prependDimension(int)
	 * @see #range(TraversalPolicy)
	 */
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
		return toDouble(getShape().extentShape().index(pos));
	}

	public void setValueAt(double value, int... pos) {
		setMem(getShape().index(pos), value);
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
		} else if (getShape().equals(shape)) {
			return this;
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

	public void save(File f) throws IOException {
		save(new FileOutputStream(f));
	}

	public void save(OutputStream out) throws IOException {
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
	public void destroy() {
		if (getDelegate() != null && getDelegate().getMemLength() == getMemLength()) {
			// When attempting to destroy a collection which extends over the entire
			// space of its delegate, it is almost certainly expected that the delegate
			// will also be destroyed
			getDelegate().destroy();
			setDelegate(null, 0);
		}

		super.destroy();
	}

	@Override
	public String describe() {
		if (getShape().getTotalSize() == 1) {
			return NumberFormats.formatNumber(toDouble(0));
		} else {
			return getShape().toStringDetail();
		}
	}

	public PackedCollection<T> clone() {
		PackedCollection<T> clone = new PackedCollection<>(getShape(), getShape().getTraversalAxis());
		clone.setMem(0, toArray(0, getMemLength()), 0, getMemLength());
		return clone;
	}

	public static PackedCollection<?> of(List<Double> values) {
		return of(values.stream().mapToDouble(d -> d).toArray());
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

	/**
	 * Creates a {@link DynamicCollectionProducer} that generates blank (zero-filled) collections
	 * with the specified dimensions. This is a convenience method for creating collections
	 * that are initialized to zero values.
	 * 
	 * @param dims The dimensions of the collection to create
	 * @return A DynamicCollectionProducer that generates zero-filled collections with the specified dimensions
	 * 
	 * @see #blank(TraversalPolicy)
	 */
	public static DynamicCollectionProducer blank(int... dims) {
		return blank(new TraversalPolicy(dims));
	}

	/**
	 * Creates a {@link DynamicCollectionProducer} that generates blank (zero-filled) collections
	 * with the specified shape. The producer will create new PackedCollections with the given
	 * shape and default (zero) values each time it is evaluated.
	 * 
	 * <p>This is useful for creating placeholder collections or initializing collections
	 * that will be populated by subsequent operations.</p>
	 * 
	 * @param shape The {@link TraversalPolicy} defining the shape of collections to create
	 * @return A DynamicCollectionProducer that generates zero-filled collections with the specified shape
	 * 
	 * @see DynamicCollectionProducer
	 */
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
