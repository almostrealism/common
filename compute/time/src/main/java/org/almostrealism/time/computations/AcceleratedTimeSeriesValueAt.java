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
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.List;

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

		Expression left = new StaticReference(Integer.class, getVariableName(0));
		Expression right = new StaticReference(Integer.class, getVariableName(1));
		Expression v1 = new StaticReference(Double.class, getVariableName(2));
		Expression v2 = new StaticReference(Double.class, getVariableName(3));
		Expression t1 = new StaticReference(Double.class, getVariableName(4));
		Expression t2 = new StaticReference(Double.class, getVariableName(5));

		scope.getVariables().add(new ExpressionAssignment(true, left, new IntegerConstant(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, right, new IntegerConstant(-1)));
		scope.getVariables().add(new ExpressionAssignment(true, v1, new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, v2, new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, t1, new DoubleConstant(0.0)));
		scope.getVariables().add(new ExpressionAssignment(true, t2, new DoubleConstant(0.0)));

		Expression res = outputVariable.reference(e(0));
		Expression bank0 = getArgument(1).valueAt(0);
		Expression bank1 = getArgument(1).valueAt(1);
		Expression cursor0 = getArgument(2).valueAt(0);

		Expression bankl0 = getArgument(1).reference(left.multiply(2));
		Expression bankl1 = getArgument(1).reference(left.multiply(2).add(1));
		Expression bankr0 = getArgument(1).reference(right.multiply(2));
		Expression bankr1 = getArgument(1).reference(right.multiply(2).add(1));

		// Linear search for the first stored sample whose time is >= the cursor.
		// The "right == -1" guard turns the body into a no-op once a match is
		// found, reproducing the original loop's break (Repeated has no break).
		Repeated loop = new Repeated<>();
		InstanceReference k = Variable.integer("i").ref();
		loop.setIndex(k.getReferent());
		Expression i = bank0.toInt().add(k);
		loop.setCondition(i.lessThan(bank1));
		loop.setInterval(e(1));

		Expression banki = getArgument(1).reference(i.multiply(2));
		Scope<PackedCollection> match = new Scope<>();
		match.assign(left, i.greaterThan(bank0).conditional(i.subtract(e(1)),
				banki.eq(cursor0).conditional(i, e(-1))));
		match.assign(right, i);
		loop.addCase(right.eq(e(-1)).and(banki.greaterThanOrEqual(cursor0)), match);

		scope.add(loop);

		// Default result; overwritten only when a valid surrounding interval is found.
		// Nested guards replicate the short-circuit of the original
		// "left == -1 || right == -1 || bankl0 > cursor0" check so the
		// out-of-range bank reads in the interpolation block never execute.
		Scope<PackedCollection> body = new Scope<>();
		body.assign(res, e(0));

		Scope<PackedCollection> interp = new Scope<>();
		interp.assign(v1, bankl1);
		interp.assign(v2, bankr1);
		interp.assign(t1, cursor0.subtract(bankl0));
		interp.assign(t2, bankr0.subtract(bankl0));
		interp.assign(res, t2.eq(e(0.0)).conditional(v1,
				v1.add(t1.divide(t2).multiply(v2.subtract(v1)))));

		Scope<PackedCollection> ifBankl = new Scope<>();
		ifBankl.addCase(bankl0.lessThanOrEqual(cursor0), interp);

		Scope<PackedCollection> ifRight = new Scope<>();
		ifRight.addCase(right.eq(e(-1)).not(), ifBankl);

		body.addCase(left.eq(e(-1)).not(), ifRight);

		scope.add(body);

		return scope;
	}
}
