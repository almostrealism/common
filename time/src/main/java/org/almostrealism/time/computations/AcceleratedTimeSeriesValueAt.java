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
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import io.almostrealism.relation.Producer;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.List;
import java.util.function.Consumer;

/**
 * Hardware-accelerated operation for linearly interpolating values from an {@link AcceleratedTimeSeries}.
 *
 * <p>This computation performs the same linear interpolation as {@link Interpolate} but is
 * specialized for {@link AcceleratedTimeSeries} with {@link CursorPair} position tracking.
 * It's deprecated in favor of the more general {@link Interpolate} computation.</p>
 *
 * <h2>Deprecation Notice</h2>
 * <p>This class is deprecated. Use {@link Interpolate} instead for better flexibility
 * and consistency across the framework:</p>
 * <pre>{@code
 * // OLD (deprecated)
 * AcceleratedTimeSeriesValueAt valueAt =
 *     new AcceleratedTimeSeriesValueAt(p(series), p(cursor));
 *
 * // NEW (recommended)
 * Interpolate interpolate =
 *     new Interpolate(p(series), p(position), null);
 * }</pre>
 *
 * <h2>Operation</h2>
 * <pre>
 * 1. Search series for entries surrounding cursor time
 * 2. Find left (t1, v1) and right (t2, v2) neighbors
 * 3. Linearly interpolate: v = v1 + ((t - t1) / (t2 - t1)) * (v2 - v1)
 * </pre>
 *
 * <h2>Edge Cases</h2>
 * <ul>
 *   <li>Returns 0 if no surrounding points exist</li>
 *   <li>Returns 0 if cursor is before all data</li>
 *   <li>Returns v1 if t1 == t2 (avoids division by zero)</li>
 * </ul>
 *
 * @deprecated Use {@link Interpolate} for time-series interpolation
 *
 * @see Interpolate
 * @see AcceleratedTimeSeries#valueAt(Producer)
 * @see CursorPair
 *
 * @author Michael Murray
 */
@Deprecated
public class AcceleratedTimeSeriesValueAt extends CollectionProducerComputationBase {
	/**
	 * Constructs an interpolation operation for the specified series and cursor.
	 *
	 * @param series Producer providing the time-series
	 * @param cursors Producer providing the query cursor position
	 * @deprecated Use {@link Interpolate} instead
	 */
	public AcceleratedTimeSeriesValueAt(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors) {
		super("timeSeriesValueAt", new TraversalPolicy(1).traverse(0), new Producer[] { series, cursors });
	}

	/**
	 * Private constructor for internal regeneration.
	 *
	 * @param arguments Producer arguments (series, cursors)
	 */
	private AcceleratedTimeSeriesValueAt(Producer<PackedCollection>... arguments) {
		super("timeSeriesValueAt", new TraversalPolicy(1).traverse(0), arguments);
	}

	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		return new AcceleratedTimeSeriesValueAt(children.stream().skip(1).toArray(Producer[]::new));
	}

	@Override
	public Scope<PackedCollection> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection> scope = new HybridScope<>(this);

		ArrayVariable<?> outputVariable = (ArrayVariable) getOutputVariable();

		Expression i = new StaticReference(Integer.class, "i");
		Expression left = new StaticReference(Integer.class, getNameProvider().getVariableName(0));
		Expression right = new StaticReference(Integer.class, getNameProvider().getVariableName(1));
		String v1 = getNameProvider().getVariableName(2);
		String v2 = getNameProvider().getVariableName(3);
		String t1 = getNameProvider().getVariableName(4);
		String t2 = getNameProvider().getVariableName(5);

		scope.getVariables().add(new ExpressionAssignment(true, left, new IntegerConstant(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, right, new IntegerConstant(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, v1), new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, v2), new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, t1), new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, new StaticReference(Double.class, t2), new DoubleConstant(0.0)));

		String res = outputVariable.valueAt(0).getSimpleExpression(getLanguage());
		String bank0 = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String bank1 = getArgument(1).valueAt(1).getSimpleExpression(getLanguage());
		String banki = getArgument(1).reference(i.multiply(2)).getSimpleExpression(getLanguage());
		String bankl0 = getArgument(1).reference(left.multiply(2)).getSimpleExpression(getLanguage());
		String bankl1 = getArgument(1).reference(left.multiply(2).add(1)).getSimpleExpression(getLanguage());
		String bankr0 = getArgument(1).reference(right.multiply(2)).getSimpleExpression(getLanguage());
		String bankr1 = getArgument(1).reference(right.multiply(2).add(1)).getSimpleExpression(getLanguage());
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
}
