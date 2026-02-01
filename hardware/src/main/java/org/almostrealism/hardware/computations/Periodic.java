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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.PeriodicScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.mem.Bytes;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * {@link OperationComputationAdapter} that generates counter-based periodic execution
 * in compiled code.
 *
 * <p>{@link Periodic} wraps a {@link Computation} (the atom) and executes it once every
 * {@code period} invocations, using a persistent counter stored in a
 * {@link MemoryData}. In compiled code, this generates an increment-and-check
 * pattern rather than a for-loop.</p>
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * // Create computation to run periodically
 * Computation<Void> renderBatch = createRenderOperation();
 *
 * // Execute renderBatch once every 1024 ticks
 * Periodic periodic = new Periodic(renderBatch, 1024);
 *
 * // Compile to scope
 * Scope<Void> scope = periodic.getScope(context);
 * }</pre>
 *
 * <h2>Generated Code Structure</h2>
 * <p>For a periodic with {@code period = 1024}:</p>
 * <pre>{@code
 * void periodic_1024() {
 *     arg0[0] = arg0[0] + 1;
 *     if (arg0[0] >= 1024) {
 *         // Atom computation body
 *         renderBatch();
 *         arg0[0] = 0;
 *     }
 * }
 * }</pre>
 *
 * <h2>Counter State</h2>
 * <p>The counter is a {@link Bytes} of size 1, passed as argument 0.
 * This persistent memory survives across compiled invocations, allowing the
 * counter to accumulate across multiple calls.</p>
 *
 * <h2>Inside a Loop</h2>
 * <p>When nested inside a {@link Loop}, the common audio processing pattern
 * becomes fully compilable:</p>
 * <pre>{@code
 * for (int i = 1; i < 44100; i += 1) {
 *     arg0[0] = arg0[0] + 1;
 *     if (arg0[0] >= 1024) {
 *         renderBatch();
 *         arg0[0] = 0;
 *     }
 *     // other tick operations
 * }
 * }</pre>
 *
 * <h2>Java Fallback</h2>
 * <p>When the atom is not a compilable {@link Computation} (e.g., it was
 * replaced during process optimization), the {@link #generate(List)} method
 * produces a Java-based fallback that reads and writes the counter via
 * {@link MemoryData} directly.</p>
 *
 * @see PeriodicScope
 * @see Loop
 * @see OperationComputationAdapter
 * @see ExpressionFeatures
 *
 * @author Michael Murray
 */
public class Periodic extends OperationComputationAdapter<MemoryData>
		implements ExpressionFeatures {
	private final Computation atom;
	private final int period;
	private final MemoryData counter;

	/**
	 * Creates a new {@link Periodic} with a fresh counter.
	 *
	 * @param atom   the computation to execute periodically
	 * @param period the number of invocations between executions
	 */
	public Periodic(Computation<Void> atom, int period) {
		this(atom, period, new Bytes(1));
	}

	/**
	 * Creates a new {@link Periodic} with a specified counter.
	 *
	 * <p>This constructor is used internally when reconstructing during
	 * process optimization to preserve the existing counter state.</p>
	 *
	 * @param atom    the computation to execute periodically
	 * @param period  the number of invocations between executions
	 * @param counter the persistent counter memory (size 1)
	 */
	private Periodic(Computation<Void> atom, int period, MemoryData counter) {
		super(() -> new Provider(counter));
		this.atom = atom;
		this.period = period;
		this.counter = counter;
		init();
	}

	@Override
	public String getName() {
		return "Periodic /" + period;
	}

	@Override
	protected List<Computation<?>> getDependentComputations() {
		return List.of(atom);
	}

	@Override
	public long getCountLong() {
		return atom instanceof Countable ? ((Countable) atom).getCountLong() : 1;
	}

	/**
	 * Returns the atom computation as the sole child of this periodic.
	 *
	 * <p>This allows the optimization framework to properly traverse and optimize
	 * the contained computation before the periodic scope is generated.</p>
	 *
	 * @return a collection containing the atom computation, if it's a {@link Process}
	 */
	@Override
	public Collection<Process<?, ?>> getChildren() {
		if (atom instanceof Process) {
			return Collections.singletonList((Process<?, ?>) atom);
		}

		return Collections.emptyList();
	}

	/**
	 * Generates a replacement for this periodic from optimized children.
	 *
	 * <p>If the child is still a {@link Computation}, a new {@link Periodic} is
	 * created preserving the counter. Otherwise, a Java-based fallback is
	 * generated using direct counter read/write.</p>
	 *
	 * @param children the optimized child processes (must contain exactly one)
	 * @return a new process representing this periodic
	 * @throws IllegalArgumentException if children size is not 1
	 */
	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		if (children.size() != 1) throw new IllegalArgumentException();

		if (children.get(0) instanceof Computation) {
			return new Periodic((Computation) children.get(0), period, counter);
		}

		OperationList op = new OperationList("Periodic /" + period);
		op.add(() -> {
			Runnable r = (Runnable) children.get(0).get();
			return () -> {
				double count = counter.toDouble(0) + 1;
				if (count >= period) {
					r.run();
					count = 0;
				}
				counter.setMem(0, count);
			};
		});
		return op;
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		atom.prepareArguments(map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		atom.prepareScope(manager, context);
	}

	/**
	 * Generates the compiled scope for this periodic computation.
	 *
	 * <p>Creates a {@link PeriodicScope} with the counter expression pointing
	 * to argument 0, element 0 (the persistent counter collection), and
	 * the atom's scope as a child.</p>
	 *
	 * @param context the kernel structure context for scope generation
	 * @return a {@link PeriodicScope} representing this computation
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		PeriodicScope<Void> scope = new PeriodicScope<>(getFunctionName(), getMetadata());
		scope.setCounter(getArgument(0).valueAt(e(0)));
		scope.setPeriod(period);
		scope.add(atom.getScope(context));
		return scope;
	}
}
