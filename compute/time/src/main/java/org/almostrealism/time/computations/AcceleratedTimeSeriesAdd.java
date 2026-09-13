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
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.TemporalScalar;

import java.util.List;

/**
 * Hardware-accelerated operation for adding {@link TemporalScalar} values to
 * an {@link AcceleratedTimeSeries}.
 *
 * <p>This computation writes a time-value pair to the next available position
 * in the series and increments the end cursor, all on GPU/accelerator hardware.
 * It's used internally by {@link AcceleratedTimeSeries#add(Producer)} to enable
 * hardware-accelerated time-series population.</p>
 *
 * <h2>Operation</h2>
 * <pre>
 * 1. Read end cursor from series (index 0, position B)
 * 2. Write temporal scalar to position indicated by end cursor
 * 3. Increment end cursor
 * </pre>
 *
 * <h2>Usage</h2>
 * <p>This class is typically not used directly. Instead, use {@link AcceleratedTimeSeries#add(Producer)}:</p>
 * <pre>{@code
 * AcceleratedTimeSeries series = new AcceleratedTimeSeries(1024);
 * Producer<TemporalScalar> newData = c(new TemporalScalar(1.0, 2.0));
 *
 * // Creates AcceleratedTimeSeriesAdd internally
 * Supplier<Runnable> addOp = series.add(newData);
 * addOp.get().run();  // Executes on hardware
 * }</pre>
 *
 * <h2>Performance</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(1) constant time</li>
 *   <li><strong>Hardware:</strong> Fully GPU-compatible</li>
 *   <li><strong>Synchronization:</strong> No locking needed</li>
 * </ul>
 *
 * @see AcceleratedTimeSeries#add(Producer)
 * @see TemporalScalar
 *
 * @author Michael Murray
 */
public class AcceleratedTimeSeriesAdd extends OperationComputationAdapter<AcceleratedTimeSeries>
		implements ExpressionFeatures {
	/**
	 * Sentinel {@code fullCursorIndex} used by the legacy two-argument constructor. The end
	 * cursor of an {@link AcceleratedTimeSeries} can never reach this value, so the write is
	 * never guarded, matching the unguarded behavior the two-argument constructor had before
	 * the guard was introduced.
	 */
	public static final int NO_CAPACITY_GUARD = Integer.MAX_VALUE;

	/**
	 * The end cursor value at which the series is full. A write attempted at or beyond this
	 * index is dropped instead of writing past the end of the series allocation.
	 */
	private final int fullCursorIndex;

	/**
	 * Constructs an add operation for the specified series and temporal scalar, with no
	 * guard against writing past the end of the series allocation.
	 *
	 * @param series Producer providing the target time-series
	 * @param addition Producer providing the temporal scalar to add
	 * @deprecated Use {@link #AcceleratedTimeSeriesAdd(Producer, Producer, int)} so that a
	 *             write attempted once the series is full is dropped rather than writing
	 *             outside the allocation
	 */
	@Deprecated
	public AcceleratedTimeSeriesAdd(Producer<AcceleratedTimeSeries> series, Producer<TemporalScalar> addition) {
		this(series, addition, NO_CAPACITY_GUARD);
	}

	/**
	 * Constructs an add operation for the specified series and temporal scalar.
	 *
	 * @param series Producer providing the target time-series
	 * @param addition Producer providing the temporal scalar to add
	 * @param fullCursorIndex The end cursor value at which the series is full; a write
	 *                        attempted at or beyond this index is dropped instead of
	 *                        writing outside the series allocation
	 */
	public AcceleratedTimeSeriesAdd(Producer<AcceleratedTimeSeries> series, Producer<TemporalScalar> addition,
									 int fullCursorIndex) {
		super(new Producer[] { series, addition });
		this.fullCursorIndex = fullCursorIndex;
	}

	/**
	 * Private constructor for internal regeneration.
	 *
	 * @param fullCursorIndex The end cursor value at which the series is full
	 * @param arguments Producer arguments (series, addition)
	 */
	private AcceleratedTimeSeriesAdd(int fullCursorIndex, Producer... arguments) {
		super(arguments);
		this.fullCursorIndex = fullCursorIndex;
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new AcceleratedTimeSeriesAdd(fullCursorIndex, children.toArray(Producer[]::new));
	}

	/**
	 * Builds the scope that writes the new entry at the end cursor and advances it, unless
	 * the end cursor has already reached {@link #fullCursorIndex}. In that case the write is
	 * skipped entirely: the series' allocation ends at that index, so writing there (or
	 * beyond it) would write outside the allocated storage. This mirrors, on the hardware
	 * path, the {@code RuntimeException} that {@link AcceleratedTimeSeries#add(TemporalScalar)}
	 * throws on the CPU path when the series is full — a kernel cannot throw, so the entry is
	 * dropped instead.
	 *
	 * @param context the kernel structure context
	 * @return the add scope
	 */
	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);

		Expression<?> bank1 = getArgument(0).valueAt(1);

		Scope<Void> write = new Scope<>();
		write.assign(getArgument(0).reference(bank1.toInt().multiply(2)),
				getArgument(1).valueAt(0));
		write.assign(getArgument(0).reference(bank1.toInt().multiply(2).add(1)),
				getArgument(1).valueAt(1));
		write.assign(getArgument(0).reference(e(1)), bank1.add(e(1.0)));

		scope.addCase(bank1.lessThan(e(fullCursorIndex)), write);
		return scope;
	}
}
