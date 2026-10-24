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

package org.almostrealism.persist.assets;

import io.almostrealism.code.Precision;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.io.Bits;
import org.almostrealism.protobuf.Collections;

import java.nio.ByteBuffer;
import java.util.function.Predicate;

/**
 * How the values of a tensor are stored, and how one is decoded.
 *
 * <p>The same descriptions serve both of the formats these assets are read from. A safetensors
 * header names an element type by the constant's name ({@code BF16}, {@code F32}, {@code I64},
 * and so on), and a protobuf {@link Collections.CollectionData} carries each kind of value in a
 * field of its own ({@link #getField()}). A value is always served to a reader as {@code double},
 * so whatever it is stored as, this is what knows how to turn its bytes into one.</p>
 *
 * <p>Integer tensors are read exactly to the framework's host-side memory: every {@code int32} is
 * exact in a {@code double}, and an {@code int64} is exact up to &plusmn;2<sup>53</sup>
 * ({@link #MAX_EXACT_INTEGER}); a value past that is rejected ({@link #requireExactLong}) rather
 * than silently rounded. Once a collection migrates to an FP32 compute device (such as Metal) its
 * values are exact only up to 2<sup>24</sup>, as every value in an FP32 collection is; keeping
 * integer values exact past that on such a device is a separate concern from reading them in.</p>
 *
 * @see CollectionDataReference
 * @see SafetensorsReference
 */
public enum ValueEncoding {
	/** Brain floating point: the upper 16 bits of an IEEE single-precision value. No protobuf payload. */
	BF16(2, 0, false) {
		@Override
		double decode(ByteBuffer buffer, int at) {
			return Float.intBitsToFloat((buffer.getShort(at) & 0xFFFF) << 16);
		}
	},
	/** IEEE half precision. No protobuf payload. */
	F16(2, 0, false) {
		@Override
		double decode(ByteBuffer buffer, int at) {
			return Bits.float16ToFloat(buffer.getShort(at));
		}
	},
	/** IEEE single precision, carried by the {@code data_32} protobuf payload. */
	F32(4, CollectionDataReference.DATA_32_FIELD, false) {
		@Override
		double decode(ByteBuffer buffer, int at) {
			return buffer.getFloat(at);
		}
	},
	/** IEEE double precision, carried by the {@code data} protobuf payload. */
	F64(8, CollectionDataReference.DATA_FIELD, false) {
		@Override
		double decode(ByteBuffer buffer, int at) {
			return buffer.getDouble(at);
		}
	},
	/** Signed 32-bit integer, carried by the {@code data_i32} protobuf payload. Every value is exact in a double. */
	I32(4, CollectionDataReference.DATA_I32_FIELD, true) {
		@Override
		double decode(ByteBuffer buffer, int at) {
			return buffer.getInt(at);
		}
	},
	/**
	 * Signed 64-bit integer, carried by the {@code data_i64} protobuf payload. A value is exact as a
	 * double up to &plusmn;2<sup>53</sup>; one past that is rejected rather than rounded.
	 */
	I64(8, CollectionDataReference.DATA_I64_FIELD, true) {
		@Override
		double decode(ByteBuffer buffer, int at) {
			return requireExactLong(buffer.getLong(at));
		}
	};

	/**
	 * The integer-carrying protobuf payloads and the floating-point ones, in the order a reader
	 * checks them: exactly one of these may be present in a {@link Collections.CollectionData}. The
	 * two that have no protobuf payload ({@link #BF16}, {@link #F16}) are left out.
	 */
	public static final ValueEncoding[] PAYLOADS = { F64, F32, I32, I64 };

	/**
	 * The largest magnitude integer a {@code double} represents exactly (2<sup>53</sup>). An
	 * {@code int64} value outside &plusmn;this cannot be served exactly and is rejected.
	 */
	public static final long MAX_EXACT_INTEGER = 1L << 53;

	/** How many bytes each value occupies. */
	private final int bytes;

	/** The {@link Collections.CollectionData} field number carrying values of this encoding, or 0 for none. */
	private final int field;

	/** Whether values of this encoding are integers. */
	private final boolean integer;

	/**
	 * Creates an encoding.
	 *
	 * @param bytes   how many bytes each value occupies
	 * @param field   the {@link Collections.CollectionData} field number, or 0 if there is none
	 * @param integer whether values of this encoding are integers
	 */
	ValueEncoding(int bytes, int field, boolean integer) {
		this.bytes = bytes;
		this.field = field;
		this.integer = integer;
	}

	/** Returns how many bytes each value occupies. */
	public int getBytes() { return bytes; }

	/**
	 * Returns the {@link Collections.CollectionData} field number carrying values of this encoding.
	 *
	 * @return the field number, or 0 if this encoding has no protobuf payload
	 */
	public int getField() { return field; }

	/** Returns whether values of this encoding are integers. */
	public boolean isInteger() { return integer; }

