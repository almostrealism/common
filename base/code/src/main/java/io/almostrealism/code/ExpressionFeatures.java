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

package io.almostrealism.code;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.ConstantCollectionExpression;
import io.almostrealism.collect.IdentityCollectionExpression;
import io.almostrealism.collect.ProductCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Cosine;
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Epsilon;
import io.almostrealism.expression.Equals;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Greater;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.MinimumValue;
import io.almostrealism.expression.Minus;
import io.almostrealism.expression.Mod;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Rectify;
import io.almostrealism.expression.Sine;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.expression.Sum;
import io.almostrealism.expression.Tangent;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.Collection;

/**
 * A mixin interface providing factory methods for building {@link Expression} trees and
 * {@link CollectionExpression} instances.
 *
 * <p>Classes that implement (or mix in) {@code ExpressionFeatures} gain convenient
 * short-hand methods for constructing constants, arithmetic, trigonometric, and
 * collection-level operations without directly instantiating the expression subclasses.
 * This is the primary API surface used by {@link Computation} implementations to build
 * their generated-code expression graphs.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // In a Computation or Producer implementation:
 * Expression sum = e(1).add(e(2));   // 1 + 2
 * CollectionExpression result = sin(shape, inputExpr);
 * }</pre>
 *
 * @see Expression
 * @see CollectionExpression
 * @see ExpressionAssignment
 */
public interface ExpressionFeatures {

	/**
	 * Creates a boolean constant expression.
	 *
	 * @param value the boolean value
	 * @return a boolean constant expression
	 */
	default Expression e(boolean value) {
		return new BooleanConstant(value);
	}

	/**
	 * Creates an integer constant expression.
	 *
	 * @param value the integer value
	 * @return an integer constant expression
	 */
	default Expression e(int value) {
		return new IntegerConstant(value);
	}

	/**
	 * Creates a double constant expression.
	 *
	 * @param value the double value
	 * @return a double constant expression
	 */
	default Expression<Double> expressionForDouble(double value) {
		return new DoubleConstant(value);
	}

	/**
	 * Creates a numeric constant expression for the given long value.
	 *
	 * <p>Returns an {@link IntegerConstant} if the value fits in an {@code int},
	 * otherwise returns a {@link LongConstant}.
	 *
	 * @param value the long value
	 * @return the appropriate numeric constant expression
	 */
	default Expression<? extends Number> e(long value) {
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			return new LongConstant(value);
		}

