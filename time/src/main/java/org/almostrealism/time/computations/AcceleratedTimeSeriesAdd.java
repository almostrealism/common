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
import java.util.function.Consumer;

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
public class AcceleratedTimeSeriesAdd extends OperationComputationAdapter<AcceleratedTimeSeries> {
	/**
	 * Constructs an add operation for the specified series and temporal scalar.
	 *
	 * @param series Producer providing the target time-series
	 * @param addition Producer providing the temporal scalar to add
	 */
	public AcceleratedTimeSeriesAdd(Producer<AcceleratedTimeSeries> series, Producer<TemporalScalar> addition) {
		super(new Producer[] { series, addition } );
	}

	/**
	 * Private constructor for internal regeneration.
	 *
	 * @param arguments Producer arguments (series, addition)
	 */
	private AcceleratedTimeSeriesAdd(Producer... arguments) {
		super(arguments);
	}

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new AcceleratedTimeSeriesAdd(children.toArray(Producer[]::new));
	}

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);

		Expression<?> bank1 = getArgument(0).valueAt(1);
		String banklast0 = getArgument(0).reference(bank1.toInt().multiply(2)).getSimpleExpression(getLanguage());
		String banklast1 = getArgument(0).reference(bank1.toInt().multiply(2).add(1)).getSimpleExpression(getLanguage());
		String input0 = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String input1 = getArgument(1).valueAt(1).getSimpleExpression(getLanguage());

		Consumer<String> code = scope.code();
		code.accept(banklast0 + " = " + input0 + ";\n");
		code.accept(banklast1 + " = " + input1 + ";\n");
		code.accept(bank1.getSimpleExpression(getLanguage()) + " = " + bank1.getSimpleExpression(getLanguage()) + " + 1.0;\n");
		return scope;
	}
}
