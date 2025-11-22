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

package org.almostrealism.graph;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.computations.TimeCellReset;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.time.Temporal;

import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A cell that tracks time progression through discrete frame iterations.
 *
 * <p>{@code TimeCell} implements frame-based timing for temporal computations,
 * maintaining a frame counter that advances with each {@link #tick()} call.
 * It supports optional looping behavior where the frame counter wraps around
 * after reaching a specified duration.</p>
 *
 * <p>The cell maintains two frame values as a {@link Pair}:</p>
 * <ul>
 *   <li>Left value (index 0): Current frame position, subject to looping</li>
 *   <li>Right value (index 1): Total accumulated frames (monotonically increasing)</li>
 * </ul>
 *
 * <p>The cell also supports scheduled reset points where the frame counter
 * can be reset to zero at specific frame numbers.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Create a TimeCell with a loop duration of 44100 frames (1 second at 44.1kHz)
 * TimeCell cell = new TimeCell(null, c(44100));
 * cell.setup().get().run();
 *
 * // Advance time
 * Runnable tick = cell.tick().get();
 * for (int i = 0; i < 100; i++) {
 *     tick.run();
 * }
 *
 * // Get current frame (will loop back after 44100 frames)
 * double frame = cell.getFrame();
 * }</pre>
 *
 * @author Michael Murray
 * @see Cell
 * @see Temporal
 * @see TimeCellReset
 */
public class TimeCell implements Cell<PackedCollection<?>>, Temporal, Destroyable, CodeFeatures {
	private Receptor r;
	private Pair<?> time;
	private Producer<PackedCollection<?>> initial, loopDuration;
	private PackedCollection<?> resets;

	/**
	 * Creates a new TimeCell with default settings and a single reset slot.
	 */
	public TimeCell() {
		this(1);
	}

	/**
	 * Creates a new TimeCell with the specified maximum number of reset points.
	 *
	 * @param maxResets the maximum number of scheduled reset points
	 */
	public TimeCell(int maxResets) {
		this.time = new Pair<>();
		this.resets = new PackedCollection<>(maxResets);
		initResets();
	}

	/**
	 * Creates a new TimeCell with initial offset and loop duration.
	 *
	 * @param initial      producer for the initial frame offset, or null to start at 0
	 * @param loopDuration producer for the loop duration in frames, or null for no looping
	 */
	public TimeCell(Producer<PackedCollection<?>> initial, Producer<PackedCollection<?>> loopDuration) {
		this(initial, loopDuration, 1);
	}

	/**
	 * Creates a new TimeCell with initial offset, loop duration, and reset capacity.
	 *
	 * @param initial      producer for the initial frame offset, or null to start at 0
	 * @param loopDuration producer for the loop duration in frames, or null for no looping
	 * @param maxResets    the maximum number of scheduled reset points
	 */
	public TimeCell(Producer<PackedCollection<?>> initial, Producer<PackedCollection<?>> loopDuration, int maxResets) {
		this(maxResets);
		this.initial = initial;
		this.loopDuration = loopDuration;
	}

	/**
	 * Initializes all reset slots to -1 (disabled).
	 */
	protected void initResets() {
		double initial[] = new double[resets.getMemLength()];
		IntStream.range(0, initial.length).forEach(i -> initial[i] = -1);
		resets.setMem(initial);
	}

	/**
	 * Schedules a reset to occur at a specific frame number.
	 *
	 * @param index the reset slot index (0 to maxResets-1)
	 * @param value the frame number at which to reset, or -1 to disable
	 */
	public void setReset(int index, int value) {
		resets.setMem(index, (double) value);
	}

	/**
	 * Gets the frame number scheduled for a reset slot.
	 *
	 * @param index the reset slot index
	 * @return the scheduled reset frame, or -1 if disabled
	 */
	public int getReset(int index) {
		return (int) resets.toDouble(index);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Initializes the time cell by setting the frame counter to the initial
	 * value (or 0 if no initial value is specified).</p>
	 *
	 * @return a supplier that provides the setup operation
	 */
	@Override
	public Supplier<Runnable> setup() {
		if (initial == null) {
			return a(cp(time), c(0.0).repeat(2));
		} else {
			return a(cp(time), repeat(2, initial));
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Pushes the current frame value to the connected receptor, if any.</p>
	 *
	 * @param protein the input value (unused)
	 * @return a supplier that provides the push operation
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
		return r == null ? new OperationList("TimeCell Push") : r.push(frame());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Advances the frame counter by one. If a loop duration is set, the
	 * left value wraps around using modulo arithmetic. Scheduled resets are
	 * also checked and applied if the current frame matches a reset point.</p>
	 *
	 * @return a supplier that provides the tick operation
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("TimeCell Tick");

		if (loopDuration == null) {
			tick.add(a(cp(time),
					add(cp(time), c(1.0).repeat(2))));
		} else {
			Producer<PackedCollection<?>> ld = c(loopDuration, 0);
			Producer<PackedCollection<?>> left = cp(time.range(shape(1)));
			left = add(left, c(1.0));
			left = greaterThanConditional(ld, c(0.0), mod(left, ld), left, false);

			Producer<PackedCollection<?>> right = cp(time.range(shape(1), 1));
			right = add(right, c(1.0));

			tick.add(a(cp(time.range(shape(1))), left));
			tick.add(a(cp(time.range(shape(1), 1)), right));
		}

		tick.add(new TimeCellReset(p(time), resets));
		return tick;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Sets the receptor that will receive frame values when {@link #push} is called.</p>
	 *
	 * @param r the receptor to connect, or null to disconnect
	 */
	@Override
	public void setReceptor(Receptor<PackedCollection<?>> r) {
		if (cellWarnings && this.r != null) {
			warn("Replacing receptor");
		}

		this.r = r;
	}

	/**
	 * Sets the current frame position directly.
	 *
	 * <p>Both the looping frame counter and the total frame counter are set
	 * to the floor of the specified value.</p>
	 *
	 * @param frame the frame position to set
	 */
	public void setFrame(double frame) {
		double f = Math.floor(frame);
		time.setMem(f, f);
	}

	/**
	 * Gets the current frame position (the looping counter).
	 *
	 * @return the current frame number
	 */
	public double getFrame() {
		return time.toDouble(0);
	}

	/**
	 * Returns a producer for the current frame position.
	 *
	 * @return producer providing the current frame value
	 */
	public Producer<PackedCollection<?>> frame() { return cp(time.range(shape(1))); }

	/**
	 * Returns a producer for the current time in seconds.
	 *
	 * @param sampleRate the sample rate in Hz to convert frames to seconds
	 * @return producer providing the time in seconds (frame / sampleRate)
	 */
	public Producer<PackedCollection<?>> time(double sampleRate) {
		return divide(frame(), c(sampleRate));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Releases the time and reset collections used by this cell.</p>
	 */
	@Override
	public void destroy() {
		Destroyable.super.destroy();
		if (time != null) time.destroy();
		if (resets != null) resets.destroy();
		time = null;
		resets = null;
	}
}
