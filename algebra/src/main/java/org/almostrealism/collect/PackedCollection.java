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
import org.almostrealism.collect.PackedCollection;

import io.almostrealism.collect.Collection;
import io.almostrealism.collect.DefaultTraversalOrdering;
import io.almostrealism.collect.RepeatTraversalOrdering;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.util.NumberFormats;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
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
import java.util.Random;
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

/**
 * A hardware-accelerated multi-dimensional array backed by contiguous memory.
 *
 * <p>
 * {@link PackedCollection} is the fundamental data container in the Almost Realism framework,
 * providing efficient storage and access for multi-dimensional numerical data. It combines:
 * <ul>
 *   <li><b>Memory efficiency:</b> Contiguous packed memory layout for cache-friendly access</li>
 *   <li><b>Hardware acceleration:</b> Direct GPU/CPU memory backing via {@link MemoryData}</li>
 *   <li><b>Multi-dimensional support:</b> Arbitrary rank tensors with {@link TraversalPolicy}</li>
 *   <li><b>Flexible traversal:</b> Custom access patterns via {@link TraversalOrdering}</li>
 *   <li><b>Type safety:</b> Generic type parameter for specialized memory data types</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 * <p>
 * Data is stored in row-major order (C-style) in a contiguous memory block. For a 3x4 matrix:
 * </p>
 * <pre>
 * Logical:      Memory:
 * [0  1  2  3]  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
 * [4  5  6  7]
 * [8  9 10 11]
 * </pre>
 *
 * <h2>Construction Patterns</h2>
 * <pre>{@code
 * // 1. Simple shape construction
 * PackedCollection tensor = new PackedCollection(3, 4, 5);  // 3x4x5 tensor
 *
 * // 2. With traversal policy
 * TraversalPolicy shape = new TraversalPolicy(10, 20);
 * PackedCollection matrix = new PackedCollection(shape);
 *
 * // 3. Copy constructor
 * PackedCollection copy = new PackedCollection(original);
 *
 * // 4. With existing memory delegate
 * MemoryData memory = ...;
 * PackedCollection view = new PackedCollection(shape, 0, memory, 0);
 * }</pre>
 *
 * <h2>Access Patterns</h2>
 * <pre>{@code
 * PackedCollection data = new PackedCollection(10, 5);
 *
 * // Indexed access
 * data.setMem(0, 1.5);
 * double value = data.toDouble(0);
 *
 * // Array access
 * data.set(0, new double[]{1.0, 2.0, 3.0, 4.0, 5.0});
 * double[] row = data.get(0).toArray();
 *
 * // Streaming
 * double sum = data.doubleStream().sum();
 * data.forEach(row -> System.out.println(row));
 * }</pre>
 *
 * <h2>Initialization Methods</h2>
 * <pre>{@code
 * PackedCollection data = new PackedCollection(100);
 *
 * data.fill(0.0);                      // Fill with constant
 * data.randFill();                     // Fill with uniform random [0,1)
 * data.randnFill();                    // Fill with normal distribution
 * data.identityFill();                 // Fill as identity matrix (2D only)
 * data.fill(pos -> pos[0] * pos[1]);   // Fill with function
 * data.replace(x -> x * 2.0);          // Transform in-place
 * }</pre>
 *
 * <h2>Shape and Traversal</h2>
 * <p>
 * The {@link TraversalPolicy} defines the logical shape and how elements are accessed:
 * </p>
 * <pre>{@code
 * PackedCollection data = new PackedCollection(3, 4);
 * TraversalPolicy shape = data.getShape();
 *
 * shape.getDimensions();     // 2
 * shape.length(0);           // 3
 * shape.length(1);           // 4
 * shape.getTotalSize();      // 12
 * shape.getTraversalAxis();  // 0 (traverse rows)
 * }</pre>
 *
 * <h2>Memory Delegation</h2>
 * <p>
 * {@link PackedCollection} can wrap existing {@link MemoryData} without copying:
 * </p>
 * <pre>{@code
 * // Create a view into existing memory
 * MemoryData largeBuffer = ...;
 * PackedCollection subset = new PackedCollection(
 *     shape(10, 10),
 *     0,              // traversal axis
 *     largeBuffer,
 *     100             // offset into buffer
 * );
 *
 * // Changes to subset modify largeBuffer
 * subset.setMem(0, 5.0);
 * }</pre>
 *
 * <h2>I/O Operations</h2>
 * <pre>{@code
 * PackedCollection data = ...;
 *
 * // Save to file
 * data.save(new File("tensor.dat"));
 *
 * // Load from file
 * PackedCollection loaded = PackedCollection.load(new File("tensor.dat"));
 *
 * // Print for debugging
 * data.print();  // Pretty-printed to console
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * {@link PackedCollection} is <b>not thread-safe</b>. External synchronization is required
 * for concurrent access. For parallel operations, use the computation framework which handles
 * synchronization internally.
 * </p>
 *
 * @author  Michael Murray
 * @see TraversalPolicy
 * @see MemoryData
 * @see CollectionProducer
 * @see CollectionFeatures
 */