		return e(Math.toIntExact(value));
	}

	/**
	 * Creates a double constant expression.
	 *
	 * @param value the double value
	 * @return a double constant expression
	 */
	default Expression<Double> e(double value) {
		return expressionForDouble(value);
	}

	/**
	 * Creates an {@code exp(x)} expression.
	 *
	 * @param expression the exponent expression
	 * @return an expression computing {@code e^x}
	 */
	default Expression<Double> exp(Expression expression) {
		return Exp.of(expression);
	}

	/**
	 * Returns the machine epsilon constant for the current precision.
	 *
	 * @return an epsilon expression
	 */
	default Epsilon epsilon() { return new Epsilon(); }

	/**
	 * Returns the minimum representable floating-point value for the current precision.
	 *
	 * @return a minimum value expression
	 */
	default MinimumValue minValue() { return new MinimumValue(); }

	/**
	 * Creates a kernel index expression using the default kernel index.
	 *
	 * @return a kernel index expression
	 */
	default KernelIndex kernel() { return new KernelIndex(); }

	/**
	 * Creates a kernel index expression within the given kernel structure context.
	 *
	 * @param context the kernel structure context
	 * @return a kernel index expression
	 */
	default KernelIndex kernel(KernelStructureContext context) { return new KernelIndex(context); }

	/**
	 * Returns an expression for the mathematical constant pi ({@code π}).
	 *
	 * <p>The returned expression delegates to the {@link LanguageOperations#pi()} method
	 * of the target language.
	 *
	 * @return a pi expression
	 */
	default StaticReference<Double> pi() {
		return new StaticReference<>(Double.class, null) {
			@Override
			public String getExpression(LanguageOperations lang) {
				return lang.pi();
			}

			@Override
			public ExpressionAssignment<Double> assign(Expression exp) {
				throw new UnsupportedOperationException();
			}
		};
	}

	/**
	 * Creates a declaration-assignment that declares a named variable and assigns it the expression.
	 *
	 * @param <T> the type of the expression
	 * @param name the variable name
	 * @param expression the expression to assign
	 * @return a declaration-assignment statement
	 */
	default <T> ExpressionAssignment<T> declare(String name, Expression<T> expression) {
		return declare(new StaticReference<>(expression.getType(), name), expression);
	}

	/**
	 * Creates an assignment statement that assigns the expression to a named variable.
	 *
	 * @param <T> the type of the expression
	 * @param name the variable name
	 * @param expression the expression to assign
	 * @return an assignment statement
	 */
	default <T> ExpressionAssignment<T> assign(String name, Expression<T> expression) {
		return assign(new StaticReference<>(expression.getType(), name), expression);
	}

	/**
	 * Creates a declaration-assignment that declares the destination expression and assigns to it.
	 *
	 * @param <T> the type of the expression
	 * @param destination the destination expression (variable reference)
	 * @param expression the expression to assign
	 * @return a declaration-assignment statement
	 */
	default <T> ExpressionAssignment<T> declare(Expression<T> destination, Expression<T> expression) {
		return new ExpressionAssignment<>(true, destination, expression);
	}

	/**
	 * Creates a plain assignment statement that assigns the expression to the destination.
	 *
	 * @param <T> the type of the expression
	 * @param destination the destination expression (variable reference)
	 * @param expression the expression to assign
	 * @return an assignment statement
	 */
	default <T> ExpressionAssignment<T> assign(Expression<T> destination, Expression<T> expression) {
		return new ExpressionAssignment<>(destination, expression);
	}

	/**
	 * Creates a greater-than (or greater-than-or-equal-to) comparison expression.
	 *
	 * @param left the left operand
	 * @param right the right operand
	 * @param includeEqual whether to include the equal case ({@code >=} instead of {@code >})
	 * @return a comparison expression
	 */
	default Greater greater(Expression<?> left, Expression<?> right, boolean includeEqual) {
		return new Greater(left, right, includeEqual);
	}

	/**
	 * Creates an equality comparison expression.
	 *
	 * @param left the left operand
	 * @param right the right operand
	 * @return an equality expression
	 */
	default Expression equals(Expression<?> left, Expression<?> right) {
		return Equals.of(left, right);
	}

	/**
	 * Creates a conditional (ternary) expression: {@code condition ? positive : negative}.
	 *
	 * @param condition the boolean condition expression
	 * @param positive the expression to use when the condition is true
	 * @param negative the expression to use when the condition is false
	 * @return a conditional expression
	 */
	default Expression conditional(Expression<Boolean> condition, Expression<?> positive, Expression<?> negative) {
		return Conditional.of(condition, positive, negative);
	}

	/**
	 * Returns the identity matrix collection expression for the given shape.
	 *
	 * <p>If the shape has exactly one element, returns a constant 1. Otherwise returns an
	 * {@link IdentityCollectionExpression} with 1 on the diagonal and 0 elsewhere.
	 *
	 * @param shape the traversal policy defining the matrix shape
	 * @return the identity collection expression
	 */
	default CollectionExpression ident(TraversalPolicy shape) {
		if (shape.getTotalSizeLong() == 1) {
			return new ConstantCollectionExpression(shape, new IntegerConstant(1));
		} else {
			return new IdentityCollectionExpression(shape);
		}
	}

	/**
	 * Creates a collection expression that sums the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the collection of operand expressions to sum
	 * @return a sum collection expression
	 */
	default CollectionExpression sum(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return sum(shape, expressions.toArray(TraversableExpression[]::new));
	}

	/**
	 * Creates a collection expression that sums the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the operand expressions to sum
	 * @return a sum collection expression
	 */
	default CollectionExpression sum(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression sum = new UniformCollectionExpression("sum", shape, Sum::of, expressions);
		sum.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.EXCLUSIVE);
		return sum;
	}

	/**
	 * Creates a collection expression that subtracts the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the collection of operand expressions
	 * @return a difference collection expression
	 */
	default CollectionExpression difference(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return difference(shape, expressions.toArray(TraversableExpression[]::new));
	}

	/**
	 * Creates a collection expression that subtracts the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the operand expressions
	 * @return a difference collection expression
	 */
	default CollectionExpression difference(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression difference = new UniformCollectionExpression("difference", shape, Difference::of, expressions);
		difference.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.EXCLUSIVE);
		return difference;
	}

	/**
	 * Creates a collection expression that multiplies the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the collection of operand expressions to multiply
	 * @return a product collection expression
	 */
	default CollectionExpression product(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return product(shape, expressions.toArray(TraversableExpression[]::new));
	}

	/**
	 * Creates a collection expression that multiplies the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the operand expressions to multiply (requires at least 2)
	 * @return a product collection expression
	 * @throws IllegalArgumentException if fewer than 2 expressions are provided
	 */
	default CollectionExpression product(TraversalPolicy shape, TraversableExpression... expressions) {
		if (expressions.length < 2) throw new IllegalArgumentException();
		return new ProductCollectionExpression(shape, expressions);
	}

	/**
	 * Creates a collection expression that divides the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the collection of operand expressions
	 * @return a quotient collection expression
	 */
	default CollectionExpression quotient(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return quotient(shape, expressions.toArray(TraversableExpression[]::new));
	}

	/**
	 * Creates a collection expression that divides the given expressions element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param expressions the operand expressions
	 * @return a quotient collection expression
	 */
	default CollectionExpression quotient(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression quotient = new UniformCollectionExpression("quotient", shape, Quotient::of, expressions);
		quotient.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.DISJUNCTIVE);
		return quotient;
	}

	/**
	 * Creates a collection expression that computes {@code 1/x} element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a reciprocal collection expression
	 */
	default CollectionExpression reciprocal(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("reciprocal", shape, args -> args[0].reciprocal(), input);
	}

	/**
	 * Creates a collection expression that negates each element ({@code -x}).
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a negation collection expression
	 */
	default CollectionExpression minus(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("minus", shape, args -> Minus.of(args[0]), input);
	}

	/**
	 * Creates a collection expression that applies modulo element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param in the dividend expression
	 * @param mod the divisor (modulus) expression
	 * @return a modulo collection expression
	 */
	default CollectionExpression mod(TraversalPolicy shape, TraversableExpression in, TraversableExpression mod) {
		return new UniformCollectionExpression("mod", shape, Mod::of, in, mod);
	}

	/**
	 * Creates a collection expression that applies {@code sin(x)} element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a sine collection expression
	 */
	default CollectionExpression sin(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("sin", shape, args -> Sine.of(args[0]), input);
	}

	/**
	 * Creates a collection expression that applies {@code cos(x)} element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a cosine collection expression
	 */
	default CollectionExpression cos(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("cos", shape, args -> Cosine.of(args[0]), input);
	}

	/**
	 * Creates a collection expression that applies {@code tan(x)} element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a tangent collection expression
	 */
	default CollectionExpression tan(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("tan", shape, args -> Tangent.of(args[0]), input);
	}

	/**
	 * Creates a collection expression that applies {@code tanh(x)} element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a hyperbolic tangent collection expression
	 */
	default CollectionExpression tanh(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("tanh", shape, args -> Tangent.of(args[0], true), input);
	}

	/**
	 * Creates a collection expression that applies ReLU ({@code max(x, 0)}) element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param input the input expression
	 * @return a rectified linear unit collection expression
	 */
	default CollectionExpression rectify(TraversalPolicy shape, TraversableExpression<Double> input) {
		return new UniformCollectionExpression("rectify", shape, args -> Rectify.of(args[0]), input);
	}

	/**
	 * Creates a traversable expression that tests whether two operands are equal element-wise.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param a the first operand
	 * @param b the second operand
	 * @return a traversable expression producing boolean equality results
	 */
	default TraversableExpression<Boolean> equals(TraversalPolicy shape,
												 TraversableExpression<Double> a, TraversableExpression<Double> b) {
		return idx -> equals(a.getValueAt(idx), b.getValueAt(idx));
	}

	/**
	 * Creates a collection expression that selects between two operands based on a scalar condition.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param condition a scalar boolean condition expression
	 * @param positive the expression to use when the condition is true
	 * @param negative the expression to use when the condition is false
	 * @return a conditional collection expression
	 */
	default CollectionExpression conditional(TraversalPolicy shape, Expression<Boolean> condition,
											TraversableExpression<Double> positive, TraversableExpression<Double> negative) {
		return CollectionExpression.create(shape, idx -> condition.conditional(positive.getValueAt(idx), negative.getValueAt(idx)));
	}

	/**
	 * Creates a collection expression that selects between two operands element-wise based on a condition operand.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @param condition a traversable expression producing boolean conditions per element
	 * @param positive the expression to use when the element condition is true
	 * @param negative the expression to use when the element condition is false
	 * @return a conditional collection expression
	 */
	default CollectionExpression conditional(TraversalPolicy shape, TraversableExpression<Boolean> condition,
											 TraversableExpression<Double> positive, TraversableExpression<Double> negative) {
		return CollectionExpression.create(shape, idx ->
				condition.getValueAt(idx)
					.conditional(positive.getValueAt(idx), negative.getValueAt(idx)));
	}

	/**
	 * Creates a collection expression that returns zero for all elements.
	 *
	 * @param shape the traversal policy defining the output shape
	 * @return a constant-zero collection expression
	 */
	default ConstantCollectionExpression constantZero(TraversalPolicy shape) {
		return new ConstantCollectionExpression(shape, new IntegerConstant(0));
	}

	/**
	 * Computes the complex product of two complex numbers represented as separate real/imaginary expressions.
	 *
	 * <p>Given complex numbers {@code a = aReal + i*aImg} and {@code b = bReal + i*bImg},
	 * computes {@code a * b = (aReal*bReal - aImg*bImg) + i*(aReal*bImg + aImg*bReal)}.
	 *
	 * @param aReal the real part of the first operand
	 * @param aImg the imaginary part of the first operand
	 * @param bReal the real part of the second operand
	 * @param bImg the imaginary part of the second operand
	 * @return a two-element array {@code [real, imaginary]} of the product
	 */
	default Expression[] complexProduct(Expression aReal, Expression aImg, Expression bReal, Expression bImg) {
		return new Expression[] {
				aReal.multiply(bReal).subtract(aImg.multiply(bImg)),
				aReal.multiply(bImg).add(aImg.multiply(bReal))
		};
	}

	/**
	 * Returns a minimal instance of {@code ExpressionFeatures} for use outside of computations.
	 *
	 * @return a default ExpressionFeatures instance
	 */
	static ExpressionFeatures getInstance() {
		return new ExpressionFeatures() { };
	}
}
