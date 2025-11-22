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
 * Represents a spatial relational frame in Relational Frame Theory (RFT).
 *
 * <p>A spatial frame captures relationships between predicates based on physical
 * or conceptual distance, proximity, or location. This frame expresses that one
 * predicate is closer (in some spatial dimension) than another.</p>
 *
 * <p>In RFT, spatial frames are one of the fundamental deictic relations that
 * help establish perspective-taking and contextual understanding of spatial
 * relationships between entities.</p>
 *
 * <p>The relationship expressed is: "{@code a} is closer than {@code b}"</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Predicate desk = ...;
 * Predicate window = ...;
 * SpatialFrame frame = new SpatialFrame(desk, window);
 * // Represents: "desk is closer than window" (from a given perspective)
 * }</pre>
 *
 * @see Predicate
 * @see TemporalFrame
 * @see DiecticFrame
 *
 * @author  Michael Murray
 */
public class SpatialFrame {
	private Predicate a, b;

	/**
	 * Constructs a spatial frame expressing that predicate {@code a} is
	 * closer than predicate {@code b}.
	 *
	 * @param a the nearer predicate in the spatial relationship
	 * @param b the farther predicate in the spatial relationship
	 */
	public SpatialFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns a string representation of this spatial frame.
	 *
	 * @return a string in the format "{@code a} is closer than {@code b}"
	 */
	public String toString() { return a + " is closer than " + b; }
}
