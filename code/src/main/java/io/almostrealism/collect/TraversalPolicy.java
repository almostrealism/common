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

package io.almostrealism.collect;

import io.almostrealism.code.ExpressionFeatures;
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

/**
 * A {@link TraversalPolicy} defines how a sequence of elements should be
 * traversed to form a multidimensional collection.
 * It specifies the dimensions of the collection and the rate at which data
 * is traversed along each axis. This information can then be used to transform
 * a position in the output space (the space of the collection) into an
 * index in the input space (the natural order of elements) and vice versa.
 *
 * @author  Michael Murray
 */
public class TraversalPolicy implements Traversable<TraversalPolicy>, Countable, Describable, ExpressionFeatures {
	public static boolean enableStrictSizes = true;
	public static boolean enableDivisibleSizes = true;

	public static long MAX_SIZE = Long.MAX_VALUE / Precision.FP64.bytes();

	private TraversalOrdering order;

	/**
	 * Dimensions of the output space.
	 */
	private long dims[];

	/**
	 * Order of the output space dimensions with respect
	 * to the input space.
	 */
	private int dimsOrder[];

	/**
	 * Numerator for the rate of traversal through the input space
	 * across each dimension of the output space.
	 */
	private long rateNumerator[];

	/**
	 * Denominator for the rate of traversal through the input space
	 * across each dimension of the output space.
	 */
	private long rateDenominator[];

