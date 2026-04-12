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

package io.almostrealism.expression;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.sequence.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * A ternary conditional expression that evaluates to one of two branches
 * depending on a boolean condition.
 *
 * <p>Generates code of the form {@code condition ? positive : negative}.
 * When {@link #enableSimplification} is active and a kernel series provider is
 * available, the condition may be resolved statically and the result replaced
 * with the appropriate branch expression or an equivalent arithmetic form.</p>
 *
 * @param <T> the numeric type of the conditional result
 */
public class Conditional<T extends Number> extends Expression<T> {
	/**
	 * When {@code true}, enables algebraic simplification of conditional expressions
	 * when the condition can be statically resolved through the kernel series provider.
	 */
	public static boolean enableSimplification = false;

	/**
	 * When {@code true}, logs a warning if the positive branch value equals the
	 * right-hand side of an equality condition, which may indicate a redundant branch.
	 */
	public static boolean enableInputBranchWarning = false;

	/**
	 * Constructs a conditional expression with the given condition and branch expressions.
	 *
	 * @param type     the Java numeric type of the result
	 * @param condition the boolean-valued guard expression
	 * @param positive  the expression yielded when the condition is true
	 * @param negative  the expression yielded when the condition is false
	 * @throws IllegalArgumentException if the condition already has a statically known boolean value
	 */
	protected Conditional(Class<T> type, Expression<Boolean> condition,
						  Expression<Double> positive, Expression<Double> negative) {
		super(type, condition, positive, negative);

		if (condition.booleanValue().isPresent()) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		return getChildren().get(0).getWrappedExpression(lang) + " ? " +
				getChildren().get(1).getWrappedExpression(lang) +
				" : " + getChildren().get(2).getWrappedExpression(lang);
	}

	@Override
	public boolean isValue(IndexValues values) {
		// TODO  This should just be the parent implementation
		return getChildren().get(0).isValue(values) &&
				getChildren().get(1).isValue(values) &&
				getChildren().get(2).isValue(values);
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong l = getChildren().get(1).upperBound(context);
		OptionalLong r = getChildren().get(2).upperBound(context);
		if (l.isPresent() && r.isPresent()) {
			return OptionalLong.of(Math.max(l.getAsLong(), r.getAsLong()));
		}

		return OptionalLong.empty();
	}

	@Override
	public Number evaluate(Number... children) {
		return children[0].doubleValue() != 0.0 ? children[1] : children[2];
	}

	@Override
	public Expression simplify(KernelStructureContext context, int depth) {
		Expression<?> flat = super.simplify(context, depth);
		if (!enableSimplification || !(flat instanceof Conditional)) return flat;

		Expression<Boolean> condition = (Expression<Boolean>) flat.getChildren().get(0);
		Expression<Double> positive = (Expression<Double>) flat.getChildren().get(1);
		Expression<Double> negative = (Expression<Double>) flat.getChildren().get(2);

		OptionalDouble ld = positive.doubleValue();
		OptionalDouble rd = negative.doubleValue();

		boolean replaceCondition = false;
		if (context.getSeriesProvider() != null && !flat.isSingleIndexMasked()) {
			int len = context.getSeriesProvider().getMaximumLength().orElse(0);
			OptionalLong max = condition.getIndices().stream()
					.mapToLong(id -> id.upperBound(context).orElse(Integer.MAX_VALUE))
					.findFirst();
			if (max.isPresent()) {
				replaceCondition = max.getAsLong() < len;
			}
		}

		if (replaceCondition) {
			Expression<?> exp = context.getSeriesProvider().getSeries(condition);
			Optional<Boolean> r = exp.booleanValue();

			if (r.isPresent()) {
				return r.get() ? positive : negative;
			} else if (exp instanceof Equals) {
				OptionalDouble d = ((Equals) exp).getRight().doubleValue();

				if (d.isPresent() && d.getAsDouble() == 1.0) {
					exp = ((Equals) exp).getLeft();

					if (rd.isPresent() && rd.getAsDouble() == 0) {
						return exp.multiply(positive);
					} else if (ld.isPresent() && ld.getAsDouble() == 0) {
						return exp.add(1).imod(2).multiply(negative);
					} else {
						return exp.multiply(positive).add(exp.add(1).imod(2).multiply(negative));
					}
				}
			}
		}

		return Conditional.of(condition, positive, negative);
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		if (children.size() != 3) throw new UnsupportedOperationException();
		return Conditional.of((Expression<Boolean>) children.get(0),
				children.get(1), children.get(2));
	}

	/**
	 * Creates and post-processes a conditional expression for the given branches.
	 * Delegates to {@link #create} and then applies {@link Expression#process}.
	 *
	 * @param condition the boolean guard expression
	 * @param positive  the branch used when the condition is true
	 * @param negative  the branch used when the condition is false
	 * @return a simplified or restructured expression representing the conditional
	 */
	public static Expression of(Expression<Boolean> condition, Expression<?> positive, Expression<?> negative) {
		return Expression.process(create(condition, positive, negative));
	}

	/**
	 * Constructs a conditional expression, applying constant-folding and structural
	 * optimizations before returning the result.
	 *
	 * <p>If the condition has a statically known boolean value the corresponding branch
	 * is returned directly. If both branches are equal a single constant is returned.
	 * If one branch is zero the expression is converted to a {@link Mask}.</p>
	 *
	 * @param condition the boolean guard expression
	 * @param positive  the branch used when the condition is true
	 * @param negative  the branch used when the condition is false
	 * @return the optimized expression representing the conditional
	 */
	public static Expression create(Expression<Boolean> condition, Expression<?> positive, Expression<?> negative) {
		Optional<Boolean> cond = condition.booleanValue();

		if (cond.isPresent()) {
			return cond.get() ? positive : negative;
		}

		OptionalDouble cd = condition.doubleValue();

		if (cd.isPresent()) {
			Scope.console.features(Conditional.class)
					.warn("Conditional created with numeric condition");
			return cd.getAsDouble() == 0.0 ? negative : positive;
		}

		OptionalDouble ld = positive.doubleValue();
		OptionalDouble rd = negative.doubleValue();

		if (enableInputBranchWarning && condition instanceof Equals && ld.isPresent()) {
			OptionalDouble value = ((Equals) condition).getRight().doubleValue();
			if (value.isPresent() && value.getAsDouble() == ld.getAsDouble()) {
				Scope.console.features(Conditional.class)
						.warn("Conditional output is equivalent to a branch of the condition");
			}
		}

		if (rd.isPresent() && ld.isPresent() && rd.getAsDouble() == ld.getAsDouble()) {
			boolean fp = positive.isFP() || negative.isFP();
			return fp ? new DoubleConstant(rd.getAsDouble()) :
					ExpressionFeatures.getInstance().e((long) rd.getAsDouble());
		}

		if (rd.isPresent() && rd.getAsDouble() == 0.0) {
			return Mask.of(condition, positive);
		}

		if (ld.isPresent() && ld.getAsDouble() == 0.0) {
			return Mask.of(condition.not(), negative);
		}

		if (positive.getType() == negative.getType()) {
			return new Conditional(positive.getType(), condition, positive, negative);
		} else {
			return new Conditional(Double.class, condition, positive, negative);
		}
	}
}