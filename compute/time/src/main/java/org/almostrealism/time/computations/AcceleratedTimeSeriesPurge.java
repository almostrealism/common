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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.List;

/**
 * Hardware-accelerated operation for purging old entries from an {@link AcceleratedTimeSeries}.
 *
 * <p>This computation removes time-series entries with timestamps earlier than a specified
 * cursor position by advancing the begin cursor. The purge operation can execute on
 * GPU/accelerator hardware and supports periodic execution via a frequency parameter.</p>
 *
 * <h2>Operation</h2>
 * <pre>
 * 1. Iterate through series from beginCursor to endCursor
 * 2. Find first entry with timestamp >= purge cursor
 * 3. Update beginCursor to that index
 * 4. Old entries become inaccessible (memory not freed, just cursors adjusted)
 * </pre>
 *
 * <h2>Frequency-Based Execution</h2>
 * <p>The {@code frequency} parameter controls how often the purge executes:</p>
 * <ul>
 *   <li><strong>1.0:</strong> Purge every call (always)</li>
 *   <li><strong>0.5:</strong> Purge every other call</li>
 *   <li><strong>0.1:</strong> Purge every 10th call</li>
 * </ul>
 *
 * <p>This is implemented via modulo arithmetic on an internal counter,
 * allowing periodic purging without external timing logic.</p>
 *
 * <h2>Usage</h2>
 * <p>This class is typically not used directly. Instead, use {@link AcceleratedTimeSeries#purge(Producer, double)}:</p>
 * <pre>{@code
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(10000);
 *
 * // Add data over time...
 *
 * // Purge all data before time 100.0
 * CursorPair cursor = new CursorPair(100.0, 100.0);
 * Supplier<Runnable> purgeOp = series.purge(c(cursor), 1.0);
 * purgeOp.get().run();  // Executes on hardware
 *
 * // Series now starts at time 100.0
 * }</pre>
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) linear scan through series</li>
 *   <li><strong>Hardware:</strong> GPU-compatible (single-threaded loop)</li>
 *   <li><strong>Memory:</strong> No deallocation, just cursor adjustment</li>
 * </ul>
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li><strong>Sliding Window:</strong> Maintain recent N seconds of data</li>
 *   <li><strong>Memory Management:</strong> Prevent series from filling up</li>
 *   <li><strong>Real-time Streaming:</strong> Discard processed data</li>
 * </ul>
 *
 * @see AcceleratedTimeSeries#purge(Producer, double)
 * @see CursorPair
 *
 * @author Michael Murray
 */
public class AcceleratedTimeSeriesPurge extends OperationComputationAdapter<PackedCollection>
		implements ExpressionFeatures {
	/** The minimum wavelength (in samples) below which entries are considered stale and purged. */
	private final double wavelength;

	/**
	 * Constructs a purge operation with frequency control.
	 *
	 * @param series Producer providing the target time-series
	 * @param cursors Producer providing the purge cursor (time threshold)
	 * @param frequency How often to purge (1.0 = every call, 0.5 = every other call, etc.)
	 */
	public AcceleratedTimeSeriesPurge(Producer<AcceleratedTimeSeries> series, Producer<CursorPair> cursors, double frequency) {
		super(new Producer[] { series, cursors, () -> new Provider<>(new PackedCollection(1)) });
		this.wavelength = 1.0 / frequency;
	}

	/**
	 * Private constructor for internal regeneration.
	 *
	 * @param wavelength Inverse of frequency (wavelength = 1 / frequency)
	 * @param arguments Producer arguments (series, cursors, counter)
	 */
	private AcceleratedTimeSeriesPurge(double wavelength, Producer<PackedCollection>... arguments) {
		super(arguments);
		this.wavelength = wavelength;
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new AcceleratedTimeSeriesPurge(wavelength, children.toArray(Producer[]::new));
	}

	/**
	 * Builds the scope that purges stale entries from the time series by advancing its begin
	 * cursor past every entry whose time precedes the purge cursor.
	 *
	 * <p><strong>Frequency throttling (currently disabled).</strong> Purge logic is emitted only
	 * when {@code wavelength == 1.0} (purge on every call). The original C used an internal counter
	 * and a {@code frequency < 1.0} gate to throttle purging, but that gate had a latent bug: its
	 * condition {@code count = 0.0} was an assignment (always false in C), so no purge ran for
	 * {@code frequency < 1.0}. The counter is private and unobservable, so the only observable
	 * effect was that the series was left untouched; emitting purge only at {@code wavelength == 1.0}
	 * preserves that. Throttling is still worthwhile (a purge is an O(n) scan of the live region).
	 * To reintroduce it correctly, advance the counter each call
	 * ({@code count = fmod(count + 1, wavelength)}) and guard the purge with a genuine comparison
	 * ({@code count == 0}), leaving the counter intact rather than clobbering it inside the gate.</p>
	 *
	 * <p><strong>Operation-unique snapshot variable.</strong> The begin-cursor snapshot is declared
	 * with an operation-unique name via {@link #getVariableName(int)}: the native (C) backend hoists
	 * declared variables to function scope, so a hardcoded name collides ("redefinition") when
	 * several purge operations are inlined into one kernel (loop indices need no such treatment, as
	 * they are declared inside the for-statement). Because the hoisted declaration carries its
	 * initializer with it, that initializer runs only once when this operation is inlined into an
	 * enclosing per-sample loop; the snapshot must instead capture the current begin cursor on every
	 * invocation, so it is declared with a throwaway value and reassigned as an in-loop statement.</p>
	 *
	 * <p><strong>Early-break emulation.</strong> {@link io.almostrealism.scope.Repeated} has no break
	 * statement, so the early break of the original loop (stop at the first entry at or beyond the
	 * cursor) is reproduced by the {@code cursor0 >= banki} term in the loop condition; without it the
	 * loop scans the full live region every call (O(n) per sample, O(n^2) across a delay line). The
	 * conjunction lowers to a bitwise {@code &} (no short-circuit), so {@code banki} is read even on
	 * the terminating check; that read stays in bounds because the series allocates
	 * {@code maxEntries + 1} slots and {@code add()} caps the end cursor at {@code maxEntries}.</p>
	 *
	 * @param context the kernel structure context
	 * @return the purge scope
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);

		// Purge only when not throttled; throttling is currently disabled (see javadoc).
		if (wavelength == 1.0) {
			Expression left = getArgument(0).valueAt(0);
			Expression right = getArgument(0).valueAt(1);
			Expression cursor0 = getArgument(1).valueAt(0);

			Scope<Void> guard = new Scope<>();
			// Operation-unique name + per-invocation reassignment of the hoisted snapshot (see javadoc).
			Expression start = guard.declareInteger(getVariableName(0), e(0).toInt());
			guard.assign(start, left.add(e(1)).toInt());

			// Advance the begin cursor past entries older than the purge cursor; the loop
			// condition emulates the original early break (see javadoc).
			Repeated loop = new Repeated<>();
			InstanceReference offset = Variable.integer("i").ref();
			loop.setIndex(offset.getReferent());
			Expression idx = start.add(offset);
			Expression banki = getArgument(0).reference(idx.multiply(2));
			loop.setCondition(idx.lessThan(right).and(cursor0.greaterThanOrEqual(banki)));
			loop.setInterval(e(1));

			loop.addCase(cursor0.greaterThan(banki), getArgument(0).reference(e(0)).assign(idx));

			guard.add(loop);
			scope.addCase(right.subtract(left).greaterThan(e(0)), guard);
		}

		return scope;
	}
}
