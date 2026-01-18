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
 * Represents a deictic relational frame in Relational Frame Theory (RFT).
 *
 * <p>A deictic frame captures relationships that depend on the perspective
 * or point of view of the observer. This frame expresses a direct attribution
 * or identification relationship between predicates, where one predicate is
 * characterized by or identified with another.</p>
 *
 * <p>In RFT, deictic frames are essential for perspective-taking and
 * self-awareness. They include three key perspective relations:</p>
 * <ul>
 *   <li><strong>I-YOU</strong>: interpersonal perspective</li>
 *   <li><strong>HERE-THERE</strong>: spatial perspective</li>
 *   <li><strong>NOW-THEN</strong>: temporal perspective</li>
 * </ul>
 *
 * <p>Deictic frames enable understanding that the same stimulus can be viewed
 * differently depending on who is observing and from where/when they observe.</p>
 *
 * <p>The relationship expressed is: "{@code a} is {@code b}"</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Predicate this_location = ...;
 * Predicate home = ...;
 * DiecticFrame frame = new DiecticFrame(this_location, home);
 * // Represents: "this_location is home" (from the speaker's perspective)
 * }</pre>
 *
 * @see Predicate
 * @see SpatialFrame
 * @see TemporalFrame
 *
 * @author  Michael Murray
 */
public class DiecticFrame {
	private Predicate a, b;

	/**
	 * Constructs a deictic frame expressing that predicate {@code a}
	 * is (or is characterized as) predicate {@code b}.
	 *
	 * @param a the subject predicate
	 * @param b the predicate that characterizes or identifies {@code a}
	 */
	public DiecticFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns a string representation of this deictic frame.
	 *
	 * @return a string in the format "{@code a} is {@code b}"
	 */
	public String toString() { return a + " is " + b; }
}
