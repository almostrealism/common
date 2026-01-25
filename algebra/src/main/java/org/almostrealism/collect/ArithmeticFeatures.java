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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.ScalarMatrixComputation;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.collect.computations.AtomicConstantComputation;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.collect.computations.CollectionExponentComputation;
import org.almostrealism.collect.computations.CollectionMinusComputation;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.CollectionProviderProducer;
import org.almostrealism.collect.computations.EpsilonConstantComputation;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.collect.computations.SingleConstantComputation;
import io.almostrealism.util.DescribableParent;
import org.almostrealism.io.Console;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Factory interface for basic arithmetic operations on collections.
 * This interface provides methods for addition, subtraction, multiplication,
 * division, negation, and exponentiation of collections.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface ArithmeticFeatures extends SlicingFeatures, ExpressionFeatures {

	Console console = CollectionFeatures.console;

	/**
	 * Performs element-wise addition of two collections.
	 *
	 * @param a the first collection to add
	 * @param b the second collection to add
	 * @return a CollectionProducer that generates the element-wise sum
	 */
	default <A extends PackedCollection, B extends PackedCollection> CollectionProducer add(Producer<A> a, Producer<B> b) {
		return add(List.of(a, b));
	}

	/**
	 * Performs element-wise addition of multiple collections.
	 *
	 * @param operands the list of collections to add together
	 * @return a CollectionProducer that generates the element-wise sum
	 * @throws IllegalArgumentException if any operand is null
	 */
	default CollectionProducer add(List<Producer<?>> operands) {
		if (operands.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException();
		}

		if (operands.stream().allMatch(o -> o instanceof SingleConstantComputation)) {
			double value = operands.stream().mapToDouble(o -> ((SingleConstantComputation) o).getConstantValue()).sum();

			return compute((shape, args) -> new SingleConstantComputation(shape, value),
					args -> String.join(" + ", applyParentheses(args)),
					operands.toArray(new Producer[0]));
		}

		return compute((shape, args) -> {
					Producer[] p = args.stream().filter(Predicate.not(Algebraic::isZero)).toArray(Producer[]::new);

					if (p.length == 0) {
						return zeros(shape);
					} else if (p.length == 1) {
						return c(reshape(shape, p[0]));
					}

					return new CollectionAddComputation(shape, p);
				},
				args -> String.join(" + ", applyParentheses(args)),
				operands.toArray(new Producer[0]));
	}

	/**
	 * Performs element-wise subtraction of two collections.
	 *
	 * @param a the collection to subtract from (minuend)
	 * @param b the collection to subtract (subtrahend)
	 * @return a {@link CollectionProducer} that generates the element-wise difference
	 */
	default CollectionProducer subtract(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return add(a, minus(b));
	}

	/**
	 * Performs element-wise subtraction while ignoring operations that would result in zero.
	 *
	 * @param a the minuend (value to subtract from)
	 * @param b the subtrahend (value to subtract)
	 * @return a {@link CollectionProducerComputation} that performs epsilon-aware subtraction
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

		CollectionProducer difference = equals(a, b, new EpsilonConstantComputation(shape), add(a, minus(b)));
		return (CollectionProducerComputation) equals(a, c(0.0), zeros(shape), difference);
	}

	/**
	 * Performs element-wise multiplication of two collections.
	 *
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 */
	default CollectionProducer multiply(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return multiply(a, b, null);
	}

	/**
	 * Performs element-wise multiplication with an optional short-circuit evaluation.
	 *
	 * @param a the first collection to multiply
	 * @param b the second collection to multiply
	 * @param shortCircuit optional pre-computed result for optimization
	 * @return a {@link CollectionProducer} that generates the element-wise product
	 */
	default CollectionProducer multiply(
			Producer<PackedCollection> a, Producer<PackedCollection> b,
			Evaluable<PackedCollection> shortCircuit) {
		if (checkComputable(a) && checkComputable(b)) {
			if (shape(a).getTotalSizeLong() == 1 && Algebraic.isIdentity(1, a)) {
				return withShortCircuit(c(b), shortCircuit);
			} else if (shape(b).getTotalSizeLong() == 1 && Algebraic.isIdentity(1, b)) {
				return withShortCircuit(c(a), shortCircuit);
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

		return withShortCircuit(compute((shape, args) -> {
					if (args.stream().anyMatch(Algebraic::isZero)) {
						return zeros(shape);
					}

					return (Producer<PackedCollection>) new CollectionProductComputation(shape, args.toArray(new Producer[0]));
				},
				args -> String.join(" * ", applyParentheses(args)), a, b), shortCircuit);
	}

	/**
	 * Multiplies a collection by a scalar value.
	 *
	 * @param scale the scalar value to multiply by
	 * @param a the collection to scale
	 * @return a {@link CollectionProducer} that generates the scaled collection, or null if no optimization available
	 */
	default CollectionProducer multiply(double scale, Producer<PackedCollection> a) {
		if (scale == 0) {
			return zeros(shape(a));
		} else if (scale == 1.0) {
			return c(a);
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

	default CollectionProducer multiply(TraversalPolicy shape, double scale, Evaluable<PackedCollection> a) {
		return c(shape, a.evaluate().doubleStream().parallel().map(d -> d * scale).toArray());
	}

	/**
	 * Performs element-wise division of two collections.
	 *
	 * @param a the dividend collection (numerator)
	 * @param b the divisor collection (denominator)
	 * @return a {@link CollectionProducer} that generates the element-wise quotient
	 * @throws UnsupportedOperationException if attempting to divide by zero
	 */
	default CollectionProducer divide(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		if (Algebraic.isZero(b)) {
			throw new UnsupportedOperationException();
		} else if (Algebraic.isZero(a)) {
			return zeros(outputShape(a, b));
		}

		CollectionProducer p = compute("divide",
				shape -> (args) ->
						quotient(shape, Stream.of(args).skip(1).toArray(io.almostrealism.collect.TraversableExpression[]::new)),
				(List<String> args) -> String.join(" / ", applyParentheses(args)), a, b);

		return CollectionProducerComputationBase.assignDeltaAlternate(
				p, multiply(a, pow(b, c(-1.0))));
	}

	/**
	 * Negates all elements in a collection (unary minus operation).
	 *
	 * @param a the collection to negate
	 * @return a {@link CollectionProducer} that generates the negated collection
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
	 *
	 * @param value the collection containing values to compute square roots for
	 * @return a {@link CollectionProducer} that generates the element-wise square roots
	 */
	default CollectionProducer sqrt(Producer<PackedCollection> value) {
		PackedCollection half = new PackedCollection(1);
		half.setMem(0.5);
		return pow(value, c(half));
	}

	/**
	 * Raises elements of the base collection to the power of corresponding elements in the exponent collection.
	 *
	 * @param base the base collection (values to be raised to powers)
	 * @param exp the exponent collection (power values)
	 * @return a {@link CollectionProducer} that generates the element-wise power results
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

		return compute((shape, args) ->
						new CollectionExponentComputation(largestTotalSize(args), args.get(0), args.get(1)),
				args -> applyParentheses(args.get(0)) + " ^ " + applyParentheses(args.get(1)),
				base, exp);
	}

	/**
	 * Computes the square of each element in a collection.
	 *
	 * @param value the collection to square
	 * @return a {@link CollectionProducer} that generates the squared values
	 */
	default CollectionProducer sq(Producer<PackedCollection> value) {
		return multiply(value, value);
	}

	// Utility methods required for internal use
	default <P extends Producer<PackedCollection>> P withShortCircuit(P producer, Evaluable<PackedCollection> shortCircuit) {
		if (producer instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) producer).setShortCircuit(shortCircuit);
		}
		return producer;
	}

	default CollectionProducer withShortCircuit(CollectionProducer producer, Evaluable<PackedCollection> shortCircuit) {
		if (producer instanceof CollectionProducerComputationBase) {
			((CollectionProducerComputationBase) producer).setShortCircuit(shortCircuit);
		}
		return producer;
	}

	static boolean checkComputable(Producer<?> p) {
		if (p instanceof CollectionProducerComputation || p instanceof CollectionProviderProducer) {
			return true;
		} else if (p instanceof ReshapeProducer) {
			return checkComputable(((ReshapeProducer) p).getComputation());
		} else {
			return false;
		}
	}

	// Required for internal use, to be overridden by CollectionFeatures
	CollectionProducer equals(Producer<PackedCollection> a, Producer<PackedCollection> b,
							  Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue);
	CollectionProducer constant(TraversalPolicy shape, double value);
	CollectionProducer c(double... values);
	CollectionProducer c(TraversalPolicy shape, double... values);
	CollectionProducer c(PackedCollection value);
	CollectionProducer c(Producer producer);
	CollectionProducerComputation zeros(TraversalPolicy shape);
	CollectionProducer compute(String name, java.util.function.Function<TraversalPolicy, java.util.function.Function<io.almostrealism.collect.TraversableExpression[], io.almostrealism.collect.CollectionExpression>> expression,
							   java.util.function.Function<List<String>, String> description, Producer<PackedCollection>... arguments);
	<P extends Producer<PackedCollection>> CollectionProducer compute(java.util.function.BiFunction<TraversalPolicy, List<Producer<PackedCollection>>, P> processor,
																	  java.util.function.Function<List<String>, String> description, Producer<PackedCollection>... arguments);
	List<String> applyParentheses(List<String> args);
	String applyParentheses(String value);
}
