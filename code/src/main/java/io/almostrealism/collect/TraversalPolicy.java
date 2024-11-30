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
import org.almostrealism.io.Describable;

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

public class TraversalPolicy implements Traversable<TraversalPolicy>, Countable, Describable {
	public static boolean enableStrictSizes = true;
	public static boolean enableDivisibleSizes = true;

	public static long MAX_SIZE = Long.MAX_VALUE / Precision.FP64.bytes();

	private TraversalOrdering order;
	private long dims[];
	private long rateNumerator[];
	private long rateDenominator[];
	private int traversalAxis;

	public TraversalPolicy(int... dims) {
		this(null, dims);
	}

	public TraversalPolicy(long... dims) {
		this(null, dims);
	}

	public TraversalPolicy(boolean tolerateZero, int... dims) {
		this(null, tolerateZero, IntStream.of(dims).mapToLong(i -> i).toArray());
	}

	public TraversalPolicy(TraversalOrdering order, int... dims) {
		this(order, IntStream.of(dims).mapToLong(i -> i).toArray());
	}

	public TraversalPolicy(TraversalOrdering order, long... dims) {
		this(order, false, dims);
	}

	public TraversalPolicy(TraversalOrdering order, boolean tolerateZero, long... dims) {
		this(order, tolerateZero, true, dims);
	}


	public TraversalPolicy(TraversalOrdering order, boolean tolerateZero, boolean tolerateLarge, long... dims) {
		this(order, tolerateZero, tolerateLarge, dims, null, null);
	}

	public TraversalPolicy(TraversalOrdering order, boolean tolerateZero, boolean tolerateLarge,
						   long[] dims, long[] rateNumerator, long[] rateDenominator) {
		this.order = order;
		this.dims = dims;
		this.rateNumerator = rateNumerator;
		this.rateDenominator = rateDenominator;

		if (rateNumerator != null && rateNumerator.length != dims.length) {
			throw new IllegalArgumentException();
		} else if (rateDenominator != null && rateDenominator.length != dims.length) {
			throw new IllegalArgumentException();
		}

		if (dims.length > 0) {
			long total = dims[0];

			for (int i = 1; i < dims.length; i++) {
				total *= dims[i];

				if (total < 0 ||
						(!tolerateLarge && total > MAX_SIZE) ||
						(!tolerateZero && total == 0)) {
					throw new IllegalArgumentException();
				}
			}
		}
	}

	public TraversalOrdering getOrder() { return order; }

	public int length(int axis) {
		return Math.toIntExact(dims[axis]);
	}

	public int inputLength(int axis) {
		return Math.toIntExact(inputLengthLong(axis));
	}

	public long lengthLong(int axis) {
		return lengthLong(axis, false);
	}

	public long inputLengthLong(int axis) {
		return lengthLong(axis, true);
	}

	private long lengthLong(int axis, boolean input) {
		long len =  dims[axis];

		if (input) {
			len = len * rateNumeratorLong(axis);
			len = len / rateDenominatorLong(axis);
		}

		return len;
	}

	public int rateNumerator(int axis) {
		return Math.toIntExact(rateNumeratorLong(axis));
	}

	public long rateNumeratorLong(int axis) {
		return rateNumerator == null || axis == -1 ? 1 : rateNumerator[axis];
	}

	public int rateDenominator(int axis) {
		return Math.toIntExact(rateDenominatorLong(axis));
	}

	public long rateDenominatorLong(int axis) {
		return rateDenominator == null || axis == -1 ? 1 : rateDenominator[axis];
	}

	public int size(int depth) {
		return Math.toIntExact(sizeLong(depth));
	}

	public int inputSize(int depth) {
		return Math.toIntExact(inputSizeLong(depth));
	}

	public long sizeLong(int depth) {
		return sizeLong(depth, false);
	}

	public long inputSizeLong(int depth) {
		return sizeLong(depth, true);
	}

	private long sizeLong(int depth, boolean input) {
		if (dims.length == 0) {
			return 0;
		} else if (depth == dims.length) {
			return 1;
		} else if (depth > dims.length) {
			throw new IllegalArgumentException("Depth is greater than the number of dimensions");
		} else {
			long s = sizeLong(depth + 1, input);
			if (s <= 0) {
				throw new UnsupportedOperationException();
			}

			long d = input  ? inputLengthLong(depth) : lengthLong(depth);
			if (d <= 0) {
				throw new UnsupportedOperationException();
			}

			return s * d;
		}
	}

	public int[] extent() {
		int[] ext = new int[dims.length];
		for (int i = 0; i < dims.length; i++) {
			ext[i] = length(i);
		}
		return ext;
	}

	public long[] extentLong() {
		long[] ext = new long[dims.length];
		for (int i = 0; i < dims.length; i++) {
			ext[i] = lengthLong(i);
		}
		return ext;
	}

	public int index(int... pos) {
		int index = 0;
		for (int i = 0; i < pos.length; i++) {
			index += (pos[i] / rateDenominator(i)) * rateNumerator(i) * inputSize(i + 1);
		}
		return index;
	}

	public Expression index(Expression... pos) {
		if (pos.length != getDimensions()) {
			throw new IllegalArgumentException();
		}

		Expression index = new IntegerConstant(0);

		for (int i = 0; i < pos.length; i++) {
			Expression p = pos[i];
			p = Quotient.of(p, new IntegerConstant(rateDenominator(i)));
			p = Product.of(p, new IntegerConstant(rateNumerator(i)));

			Expression s = new IntegerConstant(inputSize(i + 1));
			index = Sum.of(index, Product.of(p, s));
		}

		return index;
	}

