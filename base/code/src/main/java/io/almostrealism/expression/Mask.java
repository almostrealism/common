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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;
import java.util.Optional;

/**
 * A mask expression that evaluates to a value when a boolean condition is true,
 * and to zero otherwise.
 *
 * <p>This is a specialised {@link Conditional} where the negative branch is always
 * {@code 0}. It is generated automatically by {@link Conditional#create} when one
 * branch is a zero constant, providing a simpler representation for gating patterns.</p>
 *
 * @param <T> the numeric type of the masked value
 */
public class Mask<T extends Number> extends Conditional<T> {
	/**
	 * Constructs a mask expression that returns {@code value} when {@code mask} is true
	 * and zero otherwise.
	 *
	 * @param mask  the boolean guard expression
	 * @param value the expression returned when the mask is true
	 */
	protected Mask(Expression<Boolean> mask, Expression<T> value) {
		super(value.getType(), mask, (Expression) value, (Expression) new IntegerConstant(0));
	}

	public Expression<Boolean> getMask() { return (Expression<Boolean>) getChildren().get(0); }
	public Expression<T> getMaskedValue() { return (Expression<T>) getChildren().get(1); }

	@Override
	public ExpressionAssignment<T> assign(Expression exp) {
		if (getMaskedValue() instanceof InstanceReference) {
			return getMaskedValue().assign(exp);
		}

		return super.assign(exp);
	}

	@Override
	public CollectionExpression delta(CollectionExpression target) {
		return getMaskedValue().delta(target);
	}

	@Override
	public Expression simplify(KernelStructureContext context, int depth) {
		if (getMaskedValue().doubleValue().orElse(0.0) == 1.0) {
			// Attempting normal simplification here causes infinite regress,
			// as this Expression is functionally equivalent to its first argument
			return Mask.of(getMask().simplify(context, depth + 1), getMaskedValue());
		}

		return super.simplify(context, depth);
	}

	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		return Mask.of((Expression<Boolean>) children.get(0), (Expression<T>) children.get(1));
	}

	@Override
	public boolean isMasked() { return true; }

	/**
	 * Creates a mask expression, applying constant-folding and zero-value short-circuits.
	 *
	 * <p>If the mask is statically true the value is returned unchanged; if false a zero
	 * constant is returned. If the value is already zero a zero constant is returned.</p>
	 *
	 * @param mask  the boolean guard expression
	 * @param value the expression returned when the mask is true
	 * @param <T>   the numeric type of the value
	 * @return the simplified expression
	 */
	public static <T> Expression<T> of(Expression<Boolean> mask, Expression<T> value) {
		Optional<Boolean> b = mask.booleanValue();

		if (b.isPresent()) {
			if (b.get()) {
				return value;
			} else {
				return (Expression) new IntegerConstant(0);
			}
		} else if (value.doubleValue().orElse(1) == 0) {
			return value.isFP() ? (Expression) new DoubleConstant(0.0) :
					(Expression) new IntegerConstant(0);
		} else {
			return new Mask(mask, value);
		}
	}
}