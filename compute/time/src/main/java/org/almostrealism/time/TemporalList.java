/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.time;

import io.almostrealism.lifecycle.Lifecycle;
import org.almostrealism.hardware.OperationList;

import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * A collection of {@link Temporal} operations that can be executed synchronously as a single
 * coordinated temporal operation.
 *
 * <p>{@link TemporalList} extends {@link ArrayList} and implements both {@link Temporal} and
 * {@link Lifecycle}, enabling groups of temporal operations to be managed and executed as a
 * unified whole. When ticked, all contained temporals tick together in synchronization.</p>
 *
 * <h2>Core Functionality</h2>
 * <ul>
 *   <li><strong>Synchronized Execution:</strong> All temporals tick together in a single operation</li>
 *   <li><strong>Lifecycle Management:</strong> Reset propagates to all lifecycle-aware children</li>
 *   <li><strong>Standard Collection API:</strong> Full ArrayList functionality for managing temporals</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating and Using a Temporal List</h3>
 * <pre>{@code
 * // Create individual temporal operations
 * Temporal oscillator1 = createOscillator(440.0);  // A4
 * Temporal oscillator2 = createOscillator(554.37); // C#5
 * Temporal filter = createLowPassFilter();
 *
 * // Combine into synchronized list
 * TemporalList synth = new TemporalList();
 * synth.add(oscillator1);
 * synth.add(oscillator2);
 * synth.add(filter);
 *
 * // Tick all operations together
 * Supplier<Runnable> tick = synth.tick();
 * tick.get().run();  // All three operations execute in sync
 * }</pre>
 *
 * <h3>Using Stream Collectors</h3>
 * <pre>{@code
 * // Collect temporals from a stream
 * TemporalList voices = noteFrequencies.stream()
 *     .map(freq -> createOscillator(freq))
 *     .collect(TemporalList.collector());
 *
 * // Run all voices synchronously
 * voices.iter(1000).get().run();
 * }</pre>
 *
 * <h3>Lifecycle Management</h3>
 * <pre>{@code
 * TemporalList operations = new TemporalList();
 * operations.add(statefulOp1);
 * operations.add(statefulOp2);
 *
 * // Run operations
 * operations.iter(100).get().run();
 *
 * // Reset all child operations that implement Lifecycle
 * operations.reset();  // Propagates to children
 * }</pre>
 *
 * <h2>Tick Execution</h2>
 * <p>When {@link #tick()} is called, the returned {@link OperationList} contains the ticks
 * from all child temporal operations, ensuring they execute in the order they were added:</p>
 * <pre>{@code
 * TemporalList list = new TemporalList();
 * list.add(operation1);  // Executes first
 * list.add(operation2);  // Executes second
 * list.add(operation3);  // Executes third
 * }</pre>
 *
 * <h2>Hardware Acceleration</h2>
 * <p>The {@link OperationList} returned by {@link #tick()} can be compiled and optimized for
 * hardware acceleration, enabling efficient GPU execution of complex multi-operation pipelines.</p>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Audio Synthesis Chain</h3>
 * <pre>{@code
 * TemporalList synthVoice = new TemporalList();
 * synthVoice.add(oscillator);      // Sound generation
 * synthVoice.add(envelope);        // Amplitude shaping
 * synthVoice.add(filter);          // Frequency shaping
 * synthVoice.add(effectsChain);    // Reverb, delay, etc.
 * }</pre>
 *
 * <h3>Multi-Track Sequencer</h3>
 * <pre>{@code
 * TemporalList tracks = IntStream.range(0, 16)
 *     .mapToObj(i -> createTrack(i))
 *     .collect(TemporalList.collector());
 *
 * // All tracks advance together
 * tracks.buffer(512).get().run();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>{@link TemporalList} inherits {@link ArrayList}'s lack of thread safety. For concurrent
 * access, external synchronization is required or use thread-safe alternatives.</p>
 *
 * @see Temporal
 * @see OperationList
 * @see Lifecycle
 *
 * @author Michael Murray
 */
public class TemporalList extends ArrayList<Temporal> implements Temporal, Lifecycle {

	/**
	 * Returns a supplier that produces a runnable executing all child temporal operations
	 * in synchronization.
	 *
	 * <p>This method creates an {@link OperationList} containing the ticks from all temporals
	 * in this list, maintaining their insertion order. The resulting operation can be compiled
	 * and optimized for hardware acceleration.</p>
	 *
	 * @return A supplier producing a runnable that ticks all child operations
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("TemporalList Tick");
		stream().map(Temporal::tick).forEach(tick::add);
		return tick;
	}

	/**
	 * Resets this temporal list and all child operations that implement {@link Lifecycle}.
	 *
	 * <p>This method propagates the reset to all children, calling {@code reset()} on each
	 * temporal that implements the {@link Lifecycle} interface. Non-lifecycle temporals are
	 * unaffected.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * TemporalList operations = new TemporalList();
	 * operations.add(statefulOp1);  // Implements Lifecycle
	 * operations.add(statelessOp);  // Does not implement Lifecycle
	 *
	 * operations.reset();  // Only statefulOp1 is reset
	 * }</pre>
	 */
	@Override
	public void reset() {
		Lifecycle.super.reset();
		forEach(t -> {
			if (t instanceof Lifecycle) ((Lifecycle) t).reset();
		});
	}

	/**
	 * Returns a collector that accumulates {@link Temporal} instances into a {@link TemporalList}.
	 *
	 * <p>This collector enables convenient stream-based construction of temporal lists:</p>
	 * <pre>{@code
	 * TemporalList operations = temporalStream
	 *     .filter(t -> t.isActive())
	 *     .collect(TemporalList.collector());
	 * }</pre>
	 *
	 * @return A collector for building temporal lists from streams
	 */
	public static Collector<Temporal, ?, TemporalList> collector() {
		return Collectors.toCollection(TemporalList::new);
	}
}