	/**
	 * Axis used to determine the {@link #getCountLong() count} and
	 * {@link #getSizeLong() size} of the collection when perform
	 * traversal operations in parallel.
	 */
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
		this(order, tolerateZero, tolerateLarge, dims,
				null, null, null);
	}

	public TraversalPolicy(TraversalOrdering order, boolean tolerateZero, boolean tolerateLarge,
						   long[] dims, int[] dimsOrder, long[] rateNumerator, long[] rateDenominator) {
		this.order = order;
		this.dims = dims;
		this.dimsOrder = dimsOrder == null ? IntStream.range(0, dims.length).toArray() : dimsOrder;
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
		} else if (!tolerateZero) {
			throw new IllegalArgumentException();
		}
	}

	public TraversalOrdering getOrder() { return order; }

	/**
	 * The length of the collection along the specified axis
	 * of the output space.
	 */
	public int length(int axis) {
		return Math.toIntExact(lengthLong(axis));
	}

	/**
	 * The length of the collection along the specified axis
	 * of the output space.
	 */
	public long lengthLong(int axis) {
		return lengthLong(axis, false);
	}

	/**
	 * The length along the specified axis of the input space being
	 * traversed to form the collection.
	 */
	public int inputLength(int axis) {
		return Math.toIntExact(inputLengthLong(axis));
	}

	/**
	 * The length along the specified axis of the input space being
	 * traversed to form the collection.
	 */
	public long inputLengthLong(int axis) {
		return lengthLong(axis, true);
	}

	private long lengthLong(int axis, boolean input) {
		if (!input) return dims[axis];

		long len = dims[dimsOrder[axis]];
		len = len * rateNumeratorLong(axis);
		len = len / rateDenominatorLong(axis);
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
		} else if (dims[depth] == 0) {
			return 0;
		} else {
			long s = sizeLong(depth + 1, input);
			if (s <= 0) {
				throw new UnsupportedOperationException();
			}

			long d = input ? inputLengthLong(depth) : lengthLong(depth);
			if (d <= 0) {
				throw new UnsupportedOperationException();
			}

			return s * d;
		}
	}

	/**
	 * Return the raw dimensions of the output space.
	 */
	public int[] extent() {
		int[] ext = new int[dims.length];
		for (int i = 0; i < dims.length; i++) {
			ext[i] = length(i);
		}
		return ext;
	}

	/**
	 * Return the raw dimensions of the output space.
	 */
	public long[] extentLong() {
		long[] ext = new long[dims.length];
		for (int i = 0; i < dims.length; i++) {
			ext[i] = lengthLong(i);
		}
		return ext;
	}

	/**
	 * Return the raw shape of the output space.
	 */
	public TraversalPolicy extentShape() {
		return new TraversalPolicy(extentLong())
				.traverse(traversalAxis);
	}

	/**
	 * Return the raw shape of the input space.
	 */
	public TraversalPolicy inputShape() {
		long newDims[] = new long[dims.length];
		for (int i = 0; i < dims.length; i++) {
			newDims[i] = inputLengthLong(i);
		}
		return new TraversalPolicy(order, true, newDims)
				.traverse(traversalAxis);
	}

	/**
	 * Given a position in the output space,
	 * return an index in the input space.
	 */
	public int index(int... pos) {
		// Reorder the position array from the order of the
		// output space to the order of the input space
		int[] internalPos = new int[pos.length];
		for (int i = 0; i < pos.length; i++) {
			internalPos[dimsOrder[i]] = pos[i];
		}
		
		// Calculate index using internal positions
		int index = 0;
		for (int i = 0; i < internalPos.length; i++) {
			// Use the internal dimension index for rate calculations
			long rateNum = rateNumerator == null || i == -1 ? 1 : rateNumerator[i];
			long rateDen = rateDenominator == null || i == -1 ? 1 : rateDenominator[i];
			index += (internalPos[i] / rateDen) * rateNum * inputSize(i + 1);
		}
		return index;
	}

	/**
	 * Given a position in the output space,
	 * return an index in the input space.
	 */
	public Expression index(Expression... pos) {
		if (pos.length != getDimensions()) {
			throw new IllegalArgumentException();
		}

		// Reorder the position array from the order of the
		// output space to the order of the input space
		Expression[] internalPos = new Expression[pos.length];
		for (int i = 0; i < pos.length; i++) {
			internalPos[dimsOrder[i]] = pos[i];
		}

		Expression index = new IntegerConstant(0);

		for (int i = 0; i < internalPos.length; i++) {
			Expression p = internalPos[i];
			// Use the internal dimension index for rate calculations
			long rateNum = rateNumerator == null || i == -1 ? 1 : rateNumerator[i];
			long rateDen = rateDenominator == null || i == -1 ? 1 : rateDenominator[i];
			
			p = Quotient.of(p, e(rateDen));
			p = Product.of(p, e(rateNum));

			Expression s = e(inputSizeLong(i + 1));
			index = Sum.of(index, Product.of(p, s));
		}

		return index;
	}

	/**
	 * Given an index in the input space,
	 * return a position in the output space.
	 */
	public int[] position(int index) {
		if (index > getTotalInputSize()) {
			throw new IllegalArgumentException();
		}

		int internalPos[] = new int[getDimensions()];
		
		int remaining = index;
		for (int i = 0; i < internalPos.length; i++) {
			int s = inputSize(i + 1);
			internalPos[i] = remaining / s;
			remaining = remaining - internalPos[i] * s;
		}
		
		// Permute to external order
		int pos[] = new int[getDimensions()];
		for (int i = 0; i < pos.length; i++) {
			pos[i] = internalPos[dimsOrder[i]];
		}

		return pos;
	}

	/**
	 * Given an index in the input space,
	 * return a position in the output space.
	 */
	public Expression[] position(Expression index) {
		Expression internalPos[] = new Expression[getDimensions()];

		Expression remaining = index;
		for (int i = 0; i < internalPos.length; i++) {
			Expression s = e(inputSizeLong(i + 1));
			internalPos[i] = Quotient.of(remaining, s);
			remaining = Sum.of(remaining, Minus.of(Product.of(internalPos[i], s)));
		}
		
		// Permute to external order
		Expression pos[] = new Expression[getDimensions()];
		for (int i = 0; i < pos.length; i++) {
			pos[i] = internalPos[dimsOrder[i]];
		}

		return pos;
	}

	public int inputIndex(int index) {
		if (isRegular()) return index;
		return index(new TraversalPolicy(dims).position(index));
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

	public TraversalPolicy permute(int... order) {
		if (order.length != getDimensions()) {
			throw new IllegalArgumentException("Order length (" + order.length +
					") must match the number of dimensions (" + getDimensions() + ")");
		}

		long newDims[] = new long[getDimensions()];
		int newOrder[] = new int[getDimensions()];
		for (int i = 0; i < order.length; i++) {
			if (order[i] >= getDimensions()) {
				throw new IllegalArgumentException("Dimension index " + order[i] + " is out of bounds");
			}

			newDims[i] = lengthLong(order[i]);
			newOrder[i] = dimsOrder[order[i]];
		}

		TraversalPolicy p = new TraversalPolicy(
				this.order, true, false,
				newDims, newOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy consolidate() { return traverse(getTraversalAxis() - 1); }

	public TraversalPolicy traverse() { return traverse(getTraversalAxis() + 1); }

	@Override
	public TraversalPolicy traverse(int axis) {
		TraversalPolicy p = new TraversalPolicy(order, true, true, dims, dimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = axis;
		return p;
	}

	public TraversalPolicy traverseEach() {
		return traverse(getDimensions());
	}

	public TraversalPolicy traverse(TraversalOrdering order) {
		TraversalPolicy p = new TraversalPolicy(
				order == null ? getOrder() : order.compose(getOrder()),
				true, true, dims, dimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy withOrder(TraversalOrdering order) {
		if (this.order == order) return this;
		return new TraversalPolicy(
					order, true, true,
					dims, dimsOrder, rateNumerator, rateDenominator)
				.traverse(traversalAxis);
	}

	public TraversalPolicy withRate(int axis, int numerator, long denominator) {
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
				dims, dimsOrder, newNumerator, newDenominator)
				.traverse(traversalAxis);
	}

	public TraversalPolicy withInput(int... dims) {
		if (dims.length != getDimensions()) {
			throw new IllegalArgumentException();
		}

		TraversalPolicy result = this;

		for (int i = 0; i < dims.length; i++) {
			result = result.withRate(i, dims[i], length(i));
		}

		return result;
	}

	public TraversalPolicy repeat(int count) {
		return repeat(getTraversalAxis(), count);
	}

	public TraversalPolicy repeat(int axis, long count) {
		if (axis >= dims.length) {
			throw new IllegalArgumentException();
		}

		return replaceDimension(axis, count * length(axis))
				.withRate(axis, 1, count);
	}

	public TraversalPolicy prependDimension(int size) {
		long newDims[] = new long[getDimensions() + 1];
		newDims[0] = size;
		for (int i = 0; i < getDimensions(); i++) newDims[i + 1] = lengthLong(i);

		// Create new dimsOrder with shifted indices
		int newDimsOrder[] = new int[newDims.length];
		newDimsOrder[0] = 0; // New dimension maps to itself
		for (int i = 0; i < getDimensions(); i++) {
			newDimsOrder[i + 1] = dimsOrder[i] + 1;
		}

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, newDimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis + 1;
		return p;
	}

	public TraversalPolicy append(TraversalPolicy shape) {
		long newDims[] = new long[getDimensions() + shape.getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = lengthLong(i);
		for (int i = 0; i < shape.getDimensions(); i++) newDims[i + getDimensions()] = shape.length(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, null, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy appendDimension(int size) {
		long newDims[] = new long[getDimensions() + 1];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = lengthLong(i);
		newDims[newDims.length - 1] = size;

		// Create new dimsOrder with existing mappings plus new dimension
		int newDimsOrder[] = new int[newDims.length];
		for (int i = 0; i < getDimensions(); i++) {
			newDimsOrder[i] = dimsOrder[i];
		}
		newDimsOrder[newDims.length - 1] = newDims.length - 1; // New dimension maps to itself

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, newDimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy replaceDimension(int size) {
		return replaceDimension(getTraversalAxis(), size);
	}

	public TraversalPolicy replaceDimension(int axis, long size) {
		long newDims[] = new long[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == axis ? size : lengthLong(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, dimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy stride(int stride) {
		long newDims[] = new long[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == traversalAxis ? stride : 0;

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, dimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis;
		return p;
	}

	public TraversalPolicy subset(int depth) {
		long newDims[] = new long[getDimensions() - depth];
		for (int i = 0; i < newDims.length; i++) newDims[i] = lengthLong(i + depth);

		// Create subset of dimsOrder
		int newDimsOrder[] = new int[newDims.length];
		for (int i = 0; i < newDims.length; i++) {
			newDimsOrder[i] = dimsOrder[i + depth] - depth;
		}

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, newDimsOrder, rateNumerator, rateDenominator);
		p.traversalAxis = traversalAxis > depth ? traversalAxis - depth : 0;
		return p;
	}

	public TraversalPolicy item() {
		if (traversalAxis == dims.length) return new TraversalPolicy(order, true);
		return new TraversalPolicy(Arrays.stream(dims, traversalAxis, dims.length).toArray());
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

	public TraversalPolicy flatten(int... requiredDims) {
		return flatten(false, requiredDims);
	}

	/**
	 * Create a new {@link TraversalPolicy} which contains only one
	 * dimension in addition to those specified by requiredDims.
	 * If this process is strict, the dimensions of the original
	 * must actually match the required dimensions. This process
	 * will attempt to preserve the traversal axis of the original,
	 * but this may not result in the same count and size if the
	 * specified required dimensions make that impossible.
	 */
	public TraversalPolicy flatten(boolean strict, int... requiredDims) {
		if (requiredDims.length == 0) {
			return flatten(false);
		}

		TraversalPolicy targetItem = new TraversalPolicy(requiredDims);
		long count = getTotalSizeLong() / targetItem.getTotalSizeLong();

		int offset = getDimensions() - requiredDims.length;
		int axis = 0;

		long newDims[] = new long[requiredDims.length + 1];

		for (int i = 0; i < requiredDims.length + 1; i++) {
			int pos = i - 1;
			int origPos = pos + offset;

			if (i > 0) {
				newDims[i] = requiredDims[pos];

				if (strict && lengthLong(origPos) != requiredDims[pos]) {
					throw new IllegalArgumentException();
				} else if (getTraversalAxis() == origPos) {
					axis = i;
				}
			} else {
				newDims[i] = count;
			}
		}

		return new TraversalPolicy(order, true, true, newDims)
						.traverse(axis);
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

	/**
	 * Create a new {@link TraversalPolicy} which has the same
	 * dimensions, but with a traversal axis which results in
	 * a {@link #getCountLong() count} that matches that of the
	 * specified {@link TraversalPolicy}.
	 */
	public TraversalPolicy alignCount(TraversalPolicy alt) {
		return alignCount(alt.getCountLong());
	}

	/**
	 * Create a new {@link TraversalPolicy} which has the same
	 * dimensions, but with a traversal axis which results in
	 * a {@link #getCountLong() count} that matches the specified
	 * value (if possible).
	 */
	public TraversalPolicy alignCount(long count) {
		int axis = getTraversalAxis();

		while (traverse(axis).getCountLong() < count && axis < getDimensions()) {
			axis++;
		}

		if (getTraversalAxis() != axis) {
			return traverse(axis);
		}

		return this;
	}

	/**
	 * Create a new {@link TraversalPolicy} which has the same dimensions,
	 * but with a {@link #getTraversalAxis() traversal axis} which results
	 * in a {@link #getSizeLong() size} that matches that of the specified
	 * {@link TraversalPolicy}.
	 */
	public TraversalPolicy alignSize(TraversalPolicy alt) {
		return alignSize(alt.getSizeLong());
	}

	/**
	 * Create a new {@link TraversalPolicy} which has the same dimensions,
	 * but with a {@link #getTraversalAxis() traversal axis} which results
	 * in a {@link #getSizeLong() size} that matches the specified value
	 * (if possible).
	 */
	public TraversalPolicy alignSize(long size) {
		int axis = 0;

		while (traverse(axis).getSizeLong() > size && axis < getDimensions()) {
			axis++;
		}

		return getTraversalAxis() != axis ? traverse(axis) : this;
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

	public boolean isRegular() {
		return getOrder() == null && inputShape().equalsIgnoreAxis(this);
	}

	// TODO  Rename to positions
	public Stream<int[]> stream() {
		if (isRegular()) {
			return IntStream.range(0, getTotalSize()).mapToObj(this::position);
		}

		return new TraversalPolicy(dims).stream();
	}

	public Stream<int[]> inputPositions() {
		if (isRegular()) {
			return stream();
		}

		return IntStream.range(0, getTotalSize())
				.map(this::inputIndex)
				.mapToObj(this::position);
	}

	public IntStream indices() {
		if (isRegular()) {
			return IntStream.range(0, getTotalSize());
		}

		return stream().mapToInt(this::index);
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

	public int[] differingAxes(TraversalPolicy p) {
		if (p.getDimensions() != getDimensions()) {
			throw new IllegalArgumentException();
		}

		return IntStream.range(0, getDimensions())
				.filter(i -> lengthLong(i) != p.lengthLong(i))
				.toArray();
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(dims);
	}

	public boolean equalsIgnoreAxis(TraversalPolicy p) {
		return Arrays.equals(dims, p.dims) &&
				Arrays.equals(dimsOrder, p.dimsOrder) &&
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

		return this + "[axis=" + getTraversalAxis() + "|" + getCountLong() + "x" + getSizeLong() + "]";
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("(");
		for (int i = 0; i < dims.length; i++) {
			sb.append(lengthLong(i));
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
		TreeSet<TraversalPolicy> sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getSizeLong));
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

		sortedShapes = new TreeSet<>(Comparator.comparing(TraversalPolicy::getTotalSizeLong).reversed());
		sortedShapes.addAll(shapes);

		long largest = sortedShapes.iterator().next().getTotalSizeLong();
		int depth = sortedShapes.iterator().next().getDimensions();

		s: for (TraversalPolicy shape : sortedShapes) {
			if (shape.getTotalSizeLong() < largest) {
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
				long repeat;

				if (shapes.get(i).getTotalSizeLong() == 0) {
					throw new IllegalArgumentException();
				}

				if (enableDivisibleSizes && shape.getTotalSizeLong() % shapes.get(i).getTotalSizeLong() != 0) {
					repeat = 0;
				} else {
					repeat = shape.getTotalSizeLong() / shapes.get(i).getTotalSizeLong();
				}

				if (repeat > Integer.MAX_VALUE) {
					throw new UnsupportedOperationException();
				}

				V v = traversalFunction.apply(matchDepths[i], values.get(i));
				if (repeat > 1) v = expandFunction.apply(Math.toIntExact(repeat), v);
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