	public int[] position(int index) {
		int pos[] = new int[getDimensions()];

		int remaining = index;
		for (int i = 0; i < pos.length; i++) {
			int s = inputSize(i + 1);
			pos[i] = remaining / s;
			remaining = remaining - pos[i] * s;
		}

		return pos;
	}

	public Expression[] position(Expression index) {
		Expression pos[] = new Expression[getDimensions()];

		Expression remaining = index;
		for (int i = 0; i < pos.length; i++) {
			Expression s = new IntegerConstant(inputSize(i + 1));
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
		TraversalPolicy p = new TraversalPolicy(order, true, true, dims, rateNumerator, rateDenominator);
		p.traversalAxis = axis;
		return p;
	}

	public TraversalPolicy traverseEach() {
		return traverse(getDimensions());
	}

	public TraversalPolicy traverse(TraversalOrdering order) {
		TraversalPolicy p = new TraversalPolicy(
				order == null ? getOrder() : order.compose(getOrder()),
				true, true, dims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy withOrder(TraversalOrdering order) {
		if (this.order == order) return this;
		return new TraversalPolicy(
					order, true, true,
					dims, rateNumerator, rateDenominator)
				.traverse(traversalAxis);
	}

	public TraversalPolicy withRate(int axis, int numerator, int denominator) {
		if (axis >= dims.length) {
			throw new IllegalArgumentException();
		}

		long newNumerator[] = new long[dims.length];
		long newDenominator[] = new long[dims.length];

		for (int i = 0; i < dims.length; i++) {
			newNumerator[i] = (i == axis ? numerator : 1) * rateNumeratorLong(i);
			newDenominator[i] = (i == axis ? denominator : 1) * rateDenominatorLong(i);
		}

		return new TraversalPolicy(
				order, true, true,
				dims, newNumerator, newDenominator)
				.traverse(traversalAxis);
	}

	public TraversalPolicy prependDimension(int size) {
		long newDims[] = new long[getDimensions() + 1];
		newDims[0] = size;
		for (int i = 0; i < getDimensions(); i++) newDims[i + 1] = lengthLong(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis + 1;
		return p;
	}

	public TraversalPolicy append(TraversalPolicy shape) {
		long newDims[] = new long[getDimensions() + shape.getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = lengthLong(i);
		for (int i = 0; i < shape.getDimensions(); i++) newDims[i + getDimensions()] = shape.length(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy appendDimension(int size) {
		long newDims[] = new long[getDimensions() + 1];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = lengthLong(i);
		newDims[newDims.length - 1] = size;

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy replaceDimension(int size) {
		return replaceDimension(getTraversalAxis(), size);
	}

	public TraversalPolicy replaceDimension(int axis, int size) {
		long newDims[] = new long[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == axis ? size : lengthLong(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy stride(int stride) {
		long newDims[] = new long[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == traversalAxis ? stride : 0;

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy subset(int depth) {
		long newDims[] = new long[getDimensions() - depth];
		for (int i = 0; i < newDims.length; i++) newDims[i] = lengthLong(i + depth);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis > depth ? traversalAxis - depth : 0;
		return p;
	}

	public TraversalPolicy item() {
		if (traversalAxis == dims.length) return new TraversalPolicy(order);
		return new TraversalPolicy(IntStream.range(traversalAxis, dims.length).mapToLong(i -> dims[i]).toArray());
	}

	public TraversalPolicy replace(TraversalPolicy itemShape) {
		long newDims[] = new long[traversalAxis + itemShape.getDimensions()];
		for (int i = 0; i < traversalAxis; i++) newDims[i] = lengthLong(i);
		for (int i = 0; i < itemShape.getDimensions(); i++) newDims[i + traversalAxis] = itemShape.lengthLong(i);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy trim() {
		if (length(getDimensions() - 1) == 1) {
			return traverse(getDimensions() - 1)
					.replace(new TraversalPolicy(true))
					.traverse(getTraversalAxis())
					.trim();
		}

		return this;
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

	public int getInputSize() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return inputSize(traversalAxis);
	}

	public long getInputSizeLong() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return inputSizeLong(traversalAxis);
	}

	public int getTotalSize() { return size(0); }

	public long getTotalSizeLong() { return sizeLong(0); }

	public int getTotalInputSize() { return inputSize(0); }

	public long getTotalInputSizeLong() { return inputSizeLong(0); }

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
		return Arrays.equals(dims, p.dims) &&
				Arrays.equals(rateNumerator, p.rateNumerator) &&
				Arrays.equals(rateDenominator, p.rateDenominator);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof TraversalPolicy) {
			TraversalPolicy p = (TraversalPolicy) obj;
			return equalsIgnoreAxis(p) && traversalAxis == p.traversalAxis;
		}

		return false;
	}

	@Override
	public String describe() { return toStringDetail(); }

	public String toStringDetail() {
		if (getSizeLong() == 0) {
			return this + "[axis=" + getTraversalAxis() + "]";
		}

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

	public static TraversalPolicy uniform(int length, int dimensions) {
		return new TraversalPolicy(IntStream.range(0, dimensions).map(i -> length).toArray());
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
