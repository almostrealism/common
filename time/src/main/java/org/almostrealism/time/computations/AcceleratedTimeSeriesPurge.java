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

import io.almostrealism.code.Precision;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.Process;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;
import org.almostrealism.time.AcceleratedTimeSeries;
import org.almostrealism.time.CursorPair;

import java.util.List;
import java.util.function.Consumer;

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
public class AcceleratedTimeSeriesPurge extends OperationComputationAdapter<PackedCollection> {
	private double wavelength;

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

	@Override
	public Scope<Void> getScope(KernelStructureContext context) {
		HybridScope<Void> scope = new HybridScope<>(this);

		Expression i = new StaticReference(Integer.class, "i");
		String left = getArgument(0).valueAt(0).getSimpleExpression(getLanguage());
		String right = getArgument(0).valueAt(1).getSimpleExpression(getLanguage());
		String banki = getArgument(0).reference(i.multiply(2)).getSimpleExpression(getLanguage());
		String cursor0 = getArgument(1).valueAt(0).getSimpleExpression(getLanguage());
		String count = getArgument(2).valueAt(0).getSimpleExpression(getLanguage());

		Precision p = getLanguage().getPrecision();

		Consumer<String> code = scope.code();
		if (wavelength != 1.0) {
			code.accept(count + " = fmod(" + count + " + " + p.stringForDouble(1.0) + ", " + p.stringForDouble(wavelength) + ");");
			code.accept("if (" + count + " = " + p.stringForDouble(0.0) + ") {\n");
		}

		code.accept("if (" + right + " - " + left + " > 0) {\n");
		code.accept("for (int i = " + left + " + 1; i < " + right + "; i++) {\n");
		code.accept("	if (" + cursor0 + " > " + banki + ") {\n");
		code.accept("		" + left + " = i;\n");
		code.accept("	}\n");
		code.accept("	if (" + cursor0 + " < " + banki + ") {\n");
		code.accept("		break;\n");
		code.accept("	}\n");
		code.accept("}\n");
		code.accept("}\n");

		if (wavelength != 1.0) code.accept("}\n");
		return scope;
	}
}
