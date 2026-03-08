/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.io;

/**
 * Utility class for bit manipulation operations.
 *
 * <p>Provides methods for packing values into specific bit positions within integers.
 * Useful for creating compact binary representations or bit fields.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Pack a 4-bit value (0-15) at position 0
 * int result = Bits.put(0, 4, 10);  // result = 10 (binary: 1010)
 *
 * // Pack an 8-bit value at position 4
 * result |= Bits.put(4, 8, 255);  // Shifts 255 left by 4 bits
 * }</pre>
 */
public class Bits {
	/**
	 * Places a value at a specific bit position within an integer.
	 *
	 * <p>The value is masked to fit within the specified number of bits
	 * (using modulo), then shifted to the specified position.</p>
	 *
	 * @param position the bit position to place the value (0 = least significant)
	 * @param bits the number of bits allocated for the value
	 * @param value the value to pack (will be masked to fit in 'bits' bits)
	 * @return the packed value at the specified position
	 */
	public static int put(int position, int bits, int value) {
		int max = 1 << bits;
		return (Math.abs(value) % max) << position;
	}
}
