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

package org.almostrealism.audio.pattern;

import org.almostrealism.io.ConsoleFeatures;

import java.util.function.DoubleUnaryOperator;

/**
 * Determines how note duration is calculated for pattern elements.
 *
 * <p>{@code NoteDurationStrategy} controls the length of audio samples played
 * by {@link PatternElement}s. This affects both the musical character (staccato
 * vs legato) and the memory/computation requirements.</p>
 *
 * <h2>Strategies</h2>
 *
 * <ul>
 *   <li><strong>NONE</strong>: Use the note's original/natural duration as provided
 *       by the audio sample. No modification is applied.</li>
 *   <li><strong>FIXED</strong>: Use a fixed duration specified by
 *       {@link PatternElement#getNoteDurationSelection()}, clamped to not exceed
 *       the original duration.</li>
 *   <li><strong>NO_OVERLAP</strong>: Extend the note until the next note position,
 *       preventing gaps while avoiding overlap. Useful for legato passages.</li>
 * </ul>
 *
 * <h2>Duration Calculation</h2>
 *
 * <p>The {@link #getLength} method computes the final duration in seconds:</p>
 * <ul>
 *   <li>{@code timeForDuration}: Converts measure durations to seconds</li>
 *   <li>{@code position}: Current note position in measures</li>
 *   <li>{@code nextPosition}: Next note position (for NO_OVERLAP)</li>
 *   <li>{@code originalDurationSeconds}: Natural sample duration</li>
 *   <li>{@code durationSelection}: Selected duration multiplier (for FIXED)</li>
 * </ul>
 *
 * @see PatternElement#getNoteDuration
 * @see PatternElement#getDurationStrategy
 *
 * @author Michael Murray
 */
public enum NoteDurationStrategy implements ConsoleFeatures {
	NONE, FIXED, NO_OVERLAP;

	public double getLength(DoubleUnaryOperator timeForDuration,
							double position, double nextPosition,
							double originalDurationSeconds, double durationSelection) {
		if (this == NO_OVERLAP & nextPosition <= 0.0) {
			warn("No next position provided for NO_OVERLAP duration strategy");
		}

		return switch (this) {
			case FIXED -> Math.min(originalDurationSeconds, timeForDuration.applyAsDouble(durationSelection));
			case NO_OVERLAP -> timeForDuration.applyAsDouble(nextPosition - position);
			default -> originalDurationSeconds;
		};
	}
}
