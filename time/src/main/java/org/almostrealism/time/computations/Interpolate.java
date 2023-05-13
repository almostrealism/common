/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationAdapter;
import io.almostrealism.relation.Producer;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

public class Interpolate extends CollectionProducerComputationAdapter<PackedCollection<?>, PackedCollection<?>> {
	private Function<Expression, Expression> timeForIndex;

	public Interpolate(Producer<PackedCollection> series, Producer<PackedCollection> position, Producer<PackedCollection> rate) {
		this(series, position, rate, v -> v);
	}

	public Interpolate(Producer<PackedCollection> series, Producer<PackedCollection> position, Producer<PackedCollection> rate, Function<Expression, Expression> timeForIndex) {
		super(new TraversalPolicy(1), new Producer[] { series, position, rate });
		this.timeForIndex = timeForIndex;
	}

	@Override
	public TraversalPolicy getShape() {
		return new TraversalPolicy(1);
	}

	@Override
	public Scope<PackedCollection<?>> getScope() {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "Interpolate"));

		Expression left = new StaticReference(Integer.class, getVariableName(0));
		Expression right = new StaticReference(Integer.class, getVariableName(1));
		String v1 = getVariableName(2);
		String v2 = getVariableName(3);
		String t1 = getVariableName(4);
		String t2 = getVariableName(5);

		scope.getVariables().add(new Variable<>(left.getExpression(), new Expression<>(Integer.class, "-1")));
		scope.getVariables().add(new Variable<>(right.getExpression(), new Expression<>(Integer.class, "-1")));
		scope.getVariables().add(new Variable<>(v1, new Expression<>(Double.class, "0.0")));
		scope.getVariables().add(new Variable<>(v2, new Expression<>(Double.class, "0.0")));
		scope.getVariables().add(new Variable<>(t1, new Expression<>(Double.class, "0.0")));
		scope.getVariables().add(new Variable<>(t2, new Expression<>(Double.class, "0.0")));

		String res = getArgument(0).valueAt(0).getExpression();
		String start = "0";
		String end = getArgument(1).length().getExpression();
		Expression<Double> rate = getArgument(3).valueAt(0);

		Expression i = new StaticReference(Integer.class, "i");
		String banki = new Product(new Exponent(rate, expressionForDouble(-1.0)), timeForIndex.apply(i)).getExpression();
		String bankl_time = new Product(new Exponent(rate, expressionForDouble(-1.0)), timeForIndex.apply(left)).getExpression();
		String bankl_value = getArgument(1).get(left).getExpression();
		String bankr_time = new Product(new Exponent(rate, expressionForDouble(-1.0)), timeForIndex.apply(right)).getExpression();
		String bankr_value = getArgument(1).get(right).getExpression();
		String cursor = getArgument(2).valueAt(0).getExpression();

		Consumer<String> code = scope.code();
		code.accept("for (int i = " + start + "; i < " + end + "; i++) {\n");
		code.accept("	if (" + banki + " >= " + cursor + ") {\n");
		code.accept("		" + left + " = i > " + start + " ? i - 1 : (" + banki + " == " + cursor + " ? i : -1);\n");
		code.accept("		" + right + " = i;\n");
		code.accept("		break;\n");
		code.accept("	}\n");
		code.accept("}\n");

		code.accept("if (" + left + " == -1 || " + right + " == -1) {\n");
		code.accept("	" + res + " = 0;\n");
		code.accept("} else if (" + bankl_time + " > " + cursor + ") {\n");
		code.accept("	" + res + " = 0;\n");
		code.accept("} else {\n");
		code.accept("	" + v1 + " = " + bankl_value + ";\n");
		code.accept("	" + v2 + " = " + bankr_value + ";\n");
		code.accept("	" + t1 + " = " + cursor + " - " + bankl_time + ";\n");
		code.accept("	" + t2 + " = " + bankr_time + " - " + bankl_time + ";\n");
		code.accept("	if (" + t2 + " == 0) {\n");
		code.accept("		" + res + " = " + v1 + ";\n");
		code.accept("	} else {\n");
		code.accept("		" + res + " = " + v1 + " + (" + t1 + " / " + t2 + ") * (" + v2 + " - " + v1 + ");\n");
		code.accept("	}\n");
		code.accept("}\n");

		return scope;
	}
}