public class PackedCollection extends MemoryDataAdapter
		implements MemoryBank<PackedCollection>, Collection<PackedCollection, PackedCollection>, CollectionFeatures, Cloneable {
	private static Evaluable<PackedCollection> clear;

	static {
		clear = CollectionFeatures.getInstance().zeros(new TraversalPolicy(false, false, 1)).get();
	}

	private final TraversalPolicy shape;
	private Function<DelegateSpec, PackedCollection> supply;

	public PackedCollection(int... shape) {
		this(new TraversalPolicy(shape));
	}

	public PackedCollection(TraversalPolicy shape) {
		this(shape, shape.getTraversalAxis(), null, 0);
	}

	public PackedCollection(PackedCollection src) {
		this(src.getShape(), src.getShape().getTraversalAxis(), src.supply);
		new MemoryDataCopy("PackedCollection constructor", src, this).get().run();
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis) {
		this(shape, traversalAxis, null);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, Function<DelegateSpec, PackedCollection> supply) {
		this(shape, traversalAxis, supply, null, 0);
	}

	public PackedCollection(TraversalPolicy shape, int traversalAxis, Function<DelegateSpec, PackedCollection> supply, MemoryData delegate, int delegateOffset) {
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
	public PackedCollection get(int index) {
		if (shape.getTraversalAxis() == 1 && supply != null) {
			return supply.apply(new DelegateSpec(this, shape.size(1) * index));
		} else {
			return new PackedCollection(
					shape.subset(shape.getTraversalAxis()), shape.getTraversalAxis(),
					null, this, shape.getSize() * index);
		}
	}

	public PackedCollection get(int index, TraversalPolicy memberShape) {
		return range(memberShape, index * memberShape.getTotalSize());
	}

	@Override
	public void set(int index, PackedCollection value) {
		set(index, value.toArray(0, value.getMemLength()));
	}

	public void set(int index, double... values) {
		if (index * getAtomicMemLength() + values.length > getMemLength()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		setMem(index * getAtomicMemLength(), values, 0, values.length);
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
		if (m instanceof PackedCollection && ((PackedCollection) m).getShape().equals(getShape())) {
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

	public Stream<PackedCollection> stream() {
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

	public PackedCollection fill(double... value) {
		double data[] = IntStream.range(0, getMemLength()).mapToDouble(i -> value[i % value.length]).toArray();
		setMem(0, data);
		return this;
	}

	public PackedCollection fill(DoubleSupplier values) {
		double data[] = IntStream.range(0, getMemLength()).mapToDouble(i -> values.getAsDouble()).toArray();
		setMem(0, data);
		return this;
	}

	public PackedCollection fill(Function<int[], Double> f) {
		double data[] = new double[getMemLength()];
		getShape().stream().forEach(pos -> data[getShape().index(pos)] = f.apply(pos));
		setMem(0, data);
		return this;
	}

	public PackedCollection replace(DoubleUnaryOperator f) {
		double in[] = toArray(0, getMemLength());
		double data[] = IntStream.range(0, getMemLength()).mapToDouble(i -> f.applyAsDouble(in[i])).toArray();
		setMem(0, data);
		return this;
	}

	public PackedCollection identityFill() {
		return fill(pos -> {
			for (int i = 0; i < pos.length; i++) {
				if (pos[i] != pos[0]) {
					return 0.0;
				}
			}

			return 1.0;
		});
	}

	public PackedCollection randFill() {
		rand(getShape()).get().into(this).evaluate();
		return this;
	}

	public PackedCollection randFill(Random source) {
		rand(getShape(), source).get().into(this).evaluate();
		return this;
	}

	public PackedCollection randnFill() {
		randn(getShape()).get().into(this).evaluate();
		return this;
	}

	public PackedCollection randnFill(Random source) {
		randn(getShape(), source).get().into(this).evaluate();
		return this;
	}

	public void forEach(Consumer<PackedCollection> consumer) {
		stream().forEach(consumer);
	}

	public PackedCollection transpose() {
		if (getShape().getDimensions() != 2) {
			throw new IllegalArgumentException();
		}

		PackedCollection result = new PackedCollection(
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
		clear.into(this.traverseEach()).evaluate();
	}

	public PackedCollection range(TraversalPolicy shape) {
		return range(shape, 0);
	}

	public PackedCollection range(TraversalPolicy shape, int start) {
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
			return ((PackedCollection) getDelegate()).range(shape, start + getDelegateOffset());
		} else {
			return new PackedCollection(shape, shape.getTraversalAxis(), getDelegate(), start);
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
	 * <li>Total size: count * original_size</li>
	 * </ul>
	 * 
	 * <h4>Usage Examples:</h4>
	 * <pre>{@code
	 * // Create a 2x3 collection
	 * PackedCollection original = new PackedCollection(shape(2, 3));
	 * original.fill(pos -> pos);  // [0, 1, 2, 3, 4, 5]
	 * 
	 * // Repeat it 4 times
	 * PackedCollection repeated = original.repeat(4);
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
	public PackedCollection repeat(int count) {
		int len = getShape().getTotalSize();
		TraversalPolicy shape = getShape().prependDimension(count)
				.withOrder(new RepeatTraversalOrdering(len));
		return range(shape);
	}

	public PackedCollection value(int pos) {
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
	public PackedCollection traverse(int axis) {
		return reshape(getShape().traverse(axis));
	}

	@Override
	public PackedCollection reshape(TraversalPolicy shape) {
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
			return new PackedCollection(shape, axis,
					delegateSpec ->
							new PackedCollection(shape.subset(1), 0,
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

	public PackedCollection delegate(int offset, int length) {
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

	public PackedCollection clone() {
		PackedCollection clone = new PackedCollection(getShape(), getShape().getTraversalAxis());
		clone.setMem(0, toArray(0, getMemLength()), 0, getMemLength());
		return clone;
	}

	public static PackedCollection of(List<Double> values) {
		return of(values.stream().mapToDouble(d -> d).toArray());
	}

	public static PackedCollection of(double... values) {
		PackedCollection collection = factory().apply(values.length);
		collection.setMem(0, values, 0, values.length);
		return collection;
	}

	public static IntFunction<PackedCollection> factory() {
		Heap heap = Heap.getDefault();
		return heap == null ? PackedCollection::new : factory(heap::allocate);
	}

	public static IntFunction<PackedCollection> factory(IntFunction<Bytes> allocator) {
		return len -> {
			Bytes data = allocator.apply(len);
			return new PackedCollection(new TraversalPolicy(len), 0, data.getDelegate(), data.getDelegateOffset());
		};
	}

	public static PackedCollection range(MemoryData data, TraversalPolicy shape, int start) {
		if (start + shape.getTotalSize() > data.getMemLength()) {
			throw new IllegalArgumentException("Range exceeds collection size");
		}

		return new PackedCollection(shape, shape.getTraversalAxis(), data, start);
	}

	public static Iterable<PackedCollection> loadCollections(File src) {
		try {
			return loadCollections(new FileInputStream(src));
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	public static Iterable<PackedCollection> loadCollections(InputStream in) {
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
					PackedCollection collection = new PackedCollection(shape);
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

	public static IntFunction<MemoryBank<PackedCollection>> bank(TraversalPolicy atomicShape) {
		return len -> new PackedCollection(atomicShape.prependDimension(len));
	}

	public static BiFunction<Integer, Integer, MemoryBank<PackedCollection>> table(TraversalPolicy atomicShape) {
		return (width, count) -> new PackedCollection(atomicShape.prependDimension(width).prependDimension(count));
	}

	public static BiFunction<Integer, Integer, MemoryBank<PackedCollection>> table(TraversalPolicy atomicShape, BiFunction<DelegateSpec, Integer, PackedCollection> supply) {
		return (width, count) -> new PackedCollection(atomicShape.prependDimension(width).prependDimension(count), 1, delegateSpec -> supply.apply(delegateSpec, width));
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
