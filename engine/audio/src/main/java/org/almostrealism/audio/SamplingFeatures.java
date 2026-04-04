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

package org.almostrealism.audio;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * Interface for managing sample rate context in audio processing operations.
 *
 * <p>SamplingFeatures provides thread-local storage for sample rate and frame
 * position, enabling audio operations to access timing information without
 * explicit parameter passing. This is particularly useful in deeply nested
 * audio processing chains.</p>
 *
 * <h2>Sample Rate Management</h2>
 * <p>The default sample rate is {@link OutputLine#sampleRate} (44100 Hz).
 * Custom sample rates can be set using {@link #sampleRate(int, Supplier)}:</p>
 * <pre>{@code
 * // Process at 48000 Hz sample rate
 * sampleRate(48000, () -> {
 *     // All audio operations here use 48000 Hz
 *     return processAudio();
 * });
 * }</pre>
 *
 * <h2>Frame Position</h2>
 * <p>The frame producer tracks the current sample position during processing.
 * This enables time-dependent operations like oscillators and envelopes:</p>
 * <pre>{@code
 * // Get current time in seconds
 * CollectionProducer currentTime = time();  // frame / sampleRate
 * }</pre>
 *
 * <h2>Time Conversion Utilities</h2>
 * <ul>
 *   <li>{@link #toFrames(double)} - Convert seconds to sample frames</li>
 *   <li>{@link #toFramesMilli(int)} - Convert milliseconds to sample frames</li>
 *   <li>{@link #time()} - Get current time as a Producer</li>
 * </ul>
 *
 * @see OutputLine#sampleRate
 * @see CellFeatures
 */
public interface SamplingFeatures extends CodeFeatures {
	/** Thread-local storage for the current sample rate, overriding the default when set. */
	ThreadLocal<Integer> sampleRate = new ThreadLocal<>();
	/** Thread-local storage for the current frame position producer. */
	ThreadLocal<Producer<PackedCollection>> frames = new ThreadLocal<>();

	/**
	 * Executes a supplier within a frame-position context, temporarily setting the frame producer.
	 *
	 * @param <T> the return type
	 * @param f   the frame position producer to set for the duration
	 * @param r   the supplier to execute in the frame context
	 * @return the result of the supplier
	 */
	default <T> T frames(Producer<PackedCollection> f, Supplier<T> r) {
		Producer<PackedCollection> lastT = frames.get();

		try {
			frames.set(f);
			return r.get();
		} finally {
			frames.set(lastT);
		}
	}

	/**
	 * Returns the current frame position producer from thread-local context.
	 *
	 * @return the current frame position producer
	 * @throws UnsupportedOperationException if no frame context has been set
	 */
	default Producer<PackedCollection> frame() {
		Producer<PackedCollection> f = frames.get();
		if (f == null) {
			throw new UnsupportedOperationException();
		}

		return f;
	}

	/**
	 * Returns the current time as a producer, computed as frame index divided by sample rate.
	 *
	 * @return a CollectionProducer representing the current time in seconds
	 */
	default CollectionProducer time() { return divide(frame(), c(sampleRate())); }

	/**
	 * Executes a supplier within a sample rate context, temporarily setting the sample rate.
	 *
	 * @param <T> the return type
	 * @param sr  the sample rate to use for the duration
	 * @param r   the supplier to execute in the sample rate context
	 * @return the result of the supplier
	 */
	default <T> T sampleRate(int sr, Supplier<T> r) {
		Integer lastSr = sampleRate.get();

		try {
			sampleRate.set(sr);
			return r.get();
		} finally {
			sampleRate.set(lastSr);
		}
	}

	/**
	 * Returns the current sample rate from thread-local context, or the default if not set.
	 *
	 * @return the current sample rate in Hz
	 */
	default int sampleRate() { return sampleRate.get() == null ? OutputLine.sampleRate : sampleRate.get(); }

	/**
	 * Executes a supplier within a combined sample rate and frame context.
	 *
	 * @param <T>  the return type
	 * @param rate the sample rate to use
	 * @param r    the supplier to execute in the sampling context
	 * @return the result of the supplier
	 */
	default <T> T sampling(int rate, Supplier<T> r) {
		return sampleRate(rate, () -> frames(integers(), r));
	}

	/**
	 * Executes a supplier within a combined sample rate and duration-bounded frame context.
	 *
	 * @param <T>      the return type
	 * @param rate     the sample rate to use
	 * @param duration the nominal duration in seconds (currently uses unbounded integers)
	 * @param r        the supplier to execute in the sampling context
	 * @return the result of the supplier
	 */
	default <T> T sampling(int rate, double duration, Supplier<T> r) {
//		int frames = (int) (rate * duration);
//		return sampleRate(rate, () -> frames(integers(0, frames), r));
		return sampleRate(rate, () -> frames(integers(), r));
	}

	/**
	 * Sets up sampling context for a specific frame range within a note.
	 *
	 * <p>Unlike {@link #sampling(int, double, Supplier)}, which uses dynamic-sized
	 * frame indices starting at 0, this method uses fixed-size frame indices
	 * that produce only {@code frameCount} output frames with frame indices
	 * {@code [offset, offset + frameCount)}, correctly positioning
	 * time-dependent effects within the note.</p>
	 *
	 * <h3>Signature Independence</h3>
	 * <p>The frame indices are constructed as {@code integers(0, frameCount).add(p(offset))}
	 * where the offset is a caller-owned {@link PackedCollection} containing the start frame.
	 * Because the offset is a {@link org.almostrealism.collect.computations.CollectionProviderProducer}
	 * backed by a stable {@link PackedCollection} (same memory address across calls), the
	 * computation signature depends only on {@code frameCount} and the instrument chain
	 * structure, not on the actual start frame value. This enables compiled kernel reuse
	 * across different frame positions via the instruction set cache.</p>
	 *
	 * @param rate sample rate
	 * @param offset caller-owned PackedCollection containing the start frame value
	 * @param frameCount number of frames to produce
	 * @param r supplier to evaluate within the sampling context
	 * @return the result of the supplier
	 */
	default <T> T sampling(int rate, PackedCollection offset, int frameCount, Supplier<T> r) {
		return sampleRate(rate, () -> frames(
				integers(0, frameCount).add(p(offset)), r));
	}

	/**
	 * Converts a duration in seconds to sample frames using the current sample rate.
	 *
	 * @param sec the duration in seconds
	 * @return the equivalent number of sample frames
	 */
	default int toFrames(double sec) { return (int) (sampleRate() * sec); }

	/**
	 * Converts a duration producer in seconds to a frames producer using the current sample rate.
	 *
	 * @param sec producer yielding a duration in seconds
	 * @return a producer yielding the equivalent number of sample frames
	 */
	default Producer<PackedCollection> toFrames(Producer<PackedCollection> sec) {
		return multiply(c(sampleRate()), sec);
	}

	/**
	 * Converts a duration in milliseconds to sample frames using the current sample rate.
	 *
	 * @param msec the duration in milliseconds
	 * @return the equivalent number of sample frames
	 */
	default int toFramesMilli(int msec) { return (int) (sampleRate() * msec / 1000d); }

	/**
	 * Converts a duration producer in milliseconds to a frames producer using the current sample rate.
	 *
	 * @param msec producer yielding a duration in milliseconds
	 * @return a producer yielding the equivalent number of sample frames
	 */
	default Producer<PackedCollection> toFramesMilli(Producer<PackedCollection> msec) {
		return multiply(c(sampleRate() / 1000d), msec);
	}

	/**
	 * Produces granular synthesis output by sampling input audio at grain-controlled positions.
	 *
	 * <p>Each grain is described by a start time, duration, and playback rate. The output
	 * is interpolated from the input at the computed position, windowed by a sinusoidal envelope.</p>
	 *
	 * @param input      the source audio data producer
	 * @param grain      the grain parameters producer (start, duration, rate per grain)
	 * @param wavelength the sinusoidal window wavelength
	 * @param phase      the sinusoidal window phase offset
	 * @param amp        the sinusoidal window amplitude
	 * @return a CollectionProducer yielding the granular synthesis output
	 */
	default CollectionProducer grains(Producer<PackedCollection> input,
									  Producer<PackedCollection> grain,
									  Producer<PackedCollection> wavelength,
									  Producer<PackedCollection> phase,
									  Producer<PackedCollection> amp) {
		CollectionProducer start = c(grain, 0).multiply(c(sampleRate()));
		CollectionProducer d = c(grain, 1).multiply(c(sampleRate()));
		CollectionProducer rate = c(grain, 2);
		CollectionProducer w = multiply(wavelength, c(sampleRate()));

		Producer<PackedCollection> series = frame();
//		Producer<PackedCollection> max = subtract(p(count), start);
//		Producer<PackedCollection> pos  = start.add(_mod(_mod(series, d), max));
		Producer<PackedCollection> pos  = start.add(mod(series, d));

		CollectionProducer generate = interpolate(input, pos, rate);
		return generate.multiply(sinw(series, w, phase, amp));
	}
}
