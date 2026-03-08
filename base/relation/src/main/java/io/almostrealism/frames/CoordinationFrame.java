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
 * Represents a coordination relational frame in Relational Frame Theory (RFT).
 *
 * <p>A coordination frame (also known as a frame of equivalence or sameness)
 * captures relationships between predicates based on similarity, equivalence,
 * or identity. This frame expresses that one predicate is the same as another
 * in some relevant dimension or context.</p>
 *
 * <p>In RFT, coordination frames are the most fundamental type of relational
 * frame. They form the basis for categorization, naming, and understanding
 * equivalence classes. When we learn that "dog" and "perro" refer to the same
 * animal, we are establishing a coordination frame.</p>
 *
 * <p>The relationship expressed is: "{@code a} is the same as {@code b}"</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Predicate morning_star = ...;
 * Predicate evening_star = ...;
 * CoordinationFrame frame = new CoordinationFrame(morning_star, evening_star);
 * // Represents: "morning_star is the same as evening_star" (both are Venus)
 * }</pre>
 *
 * @see Predicate
 * @see ComparativeFrame
 *
 * @author  Michael Murray
 */
public class CoordinationFrame {
	private Predicate a, b;

	/**
	 * Constructs a coordination frame expressing that predicate {@code a}
	 * is the same as (or equivalent to) predicate {@code b}.
	 *
	 * @param a the first predicate in the equivalence relation
	 * @param b the second predicate in the equivalence relation
	 */
	public CoordinationFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns a string representation of this coordination frame.
	 *
	 * @return a string in the format "{@code a} is the same as {@code b}"
	 */
	public String toString() { return a + " is the same as " + b; }
}
