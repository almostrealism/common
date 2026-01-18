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
 * Represents a comparative relational frame in Relational Frame Theory (RFT).
 *
 * <p>A comparative frame captures relationships between predicates based on
 * relative magnitude, quantity, or degree along some dimension. This frame
 * expresses that one predicate is larger (greater, more, etc.) than another.</p>
 *
 * <p>In RFT, comparative frames are fundamental for evaluating and ranking
 * stimuli, making judgments about relative properties, and understanding
 * gradients or scales. They enable reasoning about "more than," "less than,"
 * "better than," and similar comparative relationships.</p>
 *
 * <p>The relationship expressed is: "{@code b} is larger than {@code a}"</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Predicate ant = ...;
 * Predicate elephant = ...;
 * ComparativeFrame frame = new ComparativeFrame(ant, elephant);
 * // Represents: "elephant is larger than ant"
 * }</pre>
 *
 * @see Predicate
 * @see CoordinationFrame
 *
 * @author  Michael Murray
 */
public class ComparativeFrame {
	private Predicate a, b;

	/**
	 * Constructs a comparative frame expressing that predicate {@code b}
	 * is larger (or greater in some dimension) than predicate {@code a}.
	 *
	 * @param a the smaller or lesser predicate
	 * @param b the larger or greater predicate
	 */
	public ComparativeFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns a string representation of this comparative frame.
	 *
	 * @return a string in the format "{@code b} is larger than {@code a}"
	 */
	public String toString() { return b + " is larger than " + a; }
}
