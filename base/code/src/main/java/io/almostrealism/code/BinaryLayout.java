/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.code;

import java.nio.ByteBuffer;

/**
 * How the bits of a single value are laid out in storage, independent of how precise the value is.
 *
 * <p>This is the storage-and-layout half of a number system, kept separate from {@link Precision}
 * (the computational half) so that code which cares only about how many bytes a value occupies, or
 * how to turn those bytes back into a value, need not reason about machine epsilon or the formatting
 * of generated literals, and code which cares only about precision need not reason about byte
 * layout. {@link NumberSystem} implements both halves.</p>
 *
 * @see NumberSystem
 * @see Precision
 */
public interface BinaryLayout {
	/**
	 * Returns the number of bytes one value occupies in storage.
	 *
	 * @return the byte width per value
	 */
	int bytes();

	/**
	 * Returns the number of bits one value occupies in storage.
	 *
	 * @return the bit width per value ({@code 8 * }{@link #bytes()})
	 */
	default int bits() { return bytes() * Byte.SIZE; }

	/**
	 * Returns whether values are stored as integers rather than floating-point.
	 *
	 * @return {@code true} if this is an integer layout
	 */
	boolean isInteger();

	/**
	 * Returns whether the layout can represent negative values.
	 *
	 * @return {@code true} if values are signed
	 */
	boolean isSigned();

	/**
	 * Decodes the value stored at a byte position, returning it as a {@code double}.
	 *
	 * @param buffer the bytes, in little-endian order
	 * @param at     the byte position of the value
	 * @return the value as a double
	 */
	double decode(ByteBuffer buffer, int at);
}
