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

package org.almostrealism.layers;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A diagnostic {@link Receptor} that evaluates incoming producers and logs warnings when
 * NaN or zero values are detected.
 *
 * <p>On each {@link #push(Producer)} call the receptor eagerly evaluates the producer,
 * runs any user-supplied {@link java.util.function.Consumer}, and then scans the result
 * for problematic values. When a NaN is found a warning is logged at error level. When
 * all values are zero a warning is logged. Optionally, when {@link #enableLargeWarning}
 * is {@code true}, values whose absolute sum or maximum exceeds predefined thresholds
 * also trigger a warning.</p>
 *
 * <p>This receptor is installed by {@link DefaultCellularLayer} when
 * {@link org.almostrealism.hardware.HardwareFeatures#outputMonitoring} is enabled, and
 * is also available for standalone use during debugging.</p>
 *
 * @see DefaultCellularLayer#setMonitor(Receptor)
 * @author Michael Murray
 */
public class MonitorReceptor implements Receptor<PackedCollection>, ConsoleFeatures {
	/**
	 * When {@code true}, logs a warning when the absolute sum or element-wise maximum of
	 * the monitored output exceeds {@code 1e6} or {@code 1e9} respectively.
	 */
	public static boolean enableLargeWarning = false;

	/** The human-readable name of the layer being monitored, included in warning messages. */
	private final String name;

	/** The expected input shape for the layer being monitored, used only in warning messages. */
	private final TraversalPolicy inputShape;

	/** The expected output shape for the layer being monitored, used only in warning messages. */
	private final TraversalPolicy outputShape;

	/** An optional consumer that receives the evaluated output for custom inspection. */
	private final Consumer<PackedCollection> op;


	/**
	 * Creates a monitor with a custom consumer and no shape metadata.
	 *
	 * @param op the consumer to invoke with each evaluated output
	 */
	public MonitorReceptor(Consumer<PackedCollection> op) {
		this("monitor", null, null, op);
	}

	/**
	 * Creates a monitor with a name and a custom consumer.
	 *
	 * @param name the label included in warning messages
	 * @param op   the consumer to invoke with each evaluated output
	 */
	public MonitorReceptor(String name, Consumer<PackedCollection> op) {
		this(name, null, null, op);
	}

	/**
	 * Creates a monitor with shape metadata and optional reference collections.
	 *
	 * @param name        the label included in warning messages
	 * @param inputShape  the input shape of the layer being monitored
	 * @param outputShape the output shape of the layer being monitored
	 * @param data        optional reference collections (for future diagnostics)
	 */
	public MonitorReceptor(String name, TraversalPolicy inputShape, TraversalPolicy outputShape, PackedCollection... data) {
		this(name, inputShape, outputShape, null, data);
	}

	/**
	 * Full constructor that sets all fields.
	 *
	 * @param name        the label included in warning messages
	 * @param inputShape  the input shape of the layer being monitored
	 * @param outputShape the output shape of the layer being monitored
	 * @param op          the consumer to invoke with each evaluated output, or {@code null}
	 * @param data        optional reference collections (for future diagnostics)
	 */
	public MonitorReceptor(String name, TraversalPolicy inputShape, TraversalPolicy outputShape,
							Consumer<PackedCollection> op, PackedCollection... data) {
		this.name = name;
		this.inputShape = inputShape;
		this.outputShape = outputShape;
		this.op = op;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Eagerly evaluates {@code in}, invokes the optional consumer, then scans the result
	 * for NaN values, all-zero values, and (if {@link #enableLargeWarning} is set) excessively
	 * large values. Any detected condition logs a warning and returns immediately.</p>
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> in) {
		return () -> () -> {
			PackedCollection out = in.get().evaluate();

			if (op != null) {
				op.accept(out);
			}

			boolean isNaN = out.doubleStream().anyMatch(Double::isNaN);
			boolean isZero = out.doubleStream().allMatch(d -> d == 0.0);
			if (isNaN) {
				warn("Identified NaN from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
				return;
			} else if (isZero) {
				warn("Identified Zero from " + name +
						" layer (" + inputShape + " -> " + outputShape + ")");
				return;
			}

			if (enableLargeWarning) {
				boolean isLarge = out.doubleStream().map(Math::abs).sum() > 1e6 ||
						out.doubleStream().map(Math::abs).max().getAsDouble() > 1e9;
				if (isLarge) {
					warn("Identified large output from " + name +
							" layer (" + inputShape + " -> " + outputShape + ")");
					return;
				}
			}

			if (name != null && name.equals("softmax2d")) {
				double total = out.doubleStream().sum();
				if (total < 0.9) {
					warn("Softmax layer (" + inputShape + " -> " + outputShape + ") sum is " + total);
				}
			}
		};
	}
}
