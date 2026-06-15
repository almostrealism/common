/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.algebra.computations.ScalarMatrixComputation;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.AtomicConstantComputation;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.collect.computations.CollectionExponentComputation;
import org.almostrealism.collect.computations.CollectionExponentialComputation;
import org.almostrealism.collect.computations.CollectionLogarithmComputation;
import org.almostrealism.collect.computations.CollectionMinusComputation;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.EpsilonConstantComputation;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.SingleConstantComputation;
import org.almostrealism.io.Console;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Factory interface for arithmetic and mathematical operations on collections.
 * This interface provides methods for addition, subtraction, multiplication,
 * division, negation, exponentiation, as well as mathematical functions like
 * exp, log, floor, min, max, abs, and sigmoid.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface ArithmeticFeatures extends SlicingFeatures, ExpressionFeatures {

	/** Shared console for logging arithmetic-related messages and warnings. */
	Console console = CollectionFeatures.console;

	/**
	 * Performs element-wise addition of two collections.
	 * This is one of the fundamental arithmetic operations for collections,
	 * adding corresponding elements from each input collection.
	 *
	 * @param a the first collection to add
	 * @param b the second collection to add
	 * @return a CollectionProducer that generates the element-wise sum
	 * 
	 *
	 * <pre>{@code
	 * // Add two vectors element-wise
	 * CollectionProducer vec1 = c(1.0, 2.0, 3.0);
	 * CollectionProducer vec2 = c(4.0, 5.0, 6.0);
	 * CollectionProducer sum = add(vec1, vec2);
	 * // Result: Producer that generates [5.0, 7.0, 9.0]
	 * 
	 * // Add a constant to a vector
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer constant = constant(1.0);
	 * CollectionProducer result = add(vector, constant);
	 * // Result: Producer that generates [2.0, 3.0, 4.0]
	 * }</pre>
	 */
	default <A extends PackedCollection, B extends PackedCollection> CollectionProducer add(Producer<A> a, Producer<B> b) {
		return add(List.of(a, b));
	}

	/**
	 * Performs element-wise addition of multiple collections.
	 * This method can add any number of collections together by summing
	 * corresponding elements across all input collections.
	 * 
	 * <p>This method includes optimizations for constant operations:
	 * when all operands are {@link SingleConstantComputation} instances,
	 * the method computes the sum directly and returns a new constant
	 * computation, avoiding the overhead of the full computation pipeline.</p>
	 *
	 * @param operands the list of collections to add together
	 * @return a CollectionProducer that generates the element-wise sum
	 * @throws IllegalArgumentException if any operand is null
	 * 
	 * @see SingleConstantComputation
	 *
	 * <pre>{@code
	 * // Add three vectors together
	 * CollectionProducer vec1 = c(1.0, 2.0);
	 * CollectionProducer vec2 = c(3.0, 4.0);
	 * CollectionProducer vec3 = c(5.0, 6.0);
	 * CollectionProducer sum = add(List.of(vec1, vec2, vec3));
	 * // Result: Producer that generates [9.0, 12.0] (1+3+5, 2+4+6)
	 * 
	 * // Add multiple constants (optimized)
	 * List<Producer<?>> constants = List.of(
	 *     constant(1.0), constant(2.0), constant(3.0)
	 * );
	 * CollectionProducer total = add(constants);
	 * // Result: Producer that generates [6.0] (computed at construction time)
	 * }</pre>
	 */
	default CollectionProducer add(List<Producer<?>> operands) {
		if (operands.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException();
		}

		if (operands.stream().allMatch(o -> o instanceof SingleConstantComputation)) {
			double value = operands.stream().mapToDouble(o -> ((SingleConstantComputation) o).getConstantValue()).sum();

			return CollectionFeatures.getInstance().compute((shape, args) -> new SingleConstantComputation(shape, value),
					args -> String.join(" + ", CollectionFeatures.getInstance().applyParentheses(args)),
					operands.toArray(new Producer[0]));
		}

		return CollectionFeatures.getInstance().compute((shape, args) -> {
					Producer[] p = args.stream().filter(Predicate.not(Algebraic::isZero)).toArray(Producer[]::new);

					if (p.length == 0) {
						return zeros(shape);
					} else if (p.length == 1) {
						return CollectionFeatures.getInstance().c(reshape(shape, p[0]));
					}

					return new CollectionAddComputation(shape, p);
				},
				args -> String.join(" + ", CollectionFeatures.getInstance().applyParentheses(args)),
				operands.toArray(new Producer[0]));
	}

	/**
	 * Performs element-wise subtraction of two collections.
	 * This operation subtracts corresponding elements of the second collection
	 * from the first collection, equivalent to {@link #add add(a, minus(b))}.
	 *
	 * @param a the collection to subtract from (minuend)
	 * @param b the collection to subtract (subtrahend)
	 * @return a {@link CollectionProducer} that generates the element-wise difference
	 * 
	 *
	 * <pre>{@code
	 * // Subtract two vectors element-wise
	 * CollectionProducer vec1 = c(5.0, 8.0, 12.0);
	 * CollectionProducer vec2 = c(2.0, 3.0, 4.0);
	 * CollectionProducer difference = subtract(vec1, vec2);
	 * // Result: Producer that generates [3.0, 5.0, 8.0] (5-2, 8-3, 12-4)
	 * 
	 * // Subtract a constant from a vector
	 * CollectionProducer vector = c(10.0, 20.0, 30.0);
	 * CollectionProducer constant = constant(5.0);
	 * CollectionProducer result = subtract(vector, constant);
	 * // Result: Producer that generates [5.0, 15.0, 25.0]
	 * }</pre>
	 */
	default CollectionProducer subtract(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return add(a, minus(b));
	}

	/**
	 * Performs element-wise subtraction while ignoring operations that would result in zero.
	 * This method uses epsilon-based floating-point comparison to determine when the operands
	 * are effectively equal, avoiding unnecessary computation in those cases.
	 * 
	 * <p>The implementation uses {@link EpsilonConstantComputation} to create a tolerance threshold
	 * for floating-point equality comparison. When two values are equal within epsilon tolerance,
	 * the subtraction is skipped and the original value is preserved rather than computing a
	 * potentially inaccurate zero result.</p>
	 * 
	 * <p>This is particularly useful in numerical computations where:</p>
	 * <ul>
	 *   <li>Floating-point precision errors might cause (a - a) to not equal exactly 0.0</li>
	 *   <li>Avoiding unnecessary computation when operands are effectively equal</li>
	 *   <li>Maintaining numerical stability in iterative algorithms</li>
	 * </ul>
	 * 
	 *
	 * @param a the minuend (value to subtract from)
	 * @param b the subtrahend (value to subtract)
	 * @return a {@link CollectionProducerComputation} that performs epsilon-aware subtraction
	 * 
	 * @see EpsilonConstantComputation
	 * @see #equals(Producer, Producer, Producer, Producer)
	 */
	default CollectionProducerComputation subtractIgnoreZero(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape = shape(a);
		int size = shape(b).getSize();

		if (shape.getSize() != size) {
			if (shape.getSize() == 1) {
				return subtractIgnoreZero(a, traverseEach(b));
			} else if (size == 1) {
				return subtractIgnoreZero(traverseEach(a), b);
			}

			throw new IllegalArgumentException("Cannot subtract a collection of size " + size +
					" from a collection of size " + shape.getSize());
		}

		CollectionProducer difference = CollectionFeatures.getInstance().equals(a, b, new EpsilonConstantComputation(shape), add(a, minus(b)));
		return (CollectionProducerComputation) CollectionFeatures.getInstance().equals(a, c(0.0), zeros(shape), difference);
	}

	/**
	 * Performs element-wise multiplication of two collections.
	 * This is a fundamental arithmetic operation that multiplies corresponding
	 * elements from each input collection.
	 * 
	 *
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 * 
	 *
	 * <pre>{@code
	 * // Multiply two vectors element-wise
	 * CollectionProducer vec1 = c(2.0, 3.0, 4.0);
	 * CollectionProducer vec2 = c(5.0, 6.0, 7.0);
	 * CollectionProducer product = multiply(vec1, vec2);
	 * // Result: Producer that generates [10.0, 18.0, 28.0]
	 * 
	 * // Scale a vector by a constant
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer scale = constant(2.0);
	 * CollectionProducer scaled = multiply(vector, scale);
	 * // Result: Producer that generates [2.0, 4.0, 6.0]
	 * }</pre>
	 */
	default CollectionProducer multiply(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return multiply(a, b, null);
	}

	/**
	 * Performs element-wise multiplication with an optional short-circuit evaluation.
	 * This overload allows for optimization by providing a pre-computed result
	 * that can be used instead of performing the actual computation.
	 * 
	 * <p>This method includes several optimizations:</p>
	 * <ul>
	 *   <li>Identity element detection (multiplication by 1.0)</li>
	 *   <li>Constant multiplication optimization using {@link SingleConstantComputation}</li>
	 *   <li>When both operands are constants, computes the result directly</li>
	 *   <li>Scalar constant multiplication optimization</li>
	 * </ul>
	 * 
	 *
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @param shortCircuit optional pre-computed result for optimization
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 * 
	 * @see SingleConstantComputation
	 *
	 * <pre>{@code
	 * // Multiply with potential optimization
	 * CollectionProducer vec1 = c(2.0, 3.0);
	 * CollectionProducer vec2 = c(1.0, 1.0);
	 * 
	 * // Pre-compute result for optimization
	 * Evaluable<PackedCollection> precomputed = () -> pack(2.0, 3.0);
	 * CollectionProducer result = multiply(vec1, vec2, precomputed);
	 * // May use precomputed result if beneficial
	 * 
	 * // Constant multiplication (optimized)
	 * CollectionProducer constant1 = constant(2.0);
	 * CollectionProducer constant2 = constant(3.0);
	 * CollectionProducer product = multiply(constant1, constant2);
	 * // Result: constant(6.0) computed directly without full pipeline
	 * }</pre>
	 */
	default CollectionProducer multiply(
			Producer<PackedCollection> a, Producer<PackedCollection> b,
			Evaluable<PackedCollection> shortCircuit) {
		// anything * 0 = 0: collapse to a zeros computation eagerly so the *result* is recognized
		// as zero (Algebraic.isZero) and propagates through the graph. Otherwise the zero detection
		// below happens inside the product expression, leaving a product wrapper around a zeros that
		// the optimizer must carry and isolate — a zeros must never become an isolated child.
		if (Algebraic.isZero(a) || Algebraic.isZero(b)) {
			return zeros(outputShape(a, b));
		}

		if (checkComputable(a) && checkComputable(b)) {
			if (shape(a).getTotalSizeLong() == 1 && Algebraic.isIdentity(1, a)) {
				return withShortCircuit(CollectionFeatures.getInstance().c(b), shortCircuit);
			} else if (shape(b).getTotalSizeLong() == 1 && Algebraic.isIdentity(1, b)) {
				return withShortCircuit(CollectionFeatures.getInstance().c(a), shortCircuit);
			} else if (a instanceof SingleConstantComputation && b instanceof SingleConstantComputation) {
				double value = ((SingleConstantComputation) a).getConstantValue() * ((SingleConstantComputation) b).getConstantValue();
				return constant(outputShape(a, b), value);
			}

			if (a instanceof SingleConstantComputation) {
				CollectionProducer result = multiply(((SingleConstantComputation) a).getConstantValue(), b);
				if (result != null) return withShortCircuit(result, shortCircuit);
			}

			if (b instanceof SingleConstantComputation) {
				CollectionProducer result = multiply(((SingleConstantComputation) b).getConstantValue(), a);
				if (result != null) return withShortCircuit(result, shortCircuit);
			}
		}

		return withShortCircuit(CollectionFeatures.getInstance().compute((shape, args) -> {
					if (args.stream().anyMatch(Algebraic::isZero)) {
						// Mathematical optimization: anything * 0 = 0
						// Returns CollectionZerosComputation to avoid unnecessary computation
						return zeros(shape);
					}

					return (Producer<PackedCollection>) new CollectionProductComputation(shape, args.toArray(new Producer[0]));
				},
				args -> String.join(" * ", CollectionFeatures.getInstance().applyParentheses(args)), a, b), shortCircuit);
	}

	/**
	 * Multiplies a collection by a scalar value.
	 * This is an optimized operation for scaling all elements of a collection
	 * by the same constant factor.
	 *
	 * @param scale the scalar value to multiply by
	 * @param a the collection to scale
	 * @return a {@link CollectionProducer} that generates the scaled collection, or null if no optimization available
	 * 
	 *
	 * <pre>{@code
	 * // Scale a vector by 2
	 * CollectionProducer vector = c(1.0, 2.0, 3.0);
	 * CollectionProducer doubled = multiply(2.0, vector);
	 * // Result: Producer that generates [2.0, 4.0, 6.0]
	 * 
	 * // Scale by zero to create zero vector
	 * CollectionProducer zeros = multiply(0.0, vector);
	 * // Result: Producer that generates [0.0, 0.0, 0.0]
	 * 
	 * // Scale by -1 to negate
	 * CollectionProducer negated = multiply(-1.0, vector);
	 * // Result: Producer that generates [-1.0, -2.0, -3.0]
	 * }</pre>
	 */
	default CollectionProducer multiply(double scale, Producer<PackedCollection> a) {
		if (scale == 0) {
			// Mathematical optimization: 0 * anything = 0
			// Returns CollectionZerosComputation with same shape as input
			return zeros(shape(a));
		} else if (scale == 1.0) {
			return CollectionFeatures.getInstance().c(a);
		} else if (scale == -1.0) {
			return minus(a);
		} else if (a instanceof ArithmeticSequenceComputation) {
			return ((ArithmeticSequenceComputation) a).multiply(scale);
		} else if (a.isConstant()) {
			return multiply(shape(a), scale, a.get());
		} else {
			return null;
		}
	}

	/**
	 * Multiplies all elements of an evaluated collection by a scalar value and returns
	 * the result as a constant {@link CollectionProducer} with the given shape.
	 * This method evaluates {@code a} immediately and embeds the scaled values as constants.
	 *
	 * @param shape the {@link TraversalPolicy} describing the shape of the result
	 * @param scale the scalar multiplier applied to each element
	 * @param a     the evaluable collection whose elements are scaled
	 * @return a {@link CollectionProducer} containing the scaled constant values
	 */
	default CollectionProducer multiply(TraversalPolicy shape, double scale, Evaluable<PackedCollection> a) {
		return c(shape, a.evaluate().doubleStream().parallel().map(d -> d * scale).toArray());
	}

	/**
	 * Performs element-wise division of two collections.
	 * This operation divides corresponding elements of the first collection
	 * by the corresponding elements of the second collection.
	 *
	 * @param a the dividend collection (numerator)
	 * @param b the divisor collection (denominator)
	 * @return a {@link CollectionProducer} that generates the element-wise quotient
	 * @throws UnsupportedOperationException if attempting to divide by zero
	 * 
	 *
	 * <pre>{@code
	 * // Divide two vectors element-wise
	 * CollectionProducer numerator = c(12.0, 15.0, 20.0);
	 * CollectionProducer denominator = c(3.0, 5.0, 4.0);
	 * CollectionProducer quotient = divide(numerator, denominator);
	 * // Result: Producer that generates [4.0, 3.0, 5.0] (12/3, 15/5, 20/4)
	 * 
	 * // Divide by a constant (scalar division)
	 * CollectionProducer vector = c(10.0, 20.0, 30.0);
	 * CollectionProducer divisor = constant(2.0);
	 * CollectionProducer halved = divide(vector, divisor);
	 * // Result: Producer that generates [5.0, 10.0, 15.0]
	 * }</pre>
	 */
	default CollectionProducer divide(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		if (Algebraic.isZero(b)) {
			throw new UnsupportedOperationException();
		} else if (Algebraic.isZero(a)) {
			// Mathematical optimization: 0 / anything = 0
			// Returns CollectionZerosComputation for efficiency
			return zeros(outputShape(a, b));
		}

		CollectionProducer p = CollectionFeatures.getInstance().compute("divide",
				shape -> (args) ->
						quotient(shape, Stream.of(args).skip(1).toArray(TraversableExpression[]::new)),
				(List<String> args) -> String.join(" / ", CollectionFeatures.getInstance().applyParentheses(args)), a, b);

		return CollectionProducerComputationBase.assignDeltaAlternate(
				p, multiply(a, pow(b, c(-1.0))));
	}

	/**
	 * Negates all elements in a collection (unary minus operation).
	 * This operation multiplies every element by -1, effectively flipping
	 * the sign of all values in the collection.
	 *
	 *
	 * @param a   the collection to negate
	 * @return a {@link CollectionProducerComputationBase} that generates the negated collection
	 *
	 *
	 * <pre>{@code
	 * // Negate a vector
	 * CollectionProducer vector = c(1.0, -2.0, 3.0, -4.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negated = minus(vector);
	 * // Result: Producer that generates [-1.0, 2.0, -3.0, 4.0]
	 *
	 * // Negate a constant (optimized case)
	 * CollectionProducer constant = constant(5.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negatedConstant = minus(constant);
	 * // Result: Producer that generates [-5.0]
	 *
	 * // Negate a matrix
	 * CollectionProducer matrix = c(shape(2, 2), 1.0, 2.0, 3.0, 4.0);
	 * CollectionProducerComputationBase<PackedCollection, PackedCollection> negatedMatrix = minus(matrix);
	 * // Result: Producer that generates 2x2 matrix [[-1,-2], [-3,-4]]
	 * }</pre>
	 */
	default CollectionProducer minus(Producer<PackedCollection> a) {
		TraversalPolicy shape = shape(a);
		int w = shape.length(0);

		if (shape.getTotalSizeLong() == 1 && a.isConstant() && Countable.isFixedCount(a)) {
			return new AtomicConstantComputation(-a.get().evaluate().toDouble());
		} else if (Algebraic.isIdentity(w, a)) {
			return new ScalarMatrixComputation(shape, c(-1))
				.setDescription(args -> "-" + DescribableParent.description(a));
		}

		return new CollectionMinusComputation(shape, a)
				.setDescription(args -> "-" + args.get(0));
	}

	/**
	 * Computes the square root of each element in a collection.
	 * This is a convenience method that raises each element to the power of 0.5,
	 * providing a more readable way to compute square roots.
	 *
	 * @param value the collection containing values to compute square roots for
	 * @return a {@link CollectionProducer} that generates the element-wise square roots
	 * 
	 *
	 * <pre>{@code
	 * // Compute square roots of elements
	 * CollectionProducer values = c(4.0, 9.0, 16.0, 25.0);
	 * CollectionProducer roots = sqrt(values);
	 * // Result: Producer that generates [2.0, 3.0, 4.0, 5.0]
	 * 
	 * // Square root of a single value
	 * CollectionProducer number = c(64.0);
	 * CollectionProducer root = sqrt(number);
	 * // Result: Producer that generates [8.0]
	 * 
	 * // Square root in mathematical expressions
	 * CollectionProducer squares = c(1.0, 4.0, 9.0);
	 * CollectionProducer magnitude = sqrt(sum(squares));
	 * // Result: sqrt(1+4+9) = sqrt(14) ~= 3.74
	 * }</pre>
	 */
	default CollectionProducer sqrt(Producer<PackedCollection> value) {
		PackedCollection half = new PackedCollection(1);
		half.setMem(0.5);
		return pow(value, c(half));
	}

	/**
	 * Raises elements of the base collection to the power of corresponding elements in the exponent collection.
	 * This operation performs element-wise exponentiation, computing base[i]^exp[i] for each element.
	 *
	 * @param base the base collection (values to be raised to powers)
	 * @param exp the exponent collection (power values)
	 * @return a {@link CollectionProducer} that generates the element-wise power results
	 * 
	 *
	 * <pre>{@code
	 * // Raise elements to specified powers
	 * CollectionProducer base = c(2.0, 3.0, 4.0);
	 * CollectionProducer exponent = c(2.0, 3.0, 0.5);
	 * CollectionProducer powers = pow(base, exponent);
	 * // Result: Producer that generates [4.0, 27.0, 2.0] (2^2, 3^3, 4^0.5)
	 * 
	 * // Square all elements (power of 2)
	 * CollectionProducer values = c(1.0, 2.0, 3.0, 4.0);
	 * CollectionProducer two = constant(2.0);
	 * CollectionProducer squares = pow(values, two);
	 * // Result: Producer that generates [1.0, 4.0, 9.0, 16.0]
	 * 
	 * // Square root (power of 0.5)
	 * CollectionProducer numbers = c(4.0, 9.0, 16.0, 25.0);
	 * CollectionProducer half = constant(0.5);
	 * CollectionProducer roots = pow(numbers, half);
	 * // Result: Producer that generates [2.0, 3.0, 4.0, 5.0]
	 * }</pre>
	 */
	default CollectionProducer pow(Producer<PackedCollection> base, Producer<PackedCollection> exp) {
		if (Algebraic.isIdentity(1, base)) {
			TraversalPolicy shape = shape(exp);

			if (shape.getTotalSizeLong() == 1) {
				return (CollectionProducer) base;
			} else {
				return repeat(shape.getTotalSize(), base).reshape(shape);
			}
		} else if (base.isConstant() && exp.isConstant()) {
			if (shape(base).getTotalSizeLong() == 1 && shape(exp).getTotalSizeLong() == 1) {
				return c(Math.pow(base.get().evaluate().toDouble(), exp.get().evaluate().toDouble()));
			}

			console.warn("Computing power of constants");
		}

		return CollectionFeatures.getInstance().compute((shape, args) ->
						new CollectionExponentComputation(largestTotalSize(args), args.get(0), args.get(1)),
				args -> CollectionFeatures.getInstance().applyParentheses(args.get(0)) + " ^ " + CollectionFeatures.getInstance().applyParentheses(args.get(1)),
				base, exp);
	}

	/**
	 * Computes the element-wise square (x²) of a collection producer by multiplying
	 * the producer with itself.
	 *
	 * @param value the producer supplying the values to square
	 * @return a {@link CollectionProducer} computing the square of each element
	 */
	default CollectionProducer sq(Producer<PackedCollection> value) {
		return multiply(value, value);
	}

	/**
	 * Computes the element-wise natural exponential (e^x) of a collection producer.
	 * Zero values in the input are included in the computation.
	 *
	 * @param value the producer supplying the exponent values
	 * @return a {@link CollectionProducer} computing e raised to each element of {@code value}
	 */
	default CollectionProducer exp(Producer<PackedCollection> value) {
		return new CollectionExponentialComputation(shape(value), false, value);
	}

	/**
	 * Computes the element-wise natural exponential (e^x) of a collection producer,
	 * treating zero-valued inputs as a special case to be ignored rather than computed.
	 * This variant can improve numerical stability in contexts such as attention softmax.
	 *
	 * @param value the producer supplying the exponent values
	 * @return a {@link CollectionProducer} computing e raised to each element of {@code value},
	 *         with zero inputs bypassed
	 */
	default CollectionProducer expIgnoreZero(Producer<PackedCollection> value) {
		return new CollectionExponentialComputation(shape(value), true, value);
	}

	/**
	 * Computes the element-wise natural logarithm (ln x) of a collection producer.
	 *
	 * @param value the producer supplying the values to take the logarithm of
	 * @return a {@link CollectionProducer} computing the natural log of each element
	 */
	default CollectionProducer log(Producer<PackedCollection> value) {
		return new CollectionLogarithmComputation(shape(value), value);
	}

	/**
	 * Computes the element-wise floor (greatest integer less than or equal to x) of a collection producer.
	 *
	 * @param value the producer supplying the values to floor
	 * @return a {@link CollectionProducerComputationBase} computing the floor of each element
	 */
	default CollectionProducerComputationBase floor(Producer<PackedCollection> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation(
				"floor", shape,
				args -> new UniformCollectionExpression("floor", shape, in -> Floor.of(in[0]), args[1]),
				value);
	}

	/**
	 * Computes the element-wise minimum of two collection producers. When the two inputs have
	 * equal total sizes the output retains that shape; otherwise the output is a scalar shape.
	 *
	 * @param a the first collection producer
	 * @param b the second collection producer
	 * @return a {@link CollectionProducerComputationBase} producing the element-wise minimum of
	 *         {@code a} and {@code b}
	 */
	default CollectionProducerComputationBase min(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("min", shape,
				args -> new UniformCollectionExpression("min", shape,
								in -> Min.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	/**
	 * Computes the element-wise maximum of two collection producers. When the two inputs have
	 * equal total sizes the output retains that shape; otherwise the output is a scalar shape.
	 *
	 * @param a the first collection producer
	 * @param b the second collection producer
	 * @return a {@link CollectionProducerComputationBase} producing the element-wise maximum of
	 *         {@code a} and {@code b}
	 */
	default CollectionProducerComputationBase max(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("max", shape,
				args -> new UniformCollectionExpression("max", shape,
								in -> Max.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	/**
	 * Applies element-wise rectification (ReLU) to a collection producer, clamping all
	 * negative values to zero and leaving non-negative values unchanged.
	 *
	 * @param a the collection producer to rectify
	 * @return a {@link CollectionProducer} computing max(0, x) for each element of {@code a}
	 */
	default CollectionProducer rectify(Producer<PackedCollection> a) {
		return CollectionFeatures.getInstance().compute("rectify", shape -> args ->
						rectify(shape, args[1]), a);
	}

	/**
	 * Computes the element-wise modulo of two collection producers, returning the remainder
	 * of dividing each element of {@code a} by the corresponding element of {@code b}.
	 *
	 * @param a the dividend collection producer
	 * @param b the divisor collection producer
	 * @return a {@link CollectionProducer} computing {@code a mod b} element-wise
	 */
	default CollectionProducer mod(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return CollectionFeatures.getInstance().compute("mod", shape -> args ->
						mod(shape, args[1], args[2]), a, b);
	}

	/**
	 * Clamps each element of a collection producer to the closed interval [{@code min}, {@code max}].
	 * Values below {@code min} are raised to {@code min} and values above {@code max} are
	 * lowered to {@code max}.
	 *
	 * @param a   the collection producer whose elements are to be clamped
	 * @param min the lower bound of the clamp range
	 * @param max the upper bound of the clamp range
	 * @return a {@link CollectionProducerComputationBase} producing clamped element values
	 */
	default CollectionProducerComputationBase bound(Producer<PackedCollection> a, double min, double max) {
		return min(max(a, c(min)), c(max));
	}

	/**
	 * Computes the absolute value of each element in a collection.
	 * This operation converts all negative values to positive while
	 * leaving positive values unchanged.
	 *
	 * @param value the collection containing values to compute absolute values for
	 * @return a {@link CollectionProducer} that generates the element-wise absolute values
	 * 
	 *
	 * <pre>{@code
	 * // Compute absolute values
	 * CollectionProducer values = c(-3.0, -1.0, 0.0, 2.0, -5.0);
	 * CollectionProducer absolutes = abs(values);
	 * // Result: Producer that generates [3.0, 1.0, 0.0, 2.0, 5.0]
	 * 
	 * // Absolute value of differences
	 * CollectionProducer a = c(10.0, 5.0, 8.0);
	 * CollectionProducer b = c(7.0, 9.0, 3.0);
	 * CollectionProducer distance = abs(subtract(a, b));
	 * // Result: Producer that generates [3.0, 4.0, 5.0] (absolute differences)
	 * }</pre>
	 */
	default CollectionProducer abs(Producer<PackedCollection> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation(
				"abs", shape, DeltaFeatures.MultiTermDeltaStrategy.NONE, true,
				args -> new UniformCollectionExpression("abs", shape, in -> new Absolute(in[0]), args[1]),
				value);
	}

	/**
	 * Computes the Euclidean magnitude (L2 norm) of a vector producer. For a scalar
	 * (size 1) input the magnitude is the absolute value; for multi-element inputs the
	 * magnitude is computed as the square root of the sum of squared elements.
	 *
	 * @param vector the producer supplying the vector whose magnitude is computed
	 * @return a {@link CollectionProducer} computing the Euclidean magnitude
	 */
	default CollectionProducer magnitude(Producer<PackedCollection> vector) {
		if (shape(vector).getSize() == 1) {
			return abs(vector);
		} else {
			return sq(vector).sum().sqrt();
		}
	}

	/**
	 * Applies the sigmoid activation function element-wise to a collection producer.
	 * Sigmoid is defined as {@code 1 / (1 + e^(-x))}, mapping any real input to (0, 1).
	 *
	 * @param input the collection producer supplying the input values
	 * @return a {@link CollectionProducer} applying the sigmoid function to each element
	 */
	default CollectionProducer sigmoid(Producer<PackedCollection> input) {
		return divide(c(1.0), minus(input).exp().add(c(1.0)));
	}

	// Utility methods required for internal use
	/**
	 * Attaches a short-circuit evaluable to a collection producer computation so that, when
	 * short-circuit conditions are met, the provided evaluable is used instead of the full
	 * computation. If the producer is not a {@link CollectionProducerComputationBase} the
	 * short-circuit is silently ignored.
	 *
	 * @param <P>          the producer type
	 * @param producer     the producer to attach the short-circuit to
	 * @param shortCircuit an evaluable invoked in place of the full computation when applicable
	 * @return the same {@code producer} instance (with short-circuit attached if supported)
	 */
	default <P extends Producer<PackedCollection>> P withShortCircuit(P producer, Evaluable<PackedCollection> shortCircuit) {
		if (producer instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) producer).setShortCircuit(shortCircuit);
		}
		return producer;
	}

	/**
	 * Attaches a short-circuit evaluable to a {@link CollectionProducer} computation so that,
	 * when short-circuit conditions are met, the provided evaluable is used instead of the full
	 * computation. If the producer is not a {@link CollectionProducerComputationBase} the
	 * short-circuit is silently ignored.
	 *
	 * @param producer     the {@link CollectionProducer} to attach the short-circuit to
	 * @param shortCircuit an evaluable invoked in place of the full computation when applicable
	 * @return the same {@code producer} instance (with short-circuit attached if supported)
	 */
	default CollectionProducer withShortCircuit(CollectionProducer producer, Evaluable<PackedCollection> shortCircuit) {
		if (producer instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) producer).setShortCircuit(shortCircuit);
		}
		return producer;
	}

	/**
	 * Checks whether a producer is directly computable as a collection computation,
	 * meaning it can participate in kernel-level evaluation without additional wrapping.
	 * A producer is computable if it is a {@link CollectionProducerComputation}, a
	 * {@link CollectionProviderProducer}, or a {@link ReshapeProducer} wrapping a computable.
	 *
	 * @param p the producer to check
	 * @return {@code true} if the producer is computable as a collection computation
	 */
	static boolean checkComputable(Producer<?> p) {
		if (p instanceof CollectionProducerComputation || p instanceof CollectionProviderProducer) {
			return true;
		} else if (p instanceof ReshapeProducer) {
			return checkComputable(((ReshapeProducer) p).getComputation());
		} else {
			return false;
		}
	}
}
