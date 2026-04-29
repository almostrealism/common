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
import org.almostrealism.io.ConsoleFeatures;
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
 * <p><b>Fixed vs Variable Count:</b></p>
 * <p>A {@link TraversalPolicy} can be either <i>fixed-count</i> or <i>variable-count</i>:</p>
 * <ul>
 *   <li><b>Fixed-count (default):</b> The dimensions are predetermined
 *       and do not change. Created via {@code new TraversalPolicy(dims)} or
 *       {@code new TraversalPolicy(true, dims)}.</li>
 *   <li><b>Variable-count:</b> The dimensions can adapt to runtime
 *       inputs, particularly the size of arguments passed to {@link io.almostrealism.relation.Evaluable#evaluate}.
 *   </li>
 * </ul>
 *
 * @author  Michael Murray
 * @see Countable#isFixedCount()
 */
public class TraversalPolicy implements Traversable<TraversalPolicy>, Countable, Describable, ExpressionFeatures, ConsoleFeatures {
	/** Whether to enforce strict validation of dimension sizes during construction. */
	public static boolean enableStrictSizes = true;

	/** Whether to enforce that the total input size is divisible by the denominator rates. */
	public static boolean enableDivisibleSizes = true;

	/** Maximum allowed total element count, bounded by FP64 byte capacity. */
	public static long MAX_SIZE = Long.MAX_VALUE / Precision.FP64.bytes();

	/** The traversal ordering applied to indices, or {@code null} for the identity ordering. */
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

	/** @see #isFixedCount() */
	private boolean fixed;

	/**
	 * Creates a fixed-count policy with the given integer dimensions and identity ordering.
	 *
	 * @param dims the dimensions of the output space
	 */
	public TraversalPolicy(int... dims) {
		this(null, dims);
	}

	/**
	 * Creates a fixed-count policy with the given long dimensions and identity ordering.
	 *
	 * @param dims the dimensions of the output space
	 */
	public TraversalPolicy(long... dims) {
		this(null, dims);
	}

	/**
	 * Creates a fixed-count policy with the given integer dimensions, optionally tolerating
	 * zero-valued dimension sizes.
	 *
	 * @param tolerateZero {@code true} to allow zero-valued dimensions
	 * @param dims         the dimensions of the output space
	 */
	public TraversalPolicy(boolean tolerateZero, int... dims) {
		this(tolerateZero, true, dims);
	}

	/**
	 * Creates a policy with the given integer dimensions, zero-tolerance, and fixed-count flag.
	 *
	 * @param tolerateZero {@code true} to allow zero-valued dimensions
	 * @param fixed        {@code true} for a fixed-count policy
	 * @param dims         the dimensions of the output space
	 */
	public TraversalPolicy(boolean tolerateZero, boolean fixed, int... dims) {
		this(null, tolerateZero, false, fixed,
				IntStream.of(dims).mapToLong(i -> i).toArray());
	}

	/**
	 * Creates a fixed-count policy with the given long dimensions, optionally tolerating
	 * zero-valued dimension sizes.
	 *
	 * @param tolerateZero {@code true} to allow zero-valued dimensions
	 * @param dims         the dimensions of the output space
	 */
	public TraversalPolicy(boolean tolerateZero, long... dims) {
		this(tolerateZero, true, dims);
	}

	/**
	 * Creates a policy with the given long dimensions, zero-tolerance, and fixed-count flag.
	 *
	 * @param tolerateZero {@code true} to allow zero-valued dimensions
	 * @param fixed        {@code true} for a fixed-count policy
	 * @param dims         the dimensions of the output space
	 */
	public TraversalPolicy(boolean tolerateZero, boolean fixed, long... dims) {
		this(null, tolerateZero, false, fixed, dims);
	}

	/**
	 * Creates a fixed-count policy with the given traversal ordering and integer dimensions.
	 *
	 * @param order the traversal ordering to apply, or {@code null} for the identity ordering
	 * @param dims  the dimensions of the output space
	 */
	public TraversalPolicy(TraversalOrdering order, int... dims) {
		this(order, IntStream.of(dims).mapToLong(i -> i).toArray());
	}

	/**
	 * Creates a fixed-count policy with the given traversal ordering and long dimensions.
	 *
	 * @param order the traversal ordering to apply, or {@code null} for the identity ordering
	 * @param dims  the dimensions of the output space
	 */
	public TraversalPolicy(TraversalOrdering order, long... dims) {
		this(order, false, false, true, dims);
	}

	/**
	 * Creates a policy with the given ordering, validation flags, fixed-count flag, and long dimensions.
	 *
	 * @param order         the traversal ordering, or {@code null}
	 * @param tolerateZero  {@code true} to allow zero-valued dimensions
	 * @param tolerateLarge {@code true} to allow sizes that exceed {@link #MAX_SIZE}
	 * @param fixed         {@code true} for a fixed-count policy
	 * @param dims          the dimensions of the output space
	 */
	public TraversalPolicy(TraversalOrdering order,
						   boolean tolerateZero, boolean tolerateLarge,
						   boolean fixed, long... dims) {
		this(order, tolerateZero, tolerateLarge, dims,
				null, null, null, fixed);
	}

	/**
	 * Full constructor used by all other constructors and by deserialization.
	 *
	 * @param order            the traversal ordering, or {@code null}
	 * @param tolerateZero     {@code true} to allow zero-valued dimensions
	 * @param tolerateLarge    {@code true} to allow sizes that exceed {@link #MAX_SIZE}
	 * @param dims             the output-space dimensions
	 * @param dimsOrder        the dimension permutation mapping output axes to input axes,
	 *                         or {@code null} for the identity permutation
	 * @param rateNumerator    the traversal rate numerators per axis, or {@code null} for all ones
	 * @param rateDenominator  the traversal rate denominators per axis, or {@code null} for all ones
	 * @param fixed            {@code true} for a fixed-count policy
	 */
	public TraversalPolicy(TraversalOrdering order, boolean tolerateZero, boolean tolerateLarge,
						   long[] dims, int[] dimsOrder, long[] rateNumerator, long[] rateDenominator,
						   boolean fixed) {
		this.order = order;
		this.dims = dims;
		this.dimsOrder = dimsOrder == null ? IntStream.range(0, dims.length).toArray() : dimsOrder;
		this.rateNumerator = rateNumerator;
		this.rateDenominator = rateDenominator;
		this.fixed = fixed;

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

	/**
	 * Returns the length of the given axis in either the output or input space.
	 *
	 * @param axis  the zero-based dimension axis
	 * @param input {@code true} to return the input length (applying the dimension order and rate)
	 * @return the axis length
	 */
	private long lengthLong(int axis, boolean input) {
		if (!input) return dims[axis];

		long len = dims[dimsOrder[axis]];
		len = len * rateNumeratorLong(axis);
		len = len / rateDenominatorLong(axis);
		return len;
	}

	/**
	 * Returns the traversal-rate numerator for the given axis as an {@code int}.
	 *
	 * @param axis the zero-based dimension axis
	 * @return the rate numerator for that axis
	 */
	public int rateNumerator(int axis) {
		return Math.toIntExact(rateNumeratorLong(axis));
	}

	/**
	 * Returns the traversal-rate numerator for the given axis as a {@code long}.
	 *
	 * @param axis the zero-based dimension axis, or {@code -1} to return {@code 1}
	 * @return the rate numerator for that axis, or {@code 1} if none is set
	 */
	public long rateNumeratorLong(int axis) {
		return rateNumerator == null || axis == -1 ? 1 : rateNumerator[axis];
	}

	/**
	 * Returns the traversal-rate denominator for the given axis as an {@code int}.
	 *
	 * @param axis the zero-based dimension axis
	 * @return the rate denominator for that axis
	 */
	public int rateDenominator(int axis) {
		return Math.toIntExact(rateDenominatorLong(axis));
	}

	/**
	 * Returns the traversal-rate denominator for the given axis as a {@code long}.
	 *
	 * @param axis the zero-based dimension axis, or {@code -1} to return {@code 1}
	 * @return the rate denominator for that axis, or {@code 1} if none is set
	 */
	public long rateDenominatorLong(int axis) {
		return rateDenominator == null || axis == -1 ? 1 : rateDenominator[axis];
	}

	/**
	 * Returns the total number of elements from the given depth to the end of the output space.
	 *
	 * @param depth the starting dimension depth (0 = from the beginning)
	 * @return the element count from that depth
	 */
	public int size(int depth) {
		return Math.toIntExact(sizeLong(depth));
	}

	/**
	 * Returns the total number of input elements from the given depth to the end of the input space.
	 *
	 * @param depth the starting dimension depth
	 * @return the input element count from that depth
	 */
	public int inputSize(int depth) {
		return Math.toIntExact(inputSizeLong(depth));
	}

	/**
	 * Returns the total number of output elements from the given depth as a {@code long}.
	 *
	 * @param depth the starting dimension depth
	 * @return the output element count from that depth
	 */
	public long sizeLong(int depth) {
		return sizeLong(depth, false);
	}

	/**
	 * Returns the total number of input elements from the given depth as a {@code long}.
	 *
	 * @param depth the starting dimension depth
	 * @return the input element count from that depth
	 */
	public long inputSizeLong(int depth) {
		return sizeLong(depth, true);
	}

	/**
	 * Recursively computes the total element count from the given depth.
	 *
	 * @param depth the starting dimension depth
	 * @param input {@code true} to use input lengths when computing the size
	 * @return the element count from that depth
	 */
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
			if (s < 0) {
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
		return new TraversalPolicy(order, true, false, fixed, newDims)
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

	/**
	 * Maps a flat output index to the corresponding flat input index, accounting for
	 * dimension order and traversal rates.
	 *
	 * @param index the flat output index
	 * @return the corresponding flat input index
	 */
	public int inputIndex(int index) {
		if (isRegular()) return index;
		return index(new TraversalPolicy(dims).position(index));
	}

	/**
	 * Convenience method for subset index calculation with integer index and location values.
	 * @see #subset(TraversalPolicy, Expression, Expression...)
	 */
	public Expression subset(TraversalPolicy shape, int index, int... loc) {
		return subset(shape, new IntegerConstant(index), loc);
	}

	/**
	 * Convenience method for subset index calculation with Expression index and integer locations.
	 * @see #subset(TraversalPolicy, Expression, Expression...)
	 */
	public Expression subset(TraversalPolicy shape, Expression index, int... loc) {
		return subset(shape, index,
				IntStream.of(loc).mapToObj(i -> new IntegerConstant(i)).toArray(Expression[]::new));
	}

	/**
	 * Convenience method for subset index calculation with integer index and Expression locations.
	 * @see #subset(TraversalPolicy, Expression, Expression...)
	 */
	public Expression subset(TraversalPolicy shape, int index, Expression... loc) {
		return subset(shape, new IntegerConstant(index), loc);
	}

	/**
	 * Creates an expression that computes the index in this TraversalPolicy's coordinate system
	 * corresponding to a subset operation.
	 *
	 * <p>Given an index in the subset coordinate system and the location offset where the subset
	 * starts, this method computes the equivalent index in the parent collection's coordinate system.</p>
	 *
	 * @param shape The shape/dimensions of the subset being extracted
	 * @param index The index within the subset coordinate system
	 * @param loc The starting location/offset where the subset begins in each dimension
	 * @return An expression that computes the corresponding index in the parent collection
	 */
	public Expression subset(TraversalPolicy shape, Expression index, Expression... loc) {
		if (shape.getDimensions() != getDimensions()) {
			warn("Obtaining a " + shape.getDimensions() +
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

	/**
	 * Returns a new policy with dimensions reordered according to the given permutation.
	 *
	 * @param order the target permutation (each element is a source axis index)
	 * @return the permuted policy
	 * @throws IllegalArgumentException if the permutation length or index is invalid
	 */
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
				newDims, newOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a policy with the traversal axis decreased by one (consolidating one level of parallelism).
	 *
	 * @return the consolidated policy
	 */
	public TraversalPolicy consolidate() { return traverse(getTraversalAxis() - 1); }

	/**
	 * Returns a policy with the traversal axis increased by one (exposing one more level of parallelism).
	 *
	 * @return the traversed policy
	 */
	public TraversalPolicy traverse() { return traverse(getTraversalAxis() + 1); }

	@Override
	public TraversalPolicy traverse(int axis) {
		TraversalPolicy p = new TraversalPolicy(order, true, true, dims, dimsOrder, rateNumerator, rateDenominator, fixed);

		if (axis > dims.length) {
			throw new IllegalArgumentException("Axis " + axis + " is greater than the number of dimensions (" + dims.length + ")");
		} else if (axis < 0) {
			throw new IllegalArgumentException("Axis " + axis + " is less than 0");
		}

		p.traversalAxis = axis;
		return p;
	}

	/**
	 * Returns a policy with the traversal axis set to the innermost dimension (traverse each element).
	 *
	 * @return the policy traversing each element individually
	 */
	public TraversalPolicy traverseEach() {
		return traverse(getDimensions());
	}

	/**
	 * Returns a copy of this policy with the given traversal ordering composed on top of the
	 * existing ordering.
	 *
	 * @param order the traversal ordering to apply, or {@code null} to use the current ordering
	 * @return the updated policy
	 */
	public TraversalPolicy traverse(TraversalOrdering order) {
		TraversalPolicy p = new TraversalPolicy(
				order == null ? getOrder() : order.compose(getOrder()),
				true, true, dims, dimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a copy of this policy with the traversal ordering replaced by the given ordering.
	 *
	 * @param order the new traversal ordering
	 * @return this policy if the ordering is unchanged, otherwise a new policy
	 */
	public TraversalPolicy withOrder(TraversalOrdering order) {
		if (this.order == order) return this;
		return new TraversalPolicy(
					order, true, true,
					dims, dimsOrder, rateNumerator, rateDenominator, fixed)
				.traverse(traversalAxis);
	}

	/**
	 * Returns a copy of this policy with the traversal rate for one axis multiplied by the
	 * given numerator and denominator.
	 *
	 * @param axis        the dimension axis to modify
	 * @param numerator   the additional rate numerator to multiply in
	 * @param denominator the additional rate denominator to multiply in
	 * @return a new policy with the updated rate
	 * @throws IllegalArgumentException if the axis is out of bounds
	 */
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
				dims, dimsOrder, newNumerator, newDenominator, fixed)
				.traverse(traversalAxis);
	}

	/**
	 * Returns a copy of this policy with the traversal rates set so that the input dimensions
	 * match the given sizes.
	 *
	 * @param dims the desired input dimension sizes
	 * @return a new policy with rates adjusted for the given input dimensions
	 * @throws IllegalArgumentException if the length of {@code dims} does not match the number of dimensions
	 */
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

	/**
	 * Returns a policy in which the current traversal axis repeats {@code count} times.
	 *
	 * @param count the number of repetitions
	 * @return the repeated policy
	 */
	public TraversalPolicy repeat(int count) {
		return repeat(getTraversalAxis(), count);
	}

	/**
	 * Returns a policy in which the given axis repeats {@code count} times.
	 *
	 * @param axis  the dimension axis to repeat
	 * @param count the number of repetitions
	 * @return the repeated policy
	 */
	public TraversalPolicy repeat(int axis, long count) {
		if (axis >= dims.length) {
			throw new IllegalArgumentException();
		}

		return replaceDimension(axis, count * length(axis))
				.withRate(axis, 1, count);
	}

	/**
	 * Returns a copy of this policy with a new dimension of the given size prepended at
	 * the beginning.
	 *
	 * @param size the size of the new leading dimension
	 * @return the extended policy
	 */
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
				newDims, newDimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis + 1;
		return p;
	}

	/**
	 * Returns a new policy that concatenates the dimensions of this policy with those
	 * of the given shape.
	 *
	 * @param shape the policy whose dimensions are appended
	 * @return the concatenated policy
	 */
	public TraversalPolicy append(TraversalPolicy shape) {
		long newDims[] = new long[getDimensions() + shape.getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = lengthLong(i);
		for (int i = 0; i < shape.getDimensions(); i++) newDims[i + getDimensions()] = shape.length(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, null, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a copy of this policy with one additional dimension of the given size appended
	 * at the end.
	 *
	 * @param size the size of the new trailing dimension
	 * @return the extended policy
	 */
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
				newDims, newDimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a copy of this policy with the traversal axis dimension replaced by the given size.
	 *
	 * @param size the new size for the traversal axis
	 * @return the updated policy
	 */
	public TraversalPolicy replaceDimension(int size) {
		return replaceDimension(getTraversalAxis(), size);
	}

	/**
	 * Returns a copy of this policy with the specified axis dimension replaced by the given size.
	 *
	 * @param axis the dimension axis to replace
	 * @param size the new size for that axis
	 * @return the updated policy
	 */
	public TraversalPolicy replaceDimension(int axis, long size) {
		long newDims[] = new long[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == axis ? size : lengthLong(i);

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, dimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a copy of this policy with a new dimension of the given size inserted at
	 * the traversal axis.
	 *
	 * @param size the size of the new dimension
	 * @return the updated policy
	 */
	public TraversalPolicy insertDimension(long size) {
		return insertDimension(getTraversalAxis(), size);
	}

	/**
	 * Returns a copy of this policy with a new dimension of the given size inserted at
	 * the specified axis.
	 *
	 * @param axis the position at which to insert the new dimension
	 * @param size the size of the new dimension
	 * @return the updated policy
	 */
	public TraversalPolicy insertDimension(int axis, long size) {
		// Create new dims with existing lengths plus new dimension
		long newDims[] = new long[getDimensions() + 1];
		for (int i = 0; i < axis; i++)
			newDims[i] = lengthLong(i);
		newDims[axis] = size;
		for (int i = axis; i < getDimensions(); i++)
			newDims[i + 1] = lengthLong(i);

		// Create new dimsOrder with existing mappings plus new dimension
		int newDimsOrder[] = new int[newDims.length];
		for (int i = 0; i < axis; i++)
			newDimsOrder[i] = dimsOrder[i] >= axis ? dimsOrder[i] + 1 : dimsOrder[i];
		newDimsOrder[axis] = axis;
		for (int i = axis; i < getDimensions(); i++)
			newDimsOrder[i + 1] = dimsOrder[i] >= axis ? dimsOrder[i] + 1 : dimsOrder[i];

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, newDimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a policy whose traversal axis dimension is set to {@code stride} and all
	 * other dimensions are set to zero.
	 *
	 * @param stride the stride size for the traversal axis
	 * @return the stride policy
	 */
	public TraversalPolicy stride(int stride) {
		long newDims[] = new long[getDimensions()];
		for (int i = 0; i < getDimensions(); i++) newDims[i] = i == traversalAxis ? stride : 0;

		TraversalPolicy p = new TraversalPolicy(
				order, true, false,
				newDims, dimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a policy containing only the dimensions starting at {@code depth}.
	 *
	 * @param depth the number of leading dimensions to drop
	 * @return the subset policy
	 */
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
				newDims, newDimsOrder, rateNumerator, rateDenominator, fixed);
		p.traversalAxis = traversalAxis > depth ? traversalAxis - depth : 0;
		return p;
	}

	/**
	 * Returns the shape of a single item at the traversal axis (dimensions from the
	 * traversal axis onward).
	 *
	 * @return the item shape
	 */
	public TraversalPolicy item() {
		if (traversalAxis == dims.length) return new TraversalPolicy(order, true, false, fixed);
		return new TraversalPolicy(Arrays.stream(dims, traversalAxis, dims.length).toArray());
	}

	/**
	 * Returns a copy of this policy where the item dimensions (from the traversal axis onward)
	 * are replaced by the given item shape.
	 *
	 * @param itemShape the new item shape to use from the traversal axis onward
	 * @return the updated policy
	 */
	public TraversalPolicy replace(TraversalPolicy itemShape) {
		long newDims[] = new long[traversalAxis + itemShape.getDimensions()];
		for (int i = 0; i < traversalAxis; i++) newDims[i] = lengthLong(i);
		for (int i = 0; i < itemShape.getDimensions(); i++) newDims[i + traversalAxis] = itemShape.lengthLong(i);

		TraversalPolicy p = new TraversalPolicy(order, newDims);
		p.traversalAxis = traversalAxis;
		return p;
	}

	/**
	 * Returns a copy of this policy with trailing dimensions of size {@code 1} removed.
	 *
	 * @return the trimmed policy, or this policy if no trailing unit dimensions exist
	 */
	public TraversalPolicy trim() {
		if (length(getDimensions() - 1) == 1) {
			return traverse(getDimensions() - 1)
					.replace(new TraversalPolicy(true))
					.traverse(getTraversalAxis())
					.trim();
		}

		return this;
	}

	/**
	 * Returns a policy with all leading dimensions collapsed into one, retaining the given
	 * trailing required dimensions.
	 *
	 * @param requiredDims the trailing dimension sizes that must be preserved
	 * @return the flattened policy
	 */
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

		return new TraversalPolicy(order, true, true, fixed, newDims)
						.traverse(axis);
	}

	/**
	 * Returns a one-dimensional policy whose single dimension is the total element count.
	 *
	 * @return the flattened policy
	 */
	public TraversalPolicy flatten() {
		return flatten(false);
	}

	/**
	 * Returns a policy flattened to one (or two) dimensions.
	 *
	 * <p>When {@code preserveCount} is {@code true}, the result is a two-dimensional policy
	 * with count and size dimensions separated at the traversal axis. Otherwise a single
	 * dimension containing all elements is produced.</p>
	 *
	 * @param preserveCount {@code true} to preserve the count/size separation
	 * @return the flattened policy
	 */
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

	/**
	 * Returns {@code true} if this {@link TraversalPolicy} has a fixed count,
	 * {@code false} if it has a variable count that may not be known until
	 * it is used in computation.
	 */
	@Override
	public boolean isFixedCount() { return fixed; }

	/**
	 * Returns the traversal axis index, which determines the boundary between the count
	 * and size dimensions.
	 *
	 * @return the traversal axis
	 */
	public int getTraversalAxis() { return traversalAxis; }

	/**
	 * Returns the size (number of elements per collection item) as an {@code int}.
	 *
	 * @return the size from the traversal axis onward
	 * @throws IllegalArgumentException if the traversal axis exceeds the number of dimensions
	 */
	public int getSize() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return size(traversalAxis);
	}

	/**
	 * Returns the size (number of elements per collection item) as a {@code long}.
	 *
	 * @return the size from the traversal axis onward
	 * @throws IllegalArgumentException if the traversal axis exceeds the number of dimensions
	 */
	public long getSizeLong() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return sizeLong(traversalAxis);
	}

	/**
	 * Returns the input size (number of input elements per collection item) as an {@code int}.
	 *
	 * @return the input size from the traversal axis onward
	 * @throws IllegalArgumentException if the traversal axis exceeds the number of dimensions
	 */
	public int getInputSize() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return inputSize(traversalAxis);
	}

	/**
	 * Returns the input size (number of input elements per collection item) as a {@code long}.
	 *
	 * @return the input size from the traversal axis onward
	 * @throws IllegalArgumentException if the traversal axis exceeds the number of dimensions
	 */
	public long getInputSizeLong() {
		if (traversalAxis > dims.length) {
			throw new IllegalArgumentException("Traversal axis is greater than the number of dimensions");
		}

		return inputSizeLong(traversalAxis);
	}

	/** Returns the total number of output elements across all dimensions. */
	public int getTotalSize() { return size(0); }

	/** Returns the total number of output elements across all dimensions as a {@code long}. */
	public long getTotalSizeLong() { return sizeLong(0); }

	/** Returns the total number of input elements across all dimensions. */
	public int getTotalInputSize() { return inputSize(0); }

	/** Returns the total number of input elements across all dimensions as a {@code long}. */
	public long getTotalInputSizeLong() { return inputSizeLong(0); }

	@Override
	public long getCountLong() {
		if (getSizeLong() == 0) {
			throw new UnsupportedOperationException();
		}

		return getTotalSizeLong() / getSizeLong();
	}

	/** Returns the number of dimensions in this policy. */
	public int getDimensions() { return dims.length; }

	/**
	 * Returns {@code true} if this policy has no traversal ordering and its input shape
	 * equals its output shape.
	 *
	 * @return {@code true} for a regular (identity-mapped) policy
	 */
	public boolean isRegular() {
		return getOrder() == null && inputShape().equalsIgnoreAxis(this);
	}

	// TODO  Rename to positions
	/**
	 * Returns a stream of all multi-dimensional positions in this policy's output space.
	 *
	 * @return a stream of position arrays, one per element
	 */
	public Stream<int[]> stream() {
		if (isRegular()) {
			return IntStream.range(0, getTotalSize()).mapToObj(this::position);
		}

		return new TraversalPolicy(dims).stream();
	}

	/**
	 * Returns a stream of all multi-dimensional positions in the input space.
	 *
	 * @return a stream of input position arrays, one per element
	 */
	public Stream<int[]> inputPositions() {
		if (isRegular()) {
			return stream();
		}

		return IntStream.range(0, getTotalSize())
				.map(this::inputIndex)
				.mapToObj(this::position);
	}

	/**
	 * Returns an {@link IntStream} of all flat indices in this policy's output space.
	 *
	 * @return a stream of flat index values
	 */
	public IntStream indices() {
		if (isRegular()) {
			return IntStream.range(0, getTotalSize());
		}

		return stream().mapToInt(this::index);
	}

	/**
	 * Serialises this policy to a {@link DataOutputStream}.
	 *
	 * @param dos the output stream to write to
	 * @throws IOException if an I/O error occurs
	 * @throws UnsupportedOperationException if this policy has a non-null traversal ordering
	 */
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

	/**
	 * Returns the axes at which this policy and the given policy have different lengths.
	 *
	 * @param p the policy to compare against
	 * @return an array of axis indices where the lengths differ
	 * @throws IllegalArgumentException if the number of dimensions differs
	 */
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

	/**
	 * Returns {@code true} if this policy has the same dimensions, order, and rates as the
	 * given policy, ignoring the traversal axis.
	 *
	 * @param p the policy to compare against
	 * @return {@code true} if all fields except the traversal axis are equal
	 */
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

	// TODO  This should include a description of the input space if it differs from the output space
	/**
	 * Returns a detailed string describing this policy including the traversal axis, count, and size.
	 *
	 * @return the detail string
	 */
	public String toStringDetail() {
		if (getSizeLong() == 0) {
			return this + "[axis=" + getTraversalAxis() + "]";
		}

		String dim = getCountLong() + "x" + getSizeLong();
		if (!fixed) dim = "x" + dim;

		return this + "[axis=" + getTraversalAxis() + "|" + dim + "]";
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

	/**
	 * Creates a policy with the given number of dimensions, each of the specified length.
	 *
	 * @param length     the size of each dimension
	 * @param dimensions the number of dimensions
	 * @return a uniform policy
	 */
	public static TraversalPolicy uniform(int length, int dimensions) {
		return new TraversalPolicy(IntStream.range(0, dimensions).map(i -> length).toArray());
	}

	/**
	 * Deserialises a {@link TraversalPolicy} from a {@link DataInputStream}.
	 *
	 * @param in the input stream to read from
	 * @return the deserialised policy
	 * @throws IOException if an I/O error occurs
	 */
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

	/**
	 * Aligns the traversal axes of multiple values with their associated shapes so that all
	 * values have a compatible traversal axis.
	 *
	 * <p>Uses {@link #enableStrictSizes} to determine whether identical total sizes are required.</p>
	 *
	 * @param <T>               the result type produced by {@code resultProcessor}
	 * @param <V>               the value type being aligned
	 * @param shapes            the shapes corresponding to each value
	 * @param values            the values to align
	 * @param traversalFunction a function that adjusts a value to a given traversal axis
	 * @param expandFunction    a function that expands a value to a given repeat count
	 * @param resultProcessor   a function that produces the final result from the aligned shape and values
	 * @return the aligned result, or {@code null} if no compatible axis was found
	 */
	public static <T, V> T alignTraversalAxes(List<TraversalPolicy> shapes, List<V> values,
											  BiFunction<Integer, V, V> traversalFunction,
											  BiFunction<Integer, V, V> expandFunction,
											  BiFunction<TraversalPolicy, List<V>, T> resultProcessor) {
		return alignTraversalAxes(shapes, values,
				enableStrictSizes || shapes.stream().noneMatch(s -> s.getDimensions() > 1),
				traversalFunction, expandFunction, resultProcessor);
	}

	/**
	 * Aligns the traversal axes of multiple values with their associated shapes, with explicit
	 * control over whether identical total sizes are required.
	 *
	 * @param <T>                        the result type produced by {@code resultProcessor}
	 * @param <V>                        the value type being aligned
	 * @param shapes                     the shapes corresponding to each value
	 * @param values                     the values to align
	 * @param requireIdenticalTotalSize  {@code true} to require all shapes to have the same total size
	 * @param traversalFunction          a function that adjusts a value to a given traversal axis
	 * @param expandFunction             a function that expands a value to a given repeat count
	 * @param resultProcessor            a function that produces the final result from the aligned shape and values
	 * @return the aligned result, or {@code null} if no compatible axis was found
	 */
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

	/**
	 * Returns the axis of {@code shape} at which its size matches the size of {@code target},
	 * optionally requiring the total sizes to match as well.
	 *
	 * @param shape                     the shape to search for a compatible axis
	 * @param target                    the target shape whose size to match
	 * @param requireIdenticalTotalSize {@code true} to also require equal total sizes
	 * @return the compatible axis index, or {@code -1} if none is found
	 */
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

	/**
	 * Returns the number of leading dimensions that match between {@code shape} and {@code target}.
	 *
	 * @param shape  the shape to examine
	 * @param target the target shape to compare against
	 * @return the number of leading dimensions with equal lengths
	 */
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