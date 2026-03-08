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

package io.almostrealism.frames;

/**
 * Represents a temporal relational frame in Relational Frame Theory (RFT).
 *
 * <p>A temporal frame captures relationships between predicates based on time
 * or sequence. This frame expresses that one predicate occurs before another
 * in a temporal sequence.</p>
 *
 * <p>In RFT, temporal frames are fundamental for understanding cause and effect,
 * narrative sequences, and the organization of events over time. They enable
 * reasoning about past, present, and future relationships.</p>
 *
 * <p>The relationship expressed is: "{@code a} is before {@code b}"</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Predicate sunrise = ...;
 * Predicate sunset = ...;
 * TemporalFrame frame = new TemporalFrame(sunrise, sunset);
 * // Represents: "sunrise is before sunset"
 * }</pre>
 *
 * @see Predicate
 * @see CausalFrame
 * @see SpatialFrame
 *
 * @author  Michael Murray
 */
public class TemporalFrame {
	private Predicate a, b;

	/**
	 * Constructs a temporal frame expressing that predicate {@code a}
	 * occurs before predicate {@code b}.
	 *
	 * @param a the earlier predicate in the temporal sequence
	 * @param b the later predicate in the temporal sequence
	 */
	public TemporalFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns a string representation of this temporal frame.
	 *
	 * @return a string in the format "{@code a} is before {@code b}"
	 */
	public String toString() { return a + " is before " + b; }
}
