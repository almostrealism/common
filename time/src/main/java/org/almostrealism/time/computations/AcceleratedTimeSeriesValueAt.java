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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Deprecated
public class AcceleratedTimeSeriesValueAt extends CollectionProducerComputationBase<PackedCollection<?>, Scalar> {
	public AcceleratedTimeSeriesValueAt(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors) {
		super(null, new TraversalPolicy(2).traverse(0), new Producer[] { series, cursors });
	}

	private AcceleratedTimeSeriesValueAt(Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(null, new TraversalPolicy(2).traverse(0), arguments);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Evaluable<? extends Scalar>> generate(List<Process<?, ?>> children) {
		return new AcceleratedTimeSeriesValueAt(children.stream().skip(1).toArray(Supplier[]::new));
	}

	@Override
	public Scope<Scalar> getScope() {
		HybridScope<Scalar> scope = new HybridScope<>(this);

		ArrayVariable<?> outputVariable = (ArrayVariable) getOutputVariable();

		scope.getVariables().add(outputVariable.valueAt(1).assign(e(1.0)));

		Expression i = new StaticReference(Integer.class, "i");
		Expression left = new StaticReference(Integer.class, getVariableName(0));
		Expression right = new StaticReference(Integer.class, getVariableName(1));
		String v1 = getVariableName(2);
		String v2 = getVariableName(3);
		String t1 = getVariableName(4);
		String t2 = getVariableName(5);

		scope.getVariables().add(new ExpressionAssignment(true, left, new IntegerConstant(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, right, new IntegerConstant(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, v1), new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, v2), new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, t1), new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, t2), new DoubleConstant(0.0)));

		String res = getArgument(0).valueAt(0).getSimpleExpression(getLanguage());
		String bank0 = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String bank1 = getArgument(1).valueAt(1).getSimpleExpression(getLanguage());
		String banki = getArgument(1).referenceRelative(i.multiply(2)).getSimpleExpression(getLanguage());
		String bankl0 = getArgument(1).referenceRelative(left.multiply(2)).getSimpleExpression(getLanguage());
		String bankl1 = getArgument(1).referenceRelative(left.multiply(2).add(1)).getSimpleExpression(getLanguage());
		String bankr0 = getArgument(1).referenceRelative(right.multiply(2)).getSimpleExpression(getLanguage());
		String bankr1 = getArgument(1).referenceRelative(right.multiply(2).add(1)).getSimpleExpression(getLanguage());
		String cursor0 = getArgument(2).valueAt(0).getSimpleExpression(getLanguage());

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

//		code.accept("if (fabs(" + res + ") > 0.99) {\n");
//		code.accept("    printf(\"left = %i, right = %i -- value = %f\\n\", " + left + ", " + right + ", " + res + ");\n");
//		code.accept("}\n");

		return scope;
	}

	@Override
	public Scalar postProcessOutput(MemoryData output, int offset) {
		return (Scalar) Scalar.postprocessor().apply(output, offset);
	}
}
