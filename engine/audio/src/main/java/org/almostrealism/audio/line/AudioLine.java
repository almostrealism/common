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

/**
 * A bidirectional audio line that supports both input and output operations.
 * This interface combines {@link InputLine} and {@link OutputLine} capabilities,
 * typically used for full-duplex audio processing scenarios.
 */
public interface AudioLine extends InputLine, OutputLine {
	/**
	 * Sets the passthrough level, which controls how much of the input signal
	 * is mixed directly into the output (monitoring).
	 *
	 * @param level Passthrough level from 0.0 (no passthrough) to 1.0 (full passthrough)
	 */
	default void setPassthroughLevel(double level) { }

	/**
	 * Returns the current passthrough level.
	 *
	 * @return Passthrough level from 0.0 to 1.0
	 */
	default double getPassthroughLevel() { return 0.0; }
}