	/**
	 * Returns the narrowest {@link Precision} that holds every value of this encoding exactly.
	 *
	 * <p>This is the precision memory must have for the values to survive being read into it:
	 * {@link #BF16} needs {@link Precision#FP32} because its exponent range exceeds FP16's, and
	 * both integer encodings need {@link Precision#FP64}, since an FP32 value is exact only up to
	 * 2<sup>24</sup>.</p>
	 *
	 * @return the precision
	 */
	public Precision getPrecision() {
		switch (this) {
			case F16:
				return Precision.FP16;
			case BF16:
			case F32:
				return Precision.FP32;
			default:
				return Precision.FP64;
		}
	}

	/**
	 * Decodes the value stored at a byte position.
	 *
	 * @param buffer the file's bytes, in little-endian order
	 * @param at     the byte position of the value
	 * @return the value as a double
	 * @throws ArithmeticException if the value is an {@code int64} outside &plusmn;2<sup>53</sup>
	 */
	abstract double decode(ByteBuffer buffer, int at);

	/**
	 * Requires an {@code int64} value to be exactly representable as a {@code double}, returning it
	 * as one.
	 *
	 * @param value the value
	 * @return the value as a double
	 * @throws ArithmeticException if the value is outside &plusmn;{@link #MAX_EXACT_INTEGER}
	 */
	static double requireExactLong(long value) {
		if (value < -MAX_EXACT_INTEGER || value > MAX_EXACT_INTEGER) {
			throw new ArithmeticException(value + " cannot be represented exactly as a double; "
					+ "an I64 value must be within ±2^53 (±" + MAX_EXACT_INTEGER + ")");
		}

		return value;
	}

	/**
	 * Converts a value to the integer this encoding stores, requiring it to be a whole number in
	 * range for the encoding's width. This is the egress check made when a collection of doubles is
	 * written out as an integer payload: a value that is not integral, or that would not survive the
	 * round trip, is rejected rather than truncated.
	 *
	 * <p>The values come from collection memory, which may hold them at less than double precision.
	 * Memory narrower than eight bytes per value cannot hold every {@code I32} or {@code I64} value
	 * exactly ({@code Integer.MAX_VALUE} in FP32 memory reads back as 2<sup>31</sup>), so when it
	 * is, the out-of-range message says so: the value was most likely rounded out of range before it
	 * reached this check, and the remedy is wider memory, not a different value.</p>
	 *
	 * @param value       the value
	 * @param index       the value's position, for the failure message
	 * @param memoryBytes the number of bytes per value in the memory the value was read from
	 * @return the value as a {@code long}
	 * @throws IllegalArgumentException if this encoding is not an integer encoding, or the value is
	 *         not a whole number, or it is out of range for this encoding's width
	 */
	long toExactLong(double value, int index, int memoryBytes) {
		if (!integer) {
			throw new IllegalArgumentException(this + " is not an integer encoding");
		} else if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
			throw new IllegalArgumentException("the value " + value + " at index " + index
					+ " is not a whole number and cannot be stored as " + this);
		}

		long bound = this == I32 ? Integer.MAX_VALUE : MAX_EXACT_INTEGER;
		long floor = this == I32 ? Integer.MIN_VALUE : -MAX_EXACT_INTEGER;
		if (value < floor || value > bound) {
			throw new IllegalArgumentException("the value " + value + " at index " + index
					+ " is outside the range of " + this + " (" + floor + " to " + bound + ")"
					+ (memoryBytes < Long.BYTES ? "; the collection holds " + memoryBytes
						+ "-byte values, which cannot represent every " + this + " value exactly, so it"
						+ " may have been rounded out of range when it was stored" : ""));
		}

