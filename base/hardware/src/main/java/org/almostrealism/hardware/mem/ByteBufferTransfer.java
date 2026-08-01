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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Precision;

import java.nio.ByteBuffer;

/**
 * Moves floating-point values between two {@link ByteBuffer}s whose element
 * {@link Precision}s may differ, converting each value as it crosses.
 *
 * <p>This is the standard way to move ingested data between buffers of
 * potentially different precisions — for example, from an FP32 checkpoint
 * region into an FP64 staging allocation — without materializing a host
 * array or branching on the element type at every site:</p>
 *
 * <pre>{@code
 * new ByteBufferTransfer(source, Precision.FP32, staging, Precision.FP64).copyAll();
 * }</pre>
 *
 * <p>All movement is relative: each copied element advances both buffer
 * positions by one element width, so transfers compose with prior reads and
 * writes on either buffer, and multiple transfers targeting the same
 * destination interleave naturally by alternating {@link #copyNext()}.</p>
 *
 * <p>Each buffer is read and written through its own
 * {@link java.nio.ByteOrder byte order}, so transfers between buffers of
 * different orders convert correctly. When the two precisions and orders
 * match, {@link #copy(int)} and {@link #copyAll()} move bytes in bulk.</p>
 *
 * <p>{@link Precision#FP16} is not supported: it denotes bfloat16 storage,
 * which has no {@link ByteBuffer} accessor and different conversion
 * semantics from IEEE half precision.</p>
 *
 * @see Precision
 * @see DirectMemory#asByteBuffer()
 */
public class ByteBufferTransfer {
	/** The buffer values are read from. */
	private final ByteBuffer source;
	/** The buffer values are written to. */
	private final ByteBuffer destination;
	/** Element precision of the source buffer. */
	private final Precision sourcePrecision;
	/** Element precision of the destination buffer. */
	private final Precision destinationPrecision;

	/**
	 * Creates a transfer between the given buffers.
	 *
	 * @param source               the buffer values are read from, starting at its current position
	 * @param sourcePrecision      the element precision of the source buffer
	 * @param destination          the buffer values are written to, starting at its current position
	 * @param destinationPrecision the element precision of the destination buffer
	 * @throws UnsupportedOperationException if either precision is {@link Precision#FP16}
	 */
	public ByteBufferTransfer(ByteBuffer source, Precision sourcePrecision,
							  ByteBuffer destination, Precision destinationPrecision) {
		if (sourcePrecision == Precision.FP16 || destinationPrecision == Precision.FP16) {
			throw new UnsupportedOperationException("FP16 transfers are not supported");
		}

		this.source = source;
		this.destination = destination;
		this.sourcePrecision = sourcePrecision;
		this.destinationPrecision = destinationPrecision;
	}

	/**
	 * Copies the next value from the source to the destination, advancing
	 * each buffer's position by one element width.
	 */
	public void copyNext() {
		double value = sourcePrecision == Precision.FP32 ?
				source.getFloat() : source.getDouble();

		if (destinationPrecision == Precision.FP32) {
			destination.putFloat((float) value);
		} else {
			destination.putDouble(value);
		}
	}

	/**
	 * Copies the given number of values from the source to the destination,
	 * advancing both buffer positions accordingly. When the two buffers share
	 * a precision and byte order, the copy is a single bulk move.
	 *
	 * @param count the number of elements to copy
	 */
	public void copy(int count) {
		if (sourcePrecision == destinationPrecision &&
				source.order() == destination.order()) {
			int limit = source.limit();
			source.limit(source.position() + count * sourcePrecision.bytes());
			destination.put(source);
			source.limit(limit);
		} else {
			for (int i = 0; i < count; i++) {
				copyNext();
			}
		}
	}

	/**
	 * Copies every remaining value in the source to the destination, leaving
	 * the source with no remaining elements.
	 */
	public void copyAll() {
		copy(source.remaining() / sourcePrecision.bytes());
	}
}
