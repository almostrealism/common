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

package io.almostrealism.collect;

import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Countable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TraversalPolicy implements Traversable<TraversalPolicy>, Countable {
	public static boolean enableStrictSizes = true;
	public static boolean enableDivisibleSizes = true;

	public static long MAX_SIZE = Long.MAX_VALUE / Precision.FP64.bytes();

	private TraversalOrdering order;
	private int dims[];
	private int traversalAxis;

	public TraversalPolicy(int... dims) {
		this(null, dims);
	}

	public TraversalPolicy(boolean tolerateZero, int... dims) {
		this(null, tolerateZero, dims);
	}

	public TraversalPolicy(TraversalOrdering order, int... dims) {
		this(order, false, dims);
	}

	public TraversalPolicy(TraversalOrdering order, boolean tolerateZero, int... dims) {
		this.order = order;
		this.dims = dims;

		if (dims.length > 0) {
			long total = dims[0];

			for (int i = 1; i < dims.length; i++) {
				total *= dims[i];

				if (total > MAX_SIZE || total < 0 || (!tolerateZero && total == 0)) {
					throw new IllegalArgumentException();
				}
			}
		}
	}

	public TraversalOrdering getOrder() { return order; }

	public int size(int depth) {
		return Math.toIntExact(sizeLong(depth));
	}

	public long sizeLong(int depth) {
		if (dims.length == 0) return 0;
		if (depth == dims.length) return 1;
		if (depth > dims.length) throw new IllegalArgumentException("Depth is greater than the number of dimensions");
		return IntStream.range(depth, dims.length).mapToLong(i -> dims[i]).reduce((x, y) -> x * y).getAsLong();
	}

	public int length(int axis) {
		return dims[axis];
	}

	public int index(int... pos) {
		int index = 0;
		for (int i = 0; i < pos.length; i++) {
			index += pos[i] * size(i + 1);
		}
		return index;
	}

	public Expression index(Expression... pos) {
		Expression index = new IntegerConstant(0);

		for (int i = 0; i < pos.length; i++) {
			Expression s = new IntegerConstant(size(i + 1));
			index = Sum.of(index, Product.of(pos[i], s));
		}

		return index;
	}

	public int[] position(int index) {
		int pos[] = new int[getDimensions()];

		int remaining = index;
		for (int i = 0; i < pos.length; i++) {
			int s = size(i + 1);
			pos[i] = remaining / s;
			remaining = remaining - pos[i] * s;
		}

		return pos;
	}

	public Expression[] position(Expression index) {
		Expression pos[] = new Expression[getDimensions()];

		Expression remaining = index;
		for (int i = 0; i < pos.length; i++) {
			Expression s = new IntegerConstant(size(i + 1));
			pos[i] = Quotient.of(remaining, s);
			remaining = Sum.of(remaining, Minus.of(Product.of(pos[i], s)));
		}

		return pos;
	}

	public Expression subset(TraversalPolicy shape, int index, int... loc) {
		return subset(shape, new IntegerConstant(index), loc);
	}

	public Expression subset(TraversalPolicy shape, Expression index, int... loc) {
		return subset(shape, index,
				IntStream.of(loc).mapToObj(i -> new IntegerConstant(i)).toArray(Expression[]::new));
	}

	public Expression subset(TraversalPolicy shape, int index, Expression... loc) {
		return subset(shape, new IntegerConstant(index), loc);
	}

	public Expression subset(TraversalPolicy shape, Expression index, Expression... loc) {
		if (shape.getDimensions() != getDimensions()) {
			System.out.println("WARN: Obtaining a " + shape.getDimensions() +
					"d subset of a " + getDimensions() +
					"d collection is likely to produce an unexpected result");
		}

		Expression pos[] = shape.position(index);

		for (int i = 0; i < loc.length; i++) {
			Expression l = loc[i];
			pos[i] = Sum.of(pos[i], l);
		}

		return index(pos);
	}

	public TraversalPolicy consolidate() { return traverse(getTraversalAxis() - 1); }

	public TraversalPolicy traverse() { return traverse(getTraversalAxis() + 1); }

	@Override
	public TraversalPolicy traverse(int axis) {
		TraversalPolicy p = new TraversalPolicy(order, dims);
		p.traversalAxis = axis;
		return p;
	}

	public TraversalPolicy traverseEach() {
		return traverse(getDimensions());
	}

	public TraversalPolicy traverse(TraversalOrdering order) {
		TraversalPolicy p = new TraversalPolicy(order == null ? getOrder() : order.compose(getOrder()), dims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy withOrder(TraversalOrdering order) {
		if (this.order == order) return this;
		return new TraversalPolicy(order, dims).traverse(traversalAxis);
	}

	public TraversalPolicy prependDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		newDims[0] = size;
		for (int i = 0; i < getDimensions(); i++) newDims[i + 1] = length(i);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis + 1;
		return p;
	}

	public TraversalPolicy append(TraversalPolicy shape) {
		int newDims[] = new int[getDimensions() + shape.getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = length(i);
		for (int i = 0; i < shape.getDimensions(); i++) newDims[i + getDimensions()] = shape.length(i);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy appendDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = length(i);
		newDims[newDims.length - 1] = size;

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy replaceDimension(int size) {
		return replaceDimension(getTraversalAxis(), size);
	}

	public TraversalPolicy replaceDimension(int axis, int size) {
		int newDims[] = new int[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == axis ? size : length(i);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy stride(int stride) {
		int newDims[] = new int[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == traversalAxis ? stride : 0;

		TraversalPolicy p = new TraversalPolicy(order, true, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy subset(int depth) {
		int newDims[] = new int[getDimensions() - depth];
		for (int i = 0; i < newDims.length; i++) newDims[i] = length(i + depth);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis > depth ? traversalAxis - depth : 0;
		return p;
	}

	public TraversalPolicy item() {
		if (traversalAxis == dims.length) return new TraversalPolicy(order);
		return new TraversalPolicy(IntStream.range(traversalAxis, dims.length).map(i -> dims[i]).toArray());
	}

	public TraversalPolicy replace(TraversalPolicy itemShape) {
		int newDims[] = new int[traversalAxis + itemShape.getDimensions()];
		for (int i = 0; i < traversalAxis; i++) newDims[i] = length(i);
		for (int i = 0; i < itemShape.getDimensions(); i++) newDims[i + traversalAxis] = itemShape.length(i);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy flatten() {
		return flatten(false);
	}

	public TraversalPolicy flatten(boolean preserveCount) {
		if (preserveCount) {
			return new TraversalPolicy(order, getCount(), getSize()).traverse();
		} else {
			return new TraversalPolicy(order, getTotalSize());
		}
	}

	public int getTraversalAxis() { return traversalAxis; }

	public int getSize() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return size(traversalAxis);
	}

	public long getSizeLong() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return sizeLong(traversalAxis);
	}

	public int getTotalSize() { return size(0); }

	public long getTotalSizeLong() { return sizeLong(0); }

	@Override
	public long getCountLong() {
		if (getSizeLong() == 0) {
			throw new UnsupportedOperationException();
		}

		return getTotalSizeLong() / getSizeLong();
	}

	public int getDimensions() { return dims.length; }

	public Stream<int[]> stream() {
		return IntStream.range(0, getTotalSize()).mapToObj(this::position);
	}

	public void store(DataOutputStream dos) throws IOException {
		if (order != null) {
			throw new UnsupportedOperationException();
		}

		dos.writeInt(dims.length);
		dos.writeInt(traversalAxis);

		for(long d : dims) {
			dos.writeLong(d);
		}

		dos.flush();
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(dims);
	}

	public boolean equalsIgnoreAxis(TraversalPolicy p) {
		return Arrays.equals(dims, p.dims);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof TraversalPolicy) {
			TraversalPolicy p = (TraversalPolicy) obj;
			return Arrays.equals(dims, p.dims) && traversalAxis == p.traversalAxis;
		}

		return false;
	}

	public String toStringDetail() {
		return this + "[axis=" + getTraversalAxis() + "|" + getCountLong() + "x" + getSize() + "]";
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("(");
		for (int i = 0; i < dims.length; i++) {
			sb.append(dims[i]);
			if (i < dims.length - 1) sb.append(", ");
		}
		sb.append(")");
		return sb.toString();
	}

	public static TraversalPolicy load(DataInputStream in) throws IOException {
		int dimCount = in.readInt();
		int traversalAxis = in.readInt();

		int dims[] = new int[dimCount];

		for(int i = 0; i < dims.length; i++) {
			long d = in.readLong();
			if (d > Integer.MAX_VALUE) {
				throw new UnsupportedOperationException();
			}

			dims[i] = (int) d;
		}

		return new TraversalPolicy(null, dims).traverse(traversalAxis);
	}

	public static <T, V> T alignTraversalAxes(List<TraversalPolicy> shapes, List<V> values,
											  BiFunction<Integer, V, V> traversalFunction,
											  BiFunction<Integer, V, V> expandFunction,
											  BiFunction<TraversalPolicy, List<V>, T> resultProcessor) {
		return alignTraversalAxes(shapes, values,
				enableStrictSizes || shapes.stream().noneMatch(s -> s.getDimensions() > 1),
				traversalFunction, expandFunction, resultProcessor);
	}

	public static <T, V> T alignTraversalAxes(List<TraversalPolicy> shapes, List<V> values,
											  boolean requireIdenticalTotalSize, BiFunction<Integer, V, V> traversalFunction,
											  BiFunction<Integer, V, V> expandFunction,
											  BiFunction<TraversalPolicy, List<V>, T> resultProcessor) {
		TreeSet<TraversalPolicy> sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getSize));
		sortedShapes.addAll(shapes);

		s: for (TraversalPolicy shape : sortedShapes) {
			int[] compatibleAxes =
					IntStream.range(0, values.size())
							.map(i -> compatibleAxis(shapes.get(i), shape, requireIdenticalTotalSize))
							.filter(i -> i >= 0).toArray();
			if (compatibleAxes.length != values.size()) continue s;

			List<V> vals = new ArrayList<>();
			for (int i = 0; i < values.size(); i++) {
				vals.add(traversalFunction.apply(compatibleAxes[i], values.get(i)));
			}

			return resultProcessor.apply(shape, vals);
		}

		sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getTotalSize).reversed());
		sortedShapes.addAll(shapes);

		int largest = sortedShapes.iterator().next().getTotalSize();
		int depth = sortedShapes.iterator().next().getDimensions();

		s: for (TraversalPolicy shape : sortedShapes) {
			if (shape.getTotalSize() < largest) {
				break s;
			}

			int minDepth = (enableStrictSizes || depth <= 1) ? -1 : 0;

			int[] matchDepths =
					IntStream.range(0, values.size())
							.map(i -> matchDepth(shapes.get(i), shape))
							.filter(i -> i > minDepth).toArray();
			if (matchDepths.length != values.size()) continue s;

			List<V> vals = new ArrayList<>();
			for (int i = 0; i < values.size(); i++) {
				int repeat;

				if (enableDivisibleSizes && shape.getTotalSize() % shapes.get(i).getTotalSize() != 0) {
					repeat = 0;
				} else {
					repeat = shape.getTotalSize() / shapes.get(i).getTotalSize();
				}


				V v = traversalFunction.apply(matchDepths[i], values.get(i));
				if (repeat > 1) v = expandFunction.apply(repeat, v);
				vals.add(v);
			}

			return resultProcessor.apply(shape, vals);
		}

		throw new IllegalArgumentException("No compatible traversal axes");
	}

	public static int compatibleAxis(TraversalPolicy shape, TraversalPolicy target, boolean requireIdenticalTotalSize) {
		for (int i = 0; i < shape.getDimensions() + 1; i++) {
			if (shape.sizeLong(i) == target.getSizeLong() &&
					(!requireIdenticalTotalSize ||
							shape.getTotalSizeLong() == target.getTotalSizeLong())) {
				return i;
			}
		}

		return -1;
	}

	public static int matchDepth(TraversalPolicy shape, TraversalPolicy target) {
		int i;

		i: for (i = 0; i < shape.getDimensions(); i++) {
			if (target.getDimensions() <= i || shape.length(i) != target.length(i)) {
				break i;
			}
		}

		return i;
	}
}
