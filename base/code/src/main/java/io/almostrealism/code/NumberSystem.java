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

import org.almostrealism.io.Bits;

import java.nio.ByteBuffer;

/**
 * The computer number systems the framework can store or compute with, in one place.
 *
 * <p>Each system is both a {@link Precision} (how precise its values are, when it has floating-point
 * values at all) and a {@link BinaryLayout} (how one value is laid out in storage and decoded from
 * its bytes). The two interfaces split the two concerns so that a caller can accept {@link Precision}
 * when it only reasons about epsilon and generated literals, or {@link BinaryLayout} when it only
 * reasons about byte width and bit decoding, without either having to know about the other.</p>
 *
 * <p>The three floating-point systems a compute backend runs at are reached by the historical
 * {@link Precision} constants: {@link Precision#FP16} is {@link #BF16}, {@link Precision#FP32} is
 * {@link #F32} and {@link Precision#FP64} is {@link #F64}. Those constants keep the exact precision
 * behaviour (byte width, machine epsilon, minimum value, type name, generated literal formatting)
 * they had when {@code Precision} was an enum, so code that reads or writes generated source is
 * unaffected by the unification.</p>
 *
 * <p>Integer systems carry whole numbers only; asking one for a floating-point property
 * ({@link #epsilon}, {@link #minValue}, {@link #typeName}, the {@code stringFor*} formatters) is a
 * programming error and throws, which {@link #isFloatingPoint()} lets a caller avoid. Integer values
 * are read exactly to a host {@code double}: every {@code int32} is exact, and an {@code int64} is
 * exact up to &plusmn;2<sup>53</sup> ({@link #MAX_EXACT_INTEGER}); a value past that is rejected
 * ({@link #requireExactLong}) rather than silently rounded. Once a collection migrates to an FP32
 * compute device its values are exact only up to 2<sup>24</sup>, as every value in an FP32 collection
 * is; keeping integer values exact past that on such a device is a separate concern from reading them
 * in.</p>
 *
 * @see Precision
 * @see BinaryLayout
 */
public enum NumberSystem implements Precision, BinaryLayout {
	/**
	 * Brain floating point: the upper 16 bits of an IEEE single-precision value. This is the system
	 * the historical {@link Precision#FP16} names, and it keeps that constant's precision behaviour —
	 * including its type name {@code "bfloat"} and its machine epsilon — unchanged.
	 */
	BF16(2, true, true, "bfloat", "FP16", -3.3895313e+38, 0.0009765625, 1e-4) {
		@Override
		public double decode(ByteBuffer buffer, int at) {
			return Float.intBitsToFloat((buffer.getShort(at) & 0xFFFF) << 16);
		}
	},
	/** IEEE half precision. */
	F16(2, true, true, "half", null, -65504.0, 0.0009765625, 1e-4) {
		@Override
		public double decode(ByteBuffer buffer, int at) {
			return Bits.float16ToFloat(buffer.getShort(at));
		}
	},
	/** IEEE single precision, the system the historical {@link Precision#FP32} names. */
	F32(4, true, true, "float", "FP32", -3.4028234663852886e+38, 1.1920928955078125e-7, 1e-5) {
		@Override
		public double decode(ByteBuffer buffer, int at) {
			return buffer.getFloat(at);
		}
	},
	/** IEEE double precision, the system the historical {@link Precision#FP64} names. */
	F64(8, true, true, "double", "FP64", -1.7976931348623157e+308, 2.220446049250313e-16, 1e-7) {
		@Override
		public double decode(ByteBuffer buffer, int at) {
			return buffer.getDouble(at);
		}
	},
	/** Signed 32-bit integer. Every value is exact in a {@code double}. */
	I32(4, false, true, null, null, 0.0, 0.0, 0.0) {
		@Override
		public double decode(ByteBuffer buffer, int at) {
			return buffer.getInt(at);
		}
	},
	/**
	 * Signed 64-bit integer. A value is exact as a {@code double} up to &plusmn;2<sup>53</sup>; one
	 * past that is rejected rather than rounded.
	 */
	I64(8, false, true, null, null, 0.0, 0.0, 0.0) {
		@Override
		public double decode(ByteBuffer buffer, int at) {
			return requireExactLong(buffer.getLong(at));
		}
	};

