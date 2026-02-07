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

package org.almostrealism.audio.filter;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;

/**
 * Data interface for biquad filter state.
 * Stores filter coefficients (b0, b1, b2, a1, a2) and delay line state (x1, x2, y1, y2).
 * <p>
 * Memory layout (each slot is 1 element):
 * <ul>
 *   <li>Slot 0: b0 - feedforward coefficient for x[n]</li>
 *   <li>Slot 1: b1 - feedforward coefficient for x[n-1]</li>
 *   <li>Slot 2: b2 - feedforward coefficient for x[n-2]</li>
 *   <li>Slot 3: a1 - feedback coefficient for y[n-1]</li>
 *   <li>Slot 4: a2 - feedback coefficient for y[n-2]</li>
 *   <li>Slot 5: x1 - previous input sample x[n-1]</li>
 *   <li>Slot 6: x2 - input sample x[n-2]</li>
 *   <li>Slot 7: y1 - previous output sample y[n-1]</li>
 *   <li>Slot 8: y2 - output sample y[n-2]</li>
 * </ul>
 *
 * @see BiquadFilterCell
 */
public interface BiquadFilterData extends CodeFeatures {
	int SIZE = 9;

	PackedCollection get(int index);

	default PackedCollection b0() { return get(0); }
	default PackedCollection b1() { return get(1); }
	default PackedCollection b2() { return get(2); }
	default PackedCollection a1() { return get(3); }
	default PackedCollection a2() { return get(4); }
	default PackedCollection x1() { return get(5); }
	default PackedCollection x2() { return get(6); }
	default PackedCollection y1() { return get(7); }
	default PackedCollection y2() { return get(8); }

	default Producer<PackedCollection> getB0() { return p(b0().range(shape(1))); }
	default Producer<PackedCollection> getB1() { return p(b1().range(shape(1))); }
	default Producer<PackedCollection> getB2() { return p(b2().range(shape(1))); }
	default Producer<PackedCollection> getA1() { return p(a1().range(shape(1))); }
	default Producer<PackedCollection> getA2() { return p(a2().range(shape(1))); }
	default Producer<PackedCollection> getX1() { return p(x1().range(shape(1))); }
	default Producer<PackedCollection> getX2() { return p(x2().range(shape(1))); }
	default Producer<PackedCollection> getY1() { return p(y1().range(shape(1))); }
	default Producer<PackedCollection> getY2() { return p(y2().range(shape(1))); }

	default void setB0(double v) { b0().setMem(0, v); }
	default void setB1(double v) { b1().setMem(0, v); }
	default void setB2(double v) { b2().setMem(0, v); }
	default void setA1(double v) { a1().setMem(0, v); }
	default void setA2(double v) { a2().setMem(0, v); }
	default void setX1(double v) { x1().setMem(0, v); }
	default void setX2(double v) { x2().setMem(0, v); }
	default void setY1(double v) { y1().setMem(0, v); }
	default void setY2(double v) { y2().setMem(0, v); }

	/**
	 * Sets all filter coefficients at once.
	 */
	default void setCoefficients(double b0, double b1, double b2, double a1, double a2) {
		setB0(b0);
		setB1(b1);
		setB2(b2);
		setA1(a1);
		setA2(a2);
	}

	/**
	 * Resets the filter state (delay lines) to zero.
	 */
	default void resetState() {
		setX1(0);
		setX2(0);
		setY1(0);
		setY2(0);
	}
}
