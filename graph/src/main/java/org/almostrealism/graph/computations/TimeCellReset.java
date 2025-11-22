/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.function.Consumer;

/**
 * Computation that resets a {@link org.almostrealism.graph.TimeCell} frame counter
 * at scheduled reset points.
 *
 * <p>{@code TimeCellReset} extends {@link OperationComputationAdapter} to implement
 * conditional frame counter reset logic. It checks if the current total frame count
 * matches any of the scheduled reset values and, if so, resets the looping frame
 * counter to zero.</p>
 *
 * <p>This is used by {@link org.almostrealism.graph.TimeCell} to support scheduled
 * restarts of temporal sequences at specific points during playback.</p>
 *
 * <p>The computation generates conditional code that checks each reset slot:</p>
 * <pre>
 * if (reset[0] > 0 && time[1] == reset[0]) {
 *     time[0] = 0.0;
 * } else if (reset[1] > 0 && time[1] == reset[1]) {
 *     time[0] = 0.0;
 * }
 * // ... for each reset slot
 * </pre>
 *
 * @author Michael Murray
 * @see org.almostrealism.graph.TimeCell
 * @see OperationComputationAdapter
 */
public class TimeCellReset extends OperationComputationAdapter<PackedCollection<?>> implements ExpressionFeatures {
	/** The hybrid scope containing the generated conditional reset code. */
	protected HybridScope scope;

	/** The number of reset slots to check. */
	private int len;

	/**
	 * Creates a new TimeCellReset computation.
	 *
	 * @param time   producer for the time pair (looping counter, total counter)
	 * @param resets the collection of scheduled reset frame numbers (-1 = disabled)
	 */
	public TimeCellReset(Producer<Pair<?>> time, PackedCollection<?> resets) {
		super((Producer) time, () -> new Provider<>(resets));
		len = resets.getMemLength();
	}

	/**
	 * Creates a new TimeCellReset from producer arguments.
	 *
	 * @param len       the number of reset slots
	 * @param arguments the producer arguments (time, resets)
	 */
	private TimeCellReset(int len, Producer<PackedCollection<?>>... arguments) {
		super(arguments);
		this.len = len;
	}

	/**
	 * Returns the time pair variable (index 0: looping, index 1: total).
	 *
	 * @return the time array variable
	 */
	public ArrayVariable getTime() { return getArgument(0); }

	/**
	 * Returns the reset schedule variable containing frame numbers to reset at.
	 *
	 * @return the resets array variable
	 */
	public ArrayVariable getResets() { return getArgument(1); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Creates a new TimeCellReset instance from the child processes.</p>
	 */
	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new TimeCellReset(len, children.toArray(Producer[]::new));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the hybrid scope containing the conditional reset logic.</p>
	 */
	@Override
	public Scope getScope(KernelStructureContext context) { return scope; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Prepares the scope by generating conditional code that checks each
	 * reset slot and resets the frame counter if a match is found.</p>
	 */
	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		scope = new HybridScope(this);

		Consumer<String> exp = scope.code();

		for (int i = 0; i < len; i++) {
			if (i > 0) exp.accept(" else ");

			Expression<Boolean> condition = getResets().valueAt(1).greaterThan(e(0.0));
			condition = condition.and(getTime().reference(e(1)).eq(getResets().valueAt(i)));

//			exp.accept("if (" + getTime().ref(1).getSimpleExpression() + " == " + getResets().valueAt(i).getSimpleExpression() + ") {\n");
			exp.accept("if (" + condition.getSimpleExpression(getLanguage()) + ") {\n");
			exp.accept("\t");
			exp.accept(getTime().valueAt(0).getSimpleExpression(getLanguage()));
			exp.accept(" = ");
			exp.accept(getLanguage().getPrecision().stringForDouble(0.0));
			exp.accept(";\n");
			exp.accept("}");
		}
	}
}