	/**
	 * The largest magnitude integer a {@code double} represents exactly (2<sup>53</sup>). An
	 * {@code int64} value outside &plusmn;this cannot be served exactly and is rejected.
	 */
	public static final long MAX_EXACT_INTEGER = 1L << 53;

	/** How many bytes one value occupies in storage. */
	private final int bytes;

	/** Whether the system has floating-point values. */
	private final boolean floatingPoint;

	/** Whether the system can represent negative values. */
	private final boolean signed;

	/** The type name used in generated code, or {@code null} for an integer system. */
	private final String typeName;

	/**
	 * The historical {@link Precision} name this system is displayed by when it is one of the three
	 * compute precisions ({@code "FP16"}, {@code "FP32"}, {@code "FP64"}), or {@code null} when the
	 * system has no historical precision name and is displayed by its {@link #name()}.
	 */
	private final String precisionName;

	/** The most negative finite floating-point value, meaningful only for a floating-point system. */
	private final double minValue;

	/** The strict machine epsilon, meaningful only for a floating-point system. */
	private final double strictEpsilon;

	/** The practical machine epsilon, meaningful only for a floating-point system. */
	private final double epsilon;

	/**
	 * Creates a number system.
	 *
	 * @param bytes         how many bytes one value occupies
	 * @param floatingPoint whether the system has floating-point values
	 * @param signed        whether the system can represent negative values
	 * @param typeName      the type name used in generated code, or {@code null} for an integer system
	 * @param precisionName the historical {@link Precision} display name, or {@code null} if none
	 * @param minValue      the most negative finite floating-point value
	 * @param strictEpsilon the strict machine epsilon
	 * @param epsilon       the practical machine epsilon
	 */
	NumberSystem(int bytes, boolean floatingPoint, boolean signed, String typeName,
				 String precisionName, double minValue, double strictEpsilon, double epsilon) {
		this.bytes = bytes;
		this.floatingPoint = floatingPoint;
		this.signed = signed;
		this.typeName = typeName;
		this.precisionName = precisionName;
		this.minValue = minValue;
		this.strictEpsilon = strictEpsilon;
		this.epsilon = epsilon;
	}

	/**
	 * Returns how this system is displayed in log and error messages: its historical
	 * {@link Precision} name for the three compute precisions ({@code "FP32"}, {@code "FP64"},
	 * {@code "FP16"}), so that output referring to a precision reads as it always has, and its
	 * {@link #name()} otherwise. This is distinct from {@link #name()}, which is the storage
	 * identity a safetensors {@code dtype} resolves through and so must remain {@code "F32"},
	 * {@code "I32"}, and so on.
	 *
	 * @return the display name
	 */
	@Override
	public String toString() {
		return precisionName == null ? name() : precisionName;
	}

	@Override
	public int bytes() { return bytes; }

	@Override
	public boolean isInteger() { return !floatingPoint; }

	@Override
	public boolean isSigned() { return signed; }

	@Override
	public boolean isFloatingPoint() { return floatingPoint; }

	/**
	 * Requires this system to have floating-point values, so a floating-point property is only read
	 * when there is one to read.
	 *
	 * @param property the property being read, for the failure message
	 * @throws UnsupportedOperationException if this is an integer system
	 */
	private void requireFloatingPoint(String property) {
		if (!floatingPoint) {
			throw new UnsupportedOperationException(this + " is an integer system and has no " + property);
		}
	}

	@Override
	public double minValue() {
		requireFloatingPoint("minimum value");
		return minValue;
	}

	@Override
	public double epsilon(boolean strict) {
		requireFloatingPoint("epsilon");
		return strict ? strictEpsilon : epsilon;
	}

	@Override
	public String typeName() {
		requireFloatingPoint("type name");
		return typeName;
	}

	@Override
	public String stringForInt(int i) {
		requireFloatingPoint("integer literal");
		if (bytes == 2 && (i < -32768 || i > 32767)) {
			throw new UnsupportedOperationException();
		}

		return String.valueOf(i);
	}

