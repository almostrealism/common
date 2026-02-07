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
