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

package org.almostrealism.time.computations;

import io.almostrealism.scope.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import io.almostrealism.relation.Producer;

import java.util.function.Consumer;
import java.util.function.Function;

public class Interpolate extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
	public static boolean enableFunctionalPosition = true;
	public static boolean enableScanning = false;

	private Function<Expression, Expression> timeForIndex;
	private Function<Expression, Expression> indexForTime;

	public Interpolate(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> position, Producer<PackedCollection<?>> rate) {
		this(series, position, rate, v -> v, v -> v);
	}

	public Interpolate(Producer<PackedCollection<?>> series, Producer<PackedCollection<?>> position,
					   Producer<PackedCollection<?>> rate, Function<Expression, Expression> timeForIndex,
					   Function<Expression, Expression> indexForTime) {
		super(new TraversalPolicy(1), new Producer[] { series, position, rate });
		this.timeForIndex = timeForIndex;
		this.indexForTime = indexForTime;
	}

	@Override
	public TraversalPolicy getShape() {
		return new TraversalPolicy(1);
	}

	@Override
	public Scope<PackedCollection<?>> getScope() {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "Interpolate"));

		Expression idx = new StaticReference(Integer.class, getVariableName(0));
		Expression left = new StaticReference(Integer.class, getVariableName(1));
		Expression right = new StaticReference(Integer.class, getVariableName(2));
		Expression leftO = new StaticReference(Integer.class, getVariableName(3));
		Expression rightO = new StaticReference(Integer.class, getVariableName(4));
		Expression bi = new StaticReference(Double.class, getVariableName(5));
		String v1 = getVariableName(6);
		String v2 = getVariableName(7);
		String t1 = getVariableName(8);
		String t2 = getVariableName(9);

		scope.getVariables().add(new Variable<>(idx.getSimpleExpression(getLanguage()), e(-1)));
		scope.getVariables().add(new Variable<>(left.getSimpleExpression(getLanguage()), e(-1)));
		scope.getVariables().add(new Variable<>(right.getSimpleExpression(getLanguage()), e(-1)));
		scope.getVariables().add(new Variable<>(leftO.getSimpleExpression(getLanguage()), e(-1)));
		scope.getVariables().add(new Variable<>(rightO.getSimpleExpression(getLanguage()), e(-1)));
		scope.getVariables().add(new Variable<>(bi.getSimpleExpression(getLanguage()), e(-1.0)));
		scope.getVariables().add(new Variable<>(v1, e(0.0)));
		scope.getVariables().add(new Variable<>(v2, e(0.0)));
		scope.getVariables().add(new Variable<>(t1, e(0.0)));
		scope.getVariables().add(new Variable<>(t2, e(0.0)));

		String res = getArgument(0).ref(0).getSimpleExpression(getLanguage());
		String start = "0";
		String end = getArgument(1).length().getSimpleExpression(getLanguage());
		Expression<Double> rate = getArgument(3).valueAt(0);

		String bankl_time = new Product(new Exponent(rate, e(-1.0)), timeForIndex.apply(left)).getSimpleExpression(getLanguage());
		String bankl_value = getArgument(1).referenceRelative(left).getSimpleExpression(getLanguage());
		String bankr_time = new Product(new Exponent(rate, e(-1.0)), timeForIndex.apply(right)).getSimpleExpression(getLanguage());
		String bankr_value = getArgument(1).referenceRelative(right).getSimpleExpression(getLanguage());
		String cursor = getArgument(2).referenceRelative(e(0)).getSimpleExpression(getLanguage());

		Consumer<String> code = scope.code();

		if (enableFunctionalPosition) {
			Expression<Double> time = getArgument(2).ref(0).multiply(rate);
			Expression index = indexForTime.apply(time);

//			code.accept(left + " = " + idx + " > " + start + " ? " + idx + " - 1 : (" + banki + " == " + cursor + " ? " + idx + " : -1);\n");

			code.accept(idx + " = " + index.ceil().toInt().getSimpleExpression(getLanguage()) + " - 1;");
			code.accept(left + " = " + idx + " > " + start + " ? " + idx + " - 1 : " + idx + ";\n");
			code.accept(right + " = " + idx + ";\n");

			code.accept("if ((" + timeForIndex.apply(idx).getSimpleExpression(getLanguage()) + ") != (" + time.getSimpleExpression(getLanguage()) + ")) {\n");
			code.accept("    " + left + " = " + left + " + 1;\n");
			code.accept("    " + right + " = " + right + " + 1;\n");
			code.accept("}\n");
		}

		if (enableScanning) {
			Expression i = new StaticReference(Integer.class, "i");
			String banki = new Product(new Exponent(rate, e(-1.0)), timeForIndex.apply(i)).getSimpleExpression(getLanguage());

			code.accept("for (int i = " + start + "; i < " + end + "; i++) {\n");
			code.accept("	if (" + banki + " >= " + cursor + ") {\n");
			code.accept("		" + leftO + " = i > " + start + " ? i - 1 : (" + banki + " == " + cursor + " ? i : -1);\n");
			code.accept("		" + rightO + " = i;\n");
			code.accept("		" + bi + " = " + banki + ";\n");
			code.accept("		break;\n");
			code.accept("	}\n");
			code.accept("}\n");

			code.accept("if (" + leftO + " == -1.0) {\n");
			code.accept("    " + left + " = -1.0;\n");
			code.accept("}\n");

			code.accept("if (" + rightO + " == -1.0) {\n");
			code.accept("    " + right + " = -1.0;\n");
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

		if (enableScanning) {
			code.accept("if (" + leftO + " != " + left + " || " + rightO + " != " + right + ") {\n");
			code.accept("printf(\"left = %i, leftO = %i, right = %i, rightO = %i, banki = %f, cursor = %f\\n\", "
					+ left + ", " + leftO + ", " + right + ", " + rightO + ", " + bi + ", " + cursor + ");\n");
			code.accept("}\n");
		}

		code.accept("	if (" + t2 + " == 0) {\n");
		code.accept("		" + res + " = " + v1 + ";\n");
		code.accept("	} else {\n");
		code.accept("		" + res + " = " + v1 + " + (" + t1 + " / " + t2 + ") * (" + v2 + " - " + v1 + ");\n");
		code.accept("	}\n");
		code.accept("}\n");

		return scope;
	}
}
