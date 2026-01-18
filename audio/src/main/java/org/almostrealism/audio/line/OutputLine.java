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

package org.almostrealism.audio.line;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

import java.util.function.Supplier;

/**
 * An audio output line for writing audio data to a destination (e.g., speakers, headphones, file).
 * This is the primary interface for all audio output operations in the Rings framework.
 * <p>
 * Implementations should handle the conversion of {@link PackedCollection} audio data
 * to the appropriate output format and manage buffering for real-time or offline rendering.
 * <p>
 * Extends {@link BufferedAudio} for buffering configuration and {@link Destroyable}
 * for lifecycle management.
 *
 * @see SourceDataOutputLine for real-time audio playback to hardware
 * @see BufferedOutputScheduler for managing buffered real-time output
 */
public interface OutputLine extends BufferedAudio, Destroyable {

	/**
	 * Returns the position in the buffer where the last frame was read/consumed by the device.
	 * This is critical for real-time audio playback to prevent buffer underruns and overruns.
	 * Implementations should query the underlying hardware or output system for this value.
	 *
	 * @return The read position in frames, or 0 if not tracking
	 */
	default int getReadPosition() {
		return 0;
	}

	/**
	 * Write all the samples from the specified {@link PackedCollection},
	 * which cannot be larger than the buffer size.
	 *
	 * @see  BufferedAudio#getBufferSize()
	 */
	void write(PackedCollection sample);

	default Supplier<Runnable> write(Producer<PackedCollection> frames) {
		return () -> {
			Evaluable<PackedCollection> sample = frames.get();
			return () -> write(sample.evaluate());
		};
	}

	/**
	 * Resets the output line, typically by closing and recreating the underlying
	 * audio hardware connection. This can be used to recover from audio issues
	 * caused by device switching (e.g., Bluetooth audio device changes).
	 * <p>
	 * Note: After a reset, the read position will restart from 0, which may
	 * cause temporary synchronization issues with any scheduler tracking
	 * playback position.
	 * <p>
	 * The default implementation does nothing; subclasses that support reset
	 * should override this method.
	 */
	default void reset() {
		// Default implementation does nothing
	}

	/**
	 * Starts or resumes the output line. Audio data written to the line will
	 * begin playing after this is called.
	 * <p>
	 * For hardware-backed implementations, this typically activates the
	 * underlying audio device. For other implementations (e.g., file-based
	 * or test mocks), this may be a no-op.
	 * <p>
	 * The default implementation does nothing.
	 */
	default void start() {
		// Default implementation does nothing
	}

	/**
	 * Stops the output line, suspending playback. The line can be restarted
	 * with {@link #start()}.
	 * <p>
	 * For hardware-backed implementations, this stops the underlying audio
	 * device from consuming buffered data. This is useful for implementing
	 * pause functionality without continuously writing silence to the hardware.
	 * <p>
	 * The default implementation does nothing.
	 */
	default void stop() {
		// Default implementation does nothing
	}

	/**
	 * Returns whether the output line is currently active (playing or ready to play).
	 * <p>
	 * For hardware-backed implementations, this reflects the state of the
	 * underlying audio device.
	 *
	 * @return {@code true} if the line is active, {@code false} otherwise
	 */
	default boolean isActive() {
		return true;
	}
}
