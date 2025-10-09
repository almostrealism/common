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

package org.almostrealism.time.computations;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.collect.CollectionVariable;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.Scope;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import io.almostrealism.relation.Producer;

import java.util.function.Consumer;
import java.util.function.Function;

public class Interpolate extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	public static boolean enableAtomicShape = false;
	public static boolean enableFunctionalPosition = true;

	private Function<Expression, Expression> timeForIndex;
	private Function<Expression, Expression> indexForTime;
	private boolean applyRate;

	public Interpolate(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> position, Producer<PackedCollection<?>> rate) {
		this(series, position, rate, v -> v, v -> v);
	}

	public Interpolate(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> position,
					   Function<Expression, Expression> timeForIndex,
					   Function<Expression, Expression> indexForTime) {
		this(series, position, null, timeForIndex, indexForTime);
	}

	public Interpolate(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> position,
					   Producer<PackedCollection<?>> rate,
					   Function<Expression, Expression> timeForIndex,
					   Function<Expression, Expression> indexForTime) {
		super("interpolate", computeShape(series, position),
				rate == null ?
					new Producer[] { series, position } :
					new Producer[] { series, position, rate});
		this.timeForIndex = timeForIndex;
		this.indexForTime = indexForTime;
		this.applyRate = rate != null;
	}

	protected Expression getArgumentValueRelative(int index, int pos) {
		return getArgumentValueRelative(index, e(pos));
	}

	protected Expression getArgumentValueRelative(int index, Expression<?> pos) {
		ArrayVariable<?> var = getArgument(index);

		if (var instanceof CollectionVariable && ((CollectionVariable) var).getShape().isFixedCount()) {
			CollectionVariable c = (CollectionVariable<?>) var;

			if (c.getShape().getCountLong() == 1) {
				return c.getValueAt(pos);
			} else if (c.getShape().getDimensions() == 1) {
				if (pos.intValue().orElse(1) != 0) {
					throw new IllegalArgumentException();
				}

				return c.getValue(kernel());
			} else {
				return c.getValue(kernel(), pos);
			}
		} else {
			return var.referenceRelative(pos);
		}
	}

	protected Expression getSeriesValue(Expression<?> pos) {
		ArrayVariable<?> var = getArgument(1);

		if (var instanceof CollectionVariable) {
			CollectionVariable c = (CollectionVariable) var;

			if (c.getShape().getTotalSizeLong() == 1) {
				// This is a hack to allow the a collection of size 1
				// to be used as a shortcut for a value of unknown size.
				// This would normally be accomplished using a value
				// that has a variable count, but unfortunately that
				// distinction can't easily distinguish between a
				// variable length time series a variable number of
				// time series' of a fixed length.
				return c.referenceAbsolute(pos);
			} else if (c.getShape().isFixedCount()) {
				return c.getValueAt(pos);
			}
		}

		return var.referenceRelative(pos);
	}

	protected Expression getTime() {
		return getArgumentValueRelative(2, 0);
	}

	protected Expression<Double> getRate() {
		if (applyRate) {
			return getArgumentValueRelative(3, 0);
		}

		return e(1.0);
	}

	@Override
	public Scope<PackedCollection<?>> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);

		Expression idx = new StaticReference(Integer.class, getNameProvider().getVariableName(0));
		Expression left = new StaticReference(Integer.class, getNameProvider().getVariableName(1));
		Expression right = new StaticReference(Integer.class, getNameProvider().getVariableName(2));
		Expression leftO = new StaticReference(Integer.class, getNameProvider().getVariableName(3));
		Expression rightO = new StaticReference(Integer.class, getNameProvider().getVariableName(4));
		Expression bi = new StaticReference(Double.class, getNameProvider().getVariableName(5));
		String v1 = getNameProvider().getVariableName(6);
		String v2 = getNameProvider().getVariableName(7);
		String t1 = getNameProvider().getVariableName(8);
		String t2 = getNameProvider().getVariableName(9);

		scope.getVariables().add(new ExpressionAssignment(true, idx, e(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, left, e(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, right, e(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, leftO, e(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, rightO, e(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, bi, e(-1.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, v1), e(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, v2), e(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, t1), e(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, t2), e(0.0)));

		String res = getArgumentValueRelative(0, 0).getSimpleExpression(getLanguage());
		String start = "0";
		String end = getArgument(1).length().getSimpleExpression(getLanguage());
		Expression<Double> rate = getRate();

		String bankl_time = Product.of(Exponent.of(rate, e(-1.0)), timeForIndex.apply(left)).getSimpleExpression(getLanguage());
		String bankl_value = getSeriesValue(left).getSimpleExpression(getLanguage());
		String bankr_time = Product.of(Exponent.of(rate, e(-1.0)), timeForIndex.apply(right)).getSimpleExpression(getLanguage());
		String bankr_value = getSeriesValue(right).getSimpleExpression(getLanguage());
		String cursor = getArgumentValueRelative(2, e(0)).getSimpleExpression(getLanguage());

		Consumer<String> code = scope.code();

		Expression<Double> time = getTime().multiply(rate);
		Expression index = indexForTime.apply(time);

		if (enableFunctionalPosition) {
			code.accept(idx + " = " + index.ceil().toInt().getSimpleExpression(getLanguage()) + " - 1;\n");
			code.accept(left + " = " + idx + " > " + start + " ? " + idx + " - 1 : " + idx + ";\n");
			code.accept(right + " = " + idx + ";\n");

			code.accept("if ((" + timeForIndex.apply(idx).getSimpleExpression(getLanguage()) + ") != (" + time.getSimpleExpression(getLanguage()) + ")) {\n");
			code.accept("    " + left + " = " + left + " + 1;\n");
			code.accept("    " + right + " = " + right + " + 1;\n");
			code.accept("}\n");
		}

		code.accept("if (" + left + " == -1 || " + right + " == -1) {\n");
		code.accept("	" + res + " = 0;\n");
		code.accept("} else if (" + bankl_time + " > " + cursor + ") {\n");
		code.accept("	" + res + " = 0;\n");
		code.accept("} else {\n");
		code.accept("	" + v1 + " = " + bankl_value + ";\n");
		code.accept("	" + v2 + " = " + bankr_value + ";\n");
		code.accept("	" + t1 + " = (" + cursor + ") - (" + bankl_time + ");\n");
		code.accept("	" + t2 + " = (" + bankr_time + ") - (" + bankl_time + ");\n");

		code.accept("	if (" + t2 + " == 0) {\n");
		code.accept("		" + res + " = " + v1 + ";\n");
		code.accept("	} else {\n");
		code.accept("		" + res + " = " + v1 + " + (" + t1 + " / " + t2 + ") * (" + v2 + " - " + v1 + ");\n");
		code.accept("	}\n");

		code.accept("}");

		return scope;
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;

		// TODO
		return null;
	}

	protected static TraversalPolicy computeShape(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> position) {
		if (enableAtomicShape) {
			return new TraversalPolicy(1);
		}

		return CollectionFeatures.getInstance().shape(position).traverseEach();
	}
}
