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

package io.almostrealism.collect;

import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Countable;
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class TraversalPolicy implements Traversable<TraversalPolicy>, Countable {
	public static long MAX_SIZE = Long.MAX_VALUE / Precision.FP64.bytes();

	private int dims[];
	private int traversalAxis;

	public TraversalPolicy(int... dims) {
		this.dims = dims;

		if (dims.length > 0) {
			long total = dims[0];

			for (int i = 1; i < dims.length; i++) {
				total *= dims[i];

				if (total > MAX_SIZE || total < 0) {
					throw new IllegalArgumentException();
				}
			}
		}
	}

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

	public Expression index(PositionExpression pos) {
		return index(pos.toArray());
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
			remaining = Sum.of(remaining, new Minus(Product.of(pos[i], s)));
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
		TraversalPolicy p = new TraversalPolicy(dims);
		p.traversalAxis = axis;
		return p;
	}

	public TraversalPolicy traverseEach() {
		return traverse(getDimensions());
	}

	public TraversalPolicy prependDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		newDims[0] = size;
		for (int i = 0; i < getDimensions(); i++) newDims[i + 1] = length(i);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis + 1;
		return p;
	}

	public TraversalPolicy append(TraversalPolicy shape) {
		int newDims[] = new int[getDimensions() + shape.getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = length(i);
		for (int i = 0; i < shape.getDimensions(); i++) newDims[i + getDimensions()] = shape.length(i);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy appendDimension(int size) {
		int newDims[] = new int[getDimensions() + 1];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = length(i);
		newDims[newDims.length - 1] = size;

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy replaceDimension(int size) {
		return replaceDimension(getTraversalAxis(), size);
	}

	public TraversalPolicy replaceDimension(int axis, int size) {
		int newDims[] = new int[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == axis ? size : length(i);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy stride(int stride) {
		int newDims[] = new int[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == traversalAxis ? stride : 0;

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy subset(int depth) {
		int newDims[] = new int[getDimensions() - depth];
		for (int i = 0; i < newDims.length; i++) newDims[i] = length(i + depth);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis > depth ? traversalAxis - depth : 0;
		return p;
	}

	public TraversalPolicy item() {
		if (traversalAxis == dims.length) return new TraversalPolicy();
		return new TraversalPolicy(IntStream.range(traversalAxis, dims.length).map(i -> dims[i]).toArray());
	}

	public TraversalPolicy replace(TraversalPolicy itemShape) {
		int newDims[] = new int[traversalAxis + itemShape.getDimensions()];
		for (int i = 0; i < traversalAxis; i++) newDims[i] = length(i);
		for (int i = 0; i < itemShape.getDimensions(); i++) newDims[i + traversalAxis] = itemShape.length(i);

		TraversalPolicy p = new TraversalPolicy(newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy flatten() {
		return flatten(false);
	}

	public TraversalPolicy flatten(boolean preserveCount) {
		if (preserveCount) {
			return new TraversalPolicy(getCount(), getSize()).traverse();
		} else {
			return new TraversalPolicy(getTotalSize());
		}
	}

	public int getTraversalAxis() { return traversalAxis; }

	public int getSize() { return size(traversalAxis); }

	public long getSizeLong() { return sizeLong(traversalAxis); }

	public int getTotalSize() { return size(0); }

	public long getTotalSizeLong() { return sizeLong(0); }

	@Override
	public int getCount() {
		if (getSizeLong() == 0) {
			throw new UnsupportedOperationException();
		}

		return Math.toIntExact(getTotalSizeLong() / getSizeLong());
	}

	public int getDimensions() { return dims.length; }

	public Stream<int[]> stream() {
		return IntStream.range(0, getTotalSize()).mapToObj(this::position);
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
		return this + "[axis=" + getTraversalAxis() + "|" + getCount() + "x" + getSize() + "]";
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

	public static <T, V> T alignTraversalAxes(List<TraversalPolicy> shapes, List<V> values,
											  BiFunction<Integer, V, V> traversalFunction,
											  BiFunction<TraversalPolicy, List<V>, T> resultProcessor) {
		TreeSet<TraversalPolicy> sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getSize));
		sortedShapes.addAll(shapes);

		s: for (TraversalPolicy shape : sortedShapes) {
			int[] compatibleAxes =
					IntStream.range(0, values.size())
							.map(i -> compatibleAxis(shape, shapes.get(i)))
							.filter(i -> i >= 0).toArray();
			if (compatibleAxes.length != values.size()) continue s;

			List<V> vals = new ArrayList<>();
			for (int i = 0; i < values.size(); i++) {
				vals.add(traversalFunction.apply(compatibleAxes[i], values.get(i)));
			}

			return resultProcessor.apply(shape, vals);
		}

		throw new IllegalArgumentException("No compatible traversal axes");
	}

	public static int compatibleAxis(TraversalPolicy shape, TraversalPolicy target) {
		for (int i = 0; i < shape.getDimensions() + 1; i++) {
			if (shape.size(i) == target.getSize()) {
				return i;
			}
		}

		return -1;
	}
}
