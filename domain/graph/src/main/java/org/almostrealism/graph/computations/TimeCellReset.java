/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.code.ArgumentProvider;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;

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
 * <p>The computation generates a compact loop that checks each reset slot,
 * stopping as soon as a matching slot is found:</p>
 * <pre>
 * int _reset_done = 0;
 * for (int _reset_j = 0; _reset_j &lt; resetCount &amp;&amp; _reset_done == 0; _reset_j++) {
 *     if (reset[_reset_j] &gt; 0.0 &amp;&amp; time[1] == reset[_reset_j]) {
 *         time[0] = 0.0;
 *         _reset_done = 1;
 *     }
 * }
 * </pre>
 *
 * <p>This loop-based approach replaces the previous if-else chain, reducing
 * generated code size from O(n) branches to a constant-size loop construct.
 * With 30+ reset slots, this reduces generated code by ~100+ lines.</p>
 *
 * <p>Because {@link Repeated} has no {@code break} statement, the loop-local
 * {@code _reset_done} flag in the continuation condition reproduces the early
 * exit of the original {@code break}: once a slot matches, the flag is set and
 * the scan stops rather than traversing the remaining slots. This mirrors the
 * {@code right == -1} early-exit idiom in
 * {@link org.almostrealism.time.computations.AcceleratedTimeSeriesValueAt}.</p>
 *
 * @author Michael Murray
 * @see org.almostrealism.graph.TimeCell
 * @see OperationComputationAdapter
 */
public class TimeCellReset extends OperationComputationAdapter<PackedCollection> implements ExpressionFeatures {
	/** The hybrid scope containing the generated conditional reset code. */
	protected HybridScope scope;

	/** The number of reset slots to check. */
	private final int len;

	/**
	 * Creates a new TimeCellReset computation.
	 *
	 * @param time   producer for the time pair (looping counter, total counter)
	 * @param resets the collection of scheduled reset frame numbers (-1 = disabled)
	 */
	public TimeCellReset(Producer<Pair> time, PackedCollection resets) {
		super((Producer) time, CollectionFeatures.getInstance().cp(resets));
		len = resets.getMemLength();
	}

	/**
	 * Creates a new TimeCellReset from producer arguments.
	 *
	 * @param len       the number of reset slots
	 * @param arguments the producer arguments (time, resets)
	 */
	private TimeCellReset(int len, Producer<PackedCollection>... arguments) {
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
	 * <p>Prepares the scope by generating a {@link Repeated} loop that checks each reset slot
	 * and resets the frame counter if a match is found. The loop is expressed entirely as
	 * language-independent {@link Expression}s and {@link Scope} statements, so the target
	 * language is not consulted until the scope is rendered.</p>
	 */
	@Override
	public void prepareScope(ArgumentProvider manager, KernelStructureContext context) {
		super.prepareScope(manager, context);

		scope = new HybridScope(this);

		// Loop-local flag implementing the early exit. Repeated has no break, so the
		// scan stops by setting this flag when a slot matches and testing it in the
		// loop condition -- the "right == -1" idiom from AcceleratedTimeSeriesValueAt.
		Expression done = new StaticReference(Integer.class, "_reset_done");
		scope.getVariables().add(new ExpressionAssignment(true, done, e(0)));

		Repeated loop = new Repeated<>();
		InstanceReference j = Variable.integer("_reset_j").ref();
		loop.setIndex(j.getReferent());
		loop.setCondition(j.lessThan(e(len)).and(done.eq(e(0))));
		loop.setInterval(e(1));

		Expression resetAtJ = getResets().valueAt(j);
		Scope<PackedCollection> match = new Scope<>();
		match.assign(getTime().reference(e(0)), e(0.0));
		match.assign(done, e(1));
		loop.addCase(resetAtJ.greaterThan(e(0.0)).and(getTime().valueAt(1).eq(resetAtJ)), match);

		scope.add(loop);
	}
}