		return (long) value;
	}

	/**
	 * Returns the floating-point encoding carrying values at the given precision's width, for
	 * reading a protobuf collection written at a {@link io.almostrealism.code.Precision}.
	 *
	 * @param precision the precision
	 * @return {@link #F64} for FP64, {@link #F32} otherwise
	 */
	public static ValueEncoding ofPrecision(Precision precision) {
		return precision == Precision.FP64 ? F64 : F32;
	}

	/**
	 * Returns the encoding of the single payload present in the given collection data, for a
	 * collection that has already been parsed.
	 *
	 * @param data the parsed collection data
	 * @return the encoding of its values, or {@code null} if it carries no payload
	 * @throws IllegalArgumentException if more than one payload is present
	 */
	public static ValueEncoding of(Collections.CollectionData data) {
		return present(encoding -> encoding.count(data) > 0);
	}

	/**
	 * Returns the encoding of the single payload present in the given encoded message, for a
	 * collection located in a file but not yet parsed. The companion to {@link #of(Collections.CollectionData)}
	 * for the unparsed form a {@link CollectionDataReference} reads: a payload is present when the
	 * message carries its {@link #getField() field}.
	 *
	 * @param message the encoded collection data
	 * @return the encoding of its values, or {@code null} if it carries no payload
	 * @throws IllegalArgumentException if more than one payload is present
	 */
	public static ValueEncoding of(EncodedMessage message) {
		return present(encoding -> message.has(encoding.getField()));
	}

	/**
	 * Returns the single payload encoding for which {@code present} holds, failing when more than
	 * one does: a collection carries the values of exactly one encoding. The two representations a
	 * collection is read from ({@link Collections.CollectionData}, {@link EncodedMessage}) differ
	 * only in how a payload's presence is tested, so each supplies that test and this makes the
	 * choice.
	 *
	 * @param present whether a candidate encoding's payload is present
	 * @return the one present encoding, or {@code null} if none is
	 * @throws IllegalArgumentException if more than one payload is present
	 */
	private static ValueEncoding present(Predicate<ValueEncoding> present) {
		ValueEncoding found = null;
		for (ValueEncoding candidate : PAYLOADS) {
			if (present.test(candidate)) {
				found = requireSingle(found, candidate);
			}
		}

		return found;
	}

	/**
	 * Returns {@code candidate} when no payload has been seen yet, failing when one already has: a
	 * collection carries the values of exactly one encoding.
	 *
	 * @param found     the payload seen so far, or {@code null}
	 * @param candidate another payload that is present
	 * @return {@code candidate}
	 * @throws IllegalArgumentException if {@code found} is not {@code null}
	 */
	private static ValueEncoding requireSingle(ValueEncoding found, ValueEncoding candidate) {
		if (found != null) {
			throw new IllegalArgumentException("Collection data carries both a " + found
					+ " and a " + candidate + " payload, but exactly one is expected");
		}

		return candidate;
	}

	/**
	 * Reads the value at the given index of a parsed collection, for the one payload this encoding
	 * names. The companion to {@link #decode(ByteBuffer, int)} for a collection whose values are
	 * already parsed onto the Java heap rather than read from a file mapping.
	 *
	 * @param data  the parsed collection data
	 * @param index the value's position
	 * @return the value as a double
	 * @throws ArithmeticException if the value is an {@code int64} outside &plusmn;2<sup>53</sup>
	 */
	double valueAt(Collections.CollectionData data, int index) {
		switch (this) {
			case F64: return data.getData(index);
			case F32: return data.getData32(index);
			case I32: return data.getDataI32(index);
			case I64: return requireExactLong(data.getDataI64(index));
			default: throw new IllegalArgumentException(this + " has no protobuf payload");
		}
	}

	/**
	 * Returns the number of values of the one payload this encoding names in the given parsed
	 * collection.
	 *
	 * @param data the parsed collection data
	 * @return the number of values
	 */
	int count(Collections.CollectionData data) {
		switch (this) {
			case F64: return data.getDataCount();
			case F32: return data.getData32Count();
			case I32: return data.getDataI32Count();
			case I64: return data.getDataI64Count();
			default: throw new IllegalArgumentException(this + " has no protobuf payload");
		}
	}

	/**
	 * Writes a collection's values into the given builder as this encoding's payload. A floating
	 * point encoding streams the values straight through; an integer encoding requires each value
	 * to be a whole number in range ({@link #toExactLong}) before storing it.
	 *
	 * @param c    the collection to write
	 * @param data the builder to write into
	 * @throws IllegalArgumentException if this encoding has no protobuf payload, or an integer
	 *         encoding is asked to store a value that is not a whole number in range
	 */
	void write(PackedCollection c, Collections.CollectionData.Builder data) {
		if (this == F64) {
			c.doubleStream().forEach(data::addData);
		} else if (this == F32) {
			c.doubleStream().forEach(d -> data.addData32((float) d));
		} else if (integer) {
			double[] values = c.doubleStream().toArray();
			int memoryBytes = values.length == 0 ? Long.BYTES : c.getMem().getProvider().getNumberSize();
			for (int i = 0; i < values.length; i++) {
				long value = toExactLong(values[i], i, memoryBytes);
				if (this == I32) {
					data.addDataI32((int) value);
				} else {
					data.addDataI64(value);
				}
			}
		} else {
			throw new IllegalArgumentException(this + " has no protobuf payload to write");
		}
	}

	/**
	 * Returns how many values of this encoding occupy the given number of bytes, failing when the
	 * byte count is not a whole number of values.
	 *
	 * @param operation a description of what is being read, for the failure message
	 * @param bytes     the number of bytes
	 * @return the number of values
	 * @throws IllegalArgumentException if {@code bytes} is not a multiple of this encoding's width
	 */
	int valuesIn(String operation, int bytes) {
		if (bytes % this.bytes != 0) {
			throw new IllegalArgumentException(operation + " occupies " + bytes
					+ " bytes, which is not a whole number of " + this + " values of "
					+ this.bytes + " bytes each");
		}

		return bytes / this.bytes;
	}
}
