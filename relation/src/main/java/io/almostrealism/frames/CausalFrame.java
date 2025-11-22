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
 * Represents a causal relational frame in Relational Frame Theory (RFT).
 *
 * <p>A causal frame captures cause-and-effect relationships between predicates.
 * This frame expresses that one predicate is the cause or reason for another.
 * Causal frames extend beyond simple temporal ordering to indicate that one
 * event or condition directly leads to or produces another.</p>
 *
 * <p>In RFT, causal frames are essential for understanding explanations,
 * predictions, and the logical connections between events. They enable
 * reasoning about why things happen and what consequences may follow.</p>
 *
 * <p>The relationship expressed is: "{@code b} is because of {@code a}"
 * (where {@code a} is the cause and {@code b} is the effect)</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * Predicate rain = ...;
 * Predicate wetGround = ...;
 * CausalFrame frame = new CausalFrame(rain, wetGround);
 * // Represents: "wetGround is because of rain"
 * }</pre>
 *
 * @see Predicate
 * @see TemporalFrame
 *
 * @author  Michael Murray
 */
public class CausalFrame {
	private Predicate a, b;

	/**
	 * Constructs a causal frame expressing that predicate {@code a}
	 * is the cause of predicate {@code b}.
	 *
	 * @param a the cause predicate
	 * @param b the effect predicate (the result of {@code a})
	 */
	public CausalFrame(Predicate a, Predicate b) {
		this.a = a;
		this.b = b;
	}

	/**
	 * Returns a string representation of this causal frame.
	 *
	 * @return a string in the format "{@code b} is because of {@code a}"
	 */
	public String toString() { return b + " is because of " + a; }
}