	@Override
	public String stringForLong(long l) {
		requireFloatingPoint("long literal");
		if (bytes >= 8) {
			return String.valueOf(l);
		} else if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException(String.valueOf(l));
		}

		return stringForInt(Math.toIntExact(l));
	}

	@Override
	public String rawStringForDouble(double d) {
		requireFloatingPoint("double literal");
		if (bytes < 8) {
			Float f = (float) d;
			if (f.isInfinite()) {
				return String.valueOf(f > 0 ? Float.MAX_VALUE : -Float.MAX_VALUE);
			} else if (f.isNaN()) {
				return "0.0";
			}

			return String.valueOf((float) d);
		} else {
			Double v = d;
			if (v.isInfinite()) {
				return String.valueOf(v > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE);
			} else if (v.isNaN()) {
				return "0.0";
			}

			return String.valueOf(d);
		}
	}

	@Override
	public String stringForDouble(double d) {
		String raw = rawStringForDouble(d);
		return bytes >= 8 ? raw : raw + "f";
	}

	/**
	 * Returns the narrowest floating-point {@link Precision} that holds every value of this system
	 * exactly, which is the precision a reader's memory must have for the values to survive being read
	 * into it: {@link #BF16} needs {@link Precision#FP32} because its exponent range exceeds
	 * {@link Precision#FP16}'s, and both integer systems need {@link Precision#FP64}, since an FP32
	 * value is exact only up to 2<sup>24</sup>.
	 *
	 * @return the precision
	 */
	public Precision storagePrecision() {
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
	 * Converts a value to the integer this system stores, requiring it to be a whole number in range
	 * for the system's width. This is the egress check made when a collection of doubles is written
	 * out as an integer payload: a value that is not integral, or that would not survive the round
	 * trip, is rejected rather than truncated.
	 *
	 * <p>The values come from collection memory, which may hold them at less than double precision.
	 * Memory narrower than eight bytes per value cannot hold every {@code I32} or {@code I64} value
	 * exactly ({@code Integer.MAX_VALUE} in FP32 memory reads back as 2<sup>31</sup>), so when it is,
	 * the out-of-range message says so: the value was most likely rounded out of range before it
	 * reached this check, and the remedy is wider memory, not a different value.</p>
	 *
	 * @param value       the value
	 * @param index       the value's position, for the failure message
	 * @param memoryBytes the number of bytes per value in the memory the value was read from
	 * @return the value as a {@code long}
	 * @throws IllegalArgumentException if this is not an integer system, or the value is not a whole
	 *         number, or it is out of range for this system's width
	 */
	public long toExactLong(double value, int index, int memoryBytes) {
		if (floatingPoint) {
			throw new IllegalArgumentException(this + " is not an integer system");
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
	 * Returns how many values of this system occupy the given number of bytes, failing when the byte
	 * count is not a whole number of values.
	 *
	 * @param operation a description of what is being read, for the failure message
	 * @param bytes     the number of bytes
	 * @return the number of values
	 * @throws IllegalArgumentException if {@code bytes} is not a multiple of this system's width
	 */
	public int valuesIn(String operation, int bytes) {
		if (bytes % this.bytes != 0) {
			throw new IllegalArgumentException(operation + " occupies " + bytes
					+ " bytes, which is not a whole number of " + this + " values of "
					+ this.bytes + " bytes each");
		}

		return bytes / this.bytes;
	}

	/**
	 * Requires an {@code int64} value to be exactly representable as a {@code double}, returning it as
	 * one.
	 *
	 * @param value the value
	 * @return the value as a double
	 * @throws ArithmeticException if the value is outside &plusmn;{@link #MAX_EXACT_INTEGER}
	 */
	public static double requireExactLong(long value) {
		if (value < -MAX_EXACT_INTEGER || value > MAX_EXACT_INTEGER) {
			throw new ArithmeticException(value + " cannot be represented exactly as a double; "
					+ "an I64 value must be within ±2^53 (±" + MAX_EXACT_INTEGER + ")");
		}

		return value;
	}
}
