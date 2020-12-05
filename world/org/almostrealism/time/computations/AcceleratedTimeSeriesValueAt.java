/*
 * Copyright 2020 Michael Murray
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
import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.function.Consumer;
import java.util.function.IntFunction;

public class AcceleratedTimeSeriesValueAt extends DynamicAcceleratedProducerAdapter<MemWrapper, Scalar> implements ScalarProducer {
	public AcceleratedTimeSeriesValueAt(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors) {
		super(2, Scalar.blank(), new Producer[] { series, cursors });
	}

	@Override
	public Scope<Scalar> getScope(NameProvider provider) {
		Scope parentScope = super.getScope(provider);
		HybridScope<Scalar> scope = new HybridScope<>(this);
		scope.getVariables().add((Variable) parentScope.getVariables().get(1));

		String left = getVariableName(0);
		String right = getVariableName(1);
		String v1 = getVariableName(2);
		String v2 = getVariableName(3);
		String t1 = getVariableName(4);
		String t2 = getVariableName(5);

		scope.getVariables().add(new Variable<>(left, new Expression<>(Integer.class, "-1")));
		scope.getVariables().add(new Variable<>(right, new Expression<>(Integer.class, "-1")));
		scope.getVariables().add(new Variable<>(v1, new Expression<>(Double.class, "0.0")));
		scope.getVariables().add(new Variable<>(v2, new Expression<>(Double.class, "0.0")));
		scope.getVariables().add(new Variable<>(t1, new Expression<>(Double.class, "0.0")));
		scope.getVariables().add(new Variable<>(t2, new Expression<>(Double.class, "0.0")));

		String res = getArgumentValueName(0, 0);
		String bank0 = getArgumentValueName(1, 0);
		String bank1 = getArgumentValueName(1, 1);
		String banki = getArgumentValueName(1, "2 * i");
		String bankl0 = getArgumentValueName(1, "2 * " + left);
		String bankl1 = getArgumentValueName(1, "2 * " + left + " + 1");
		String bankr0 = getArgumentValueName(1, "2 * " + right);
		String bankr1 = getArgumentValueName(1, "2 * " + right + " + 1");
		String cursor0 = getArgumentValueName(2, 0);

		Consumer<String> code = scope.code();
		code.accept("for (int i = " + bank0 + "; i < " + bank1 + "; i++) {\n");
		code.accept("	if (" + banki + " >= " + cursor0 + ") {\n");
		code.accept("		" + left + " = i > " + bank0 + " ? i - 1 : (" + banki + " == " + cursor0 + " ? i : -1);\n");
		code.accept("		" + right + " = i;\n");
		code.accept("		break;\n");
		code.accept("	}\n");
		code.accept("}\n");

		code.accept("if (" + left + " == -1 || " + right + " == -1) {\n");
		code.accept("	" + res + " = 0;\n");
		code.accept("} else if (" + bankl0 + " > " + cursor0 + ") {\n");
		code.accept("	" + res + " = 0;\n");
		code.accept("} else {\n");
		code.accept("	" + v1 + " = " + bankl1 + ";\n");
		code.accept("	" + v2 + " = " + bankr1 + ";\n");
		code.accept("	" + t1 + " = " + cursor0 + " - " + bankl0 + ";\n");
		code.accept("	" + t2 + " = " + bankr0 + " - " + bankl0 + ";\n");
		code.accept("	if (" + t2 + " == 0) {\n");
		code.accept("		" + res + " = " + v1 + ";\n");
		code.accept("	} else {\n");
		code.accept("		" + res + " = " + v1 + " + (" + t1 + " / " + t2 + ") * (" + v2 + " - " + v1 + ");\n");
		code.accept("	}\n");
		code.accept("}\n");

		return scope;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> new Expression<>(Double.class, "1.0");
	}
}
