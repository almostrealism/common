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
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Difference;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Epsilon;
import io.almostrealism.expression.Equals;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Greater;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.LongConstant;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Quotient;
import io.almostrealism.expression.Sum;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.expression.MinimumValue;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperations;

import java.util.Collection;

public interface ExpressionFeatures {

	default Expression e(boolean value) {
		return new BooleanConstant(value);
	}

	default Expression e(int value) {
		return new IntegerConstant(value);
	}

	default Expression<Double> expressionForDouble(double value) {
		return new DoubleConstant(value);
	}

	default Expression<? extends Number> e(long value) {
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			return new LongConstant(value);
		}

		return e(Math.toIntExact(value));
	}

	default Expression<Double> e(double value) {
		return expressionForDouble(value);
	}

	default Exp exp(Expression expression) {
		return new Exp(expression);
	}

	default Epsilon epsilon() { return new Epsilon(); }

	default MinimumValue minValue() { return new MinimumValue(); }

	default KernelIndex kernel() { return new KernelIndex(); }

	default StaticReference<Double> pi() {
		return new StaticReference<>(Double.class, null) {
			@Override
			public String getExpression(LanguageOperations lang) {
				return lang.pi();
			}
		};
	}

	default <T> ExpressionAssignment<T> declare(String name, Expression<T> expression) {
		return declare(new StaticReference<>(expression.getType(), name), expression);
	}

	default <T> ExpressionAssignment<T> assign(String name, Expression<T> expression) {
		return assign(new StaticReference<>(expression.getType(), name), expression);
	}

	default <T> ExpressionAssignment<T> declare(Expression<T> destination, Expression<T> expression) {
		return new ExpressionAssignment<>(true, destination, expression);
	}

	default <T> ExpressionAssignment<T> assign(Expression<T> destination, Expression<T> expression) {
		return new ExpressionAssignment<>(destination, expression);
	}

	default Greater greater(Expression<?> left, Expression<?> right, boolean includeEqual) {
		return new Greater(left, right, includeEqual);
	}

	default Equals equals(Expression<?> left, Expression<?> right) {
		return new Equals(left, right);
	}

	default Expression conditional(Expression<Boolean> condition, Expression<?> positive, Expression<?> negative) {
		return Conditional.of(condition, (Expression) positive, (Expression) negative);
	}

	default CollectionExpression sum(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return sum(shape, expressions.toArray(TraversableExpression[]::new));
	}

	default CollectionExpression sum(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression sum = new UniformCollectionExpression(shape, Sum::of, expressions);
		sum.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.EXCLUSIVE);
		return sum;
	}

	default CollectionExpression difference(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return difference(shape, expressions.toArray(TraversableExpression[]::new));
	}

	default CollectionExpression difference(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression difference = new UniformCollectionExpression(shape, Difference::of, expressions);
		difference.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.EXCLUSIVE);
		return difference;
	}

	default CollectionExpression product(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return product(shape, expressions.toArray(TraversableExpression[]::new));
	}

	default CollectionExpression product(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression product = new UniformCollectionExpression(shape, Product::of, expressions);
		product.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.DISJUNCTIVE);
		return product;
	}

	default CollectionExpression quotient(TraversalPolicy shape, Collection<? extends TraversableExpression<Double>> expressions) {
		return quotient(shape, expressions.toArray(TraversableExpression[]::new));
	}

	default CollectionExpression quotient(TraversalPolicy shape, TraversableExpression... expressions) {
		UniformCollectionExpression quotient = new UniformCollectionExpression(shape, Quotient::of, expressions);
		quotient.setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.DISJUNCTIVE);
		return quotient;
	}

	default Expression[] complexProduct(Expression aReal, Expression aImg, Expression bReal, Expression bImg) {
		return new Expression[] {
				aReal.multiply(bReal).subtract(aImg.multiply(bImg)),
				aReal.multiply(bImg).add(aImg.multiply(bReal))
		};
	}

	static ExpressionFeatures getInstance() {
		return new ExpressionFeatures() { };
	}
}
