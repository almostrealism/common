/*
 * Copyright 2024 Michael Murray
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

/**
 * How precise a number system's values are: the number of bytes per element, the minimum
 * representable value, machine epsilon, the type name used in generated code (e.g. {@code "float"}
 * for FP32), and the formatting of numeric literals in generated expressions.
 *
 * <p>This is one half of a number system — the computational half. The other half, how a value is
 * laid out in storage, is {@link BinaryLayout}. {@link NumberSystem} is the enumeration of the
 * systems the framework supports, and implements both halves; most code accepts a {@code Precision}
 * because it reasons only about how precise a value is, not about its byte layout.</p>
 *
 * <p>The three floating-point precisions a compute backend runs at keep their historical names as
 * the constants {@link #FP16}, {@link #FP32} and {@link #FP64}, each of which is the corresponding
 * {@link NumberSystem}. A system that is not floating-point ({@link NumberSystem#I32},
 * {@link NumberSystem#I64}) is still a {@code Precision}, but has no precision properties; use
 * {@link #isFloatingPoint()} before reading any of them.</p>
 *
 * @see NumberSystem
 * @see BinaryLayout
 * @see MemoryProvider
 * @see io.almostrealism.lang.LanguageOperations
 */
public interface Precision {
	/**
	 * 16-bit half-precision floating point (bfloat). The historical name for {@link NumberSystem#BF16},
	 * whose precision behaviour this preserves unchanged.
	 */
	Precision FP16 = NumberSystem.BF16;

	/** 32-bit single-precision floating point, i.e. {@link NumberSystem#F32}. */
	Precision FP32 = NumberSystem.F32;

	/** 64-bit double-precision floating point, i.e. {@link NumberSystem#F64}. */
	Precision FP64 = NumberSystem.F64;

	/**
	 * Returns the storage identity of this precision's number system, such as {@code "F32"}. For a
	 * {@link NumberSystem} this is {@link Enum#name()}, the name a safetensors {@code dtype} resolves
	 * through, so it is {@code "F32"}/{@code "F64"}/{@code "BF16"} rather than the historical
	 * {@code "FP32"}/{@code "FP64"}/{@code "FP16"}. The historical precision name, as it appears in
	 * log and error messages, is the value's {@link Object#toString() string form} — see
	 * {@link #valueOf(String)}, which is keyed on those historical names.
	 *
	 * @return the number system name
	 */
	String name();

	/**
	 * Returns whether this precision has floating-point values at all. An integer system is a
	 * {@code Precision}, but its floating-point properties ({@link #minValue()}, {@link #epsilon()},
	 * {@link #typeName()}, the {@code stringFor*} formatters) cannot be read.
	 *
	 * @return {@code true} if floating-point values are representable
	 */
	boolean isFloatingPoint();

	/**
	 * Returns the number of bytes required to store one element at this precision.
	 *
	 * @return the byte count per element (2, 4, or 8)
	 */
	int bytes();

	/**
	 * Returns the most negative finite value representable at this precision.
	 *
	 * @return the minimum (most negative) finite floating-point value
	 * @throws UnsupportedOperationException if this precision has no floating-point values
	 */
	double minValue();

	/**
	 * Returns the approximate machine epsilon for this precision (non-strict variant).
	 *
	 * @return the machine epsilon
	 * @throws UnsupportedOperationException if this precision has no floating-point values
	 */
	default double epsilon() { return epsilon(false); }

	/**
	 * Returns the machine epsilon for this precision.
	 *
	 * @param strict if {@code true}, returns the exact theoretical epsilon; if {@code false},
	 *               returns a slightly larger practical value for numerical comparisons
	 * @return the machine epsilon
	 * @throws UnsupportedOperationException if this precision has no floating-point values
	 */
	double epsilon(boolean strict);

	/**
	 * Returns the type name used in generated code for this precision.
	 *
	 * @return the type name (e.g., {@code "float"} for FP32, {@code "double"} for FP64)
	 * @throws UnsupportedOperationException if this precision has no floating-point values
	 */
	String typeName();

	/**
	 * Returns the generated code literal for the given integer value at this precision.
	 *
	 * @param i the integer value to format
	 * @return the literal string
	 * @throws UnsupportedOperationException if the value is out of range for this precision, or this
	 *         precision has no floating-point values
	 */
	String stringForInt(int i);

	/**
	 * Returns the generated code literal for the given long value at this precision.
	 *
	 * @param l the long value to format
	 * @return the literal string
	 * @throws UnsupportedOperationException if the value is out of range for this precision, or this
	 *         precision has no floating-point values
	 */
	String stringForLong(long l);

	/**
	 * Returns the generated code literal for the given double value at this precision.
	 *
	 * <p>Below {@link #FP64} the literal carries an {@code f} suffix, making it a single
	 * precision literal in every target language. Without it the literal has no type of its
	 * own and the target compiler reads it as a double, which is not merely wasteful: an
	 * overload taking the value alongside a single precision operand then matches both the
	 * single and the double precision form through one implicit conversion each, and the
	 * call is ambiguous. Whether that happens is a property of the device rather than of
	 * this code — a target without double support silently narrows the untyped literal
	 * instead — so the suffix is what keeps generated source portable between them.</p>
	 *
	 * @param d the double value to format
	 * @return the literal string, formatted for the target precision
	 * @throws UnsupportedOperationException if this precision has no floating-point values
	 */
	String stringForDouble(double d);

	/**
	 * Returns the raw string representation of the given double value at this precision,
	 * without any cast wrapper.
	 *
	 * <p>For non-FP64 precisions, the value is cast to {@code float} first. An infinite value
	 * is replaced with the furthest finite value of the same sign, which is also what a finite
	 * value too large for the target precision saturates to once narrowed; NaN is replaced with
	 * {@code "0.0"}. Note that {@link Float#MIN_VALUE} and {@link Double#MIN_VALUE} are the
	 * smallest positive values rather than the most negative ones, so the negative case is the
	 * negation of the maximum.</p>
	 *
	 * @param d the double value to format
	 * @return the raw literal string
	 * @throws UnsupportedOperationException if this precision has no floating-point values
	 */
	String rawStringForDouble(double d);

	/**
	 * Returns the floating-point precisions, in order of increasing width. These are the precisions a
	 * compute backend runs at, the historical values of this type when it was an enum.
	 *
	 * @return the floating-point precisions
	 */
	static Precision[] values() {
		return new Precision[] { FP16, FP32, FP64 };
	}

	/**
	 * Returns the floating-point precision of the given name, the inverse of {@link Object#toString()}
	 * over the historical precision names {@code FP16}, {@code FP32} and {@code FP64}.
	 *
	 * @param name the precision's name
	 * @return the precision
	 * @throws IllegalArgumentException if no floating-point precision has the given name
	 */
	static Precision valueOf(String name) {
		switch (name) {
			case "FP16":
				return FP16;
			case "FP32":
				return FP32;
			case "FP64":
				return FP64;
			default:
				throw new IllegalArgumentException(name + " is not a floating-point precision");
		}
	}

	/**
	 * Returns the precision whose elements occupy the given number of bytes, the inverse of
	 * {@link #bytes()} over the floating-point precisions.
	 *
	 * @param bytes the byte count per element (2, 4, or 8)
	 * @return the matching precision
	 * @throws IllegalArgumentException if no precision uses the given byte count
	 */
	static Precision ofBytes(int bytes) {
		switch (bytes) {
			case 2:
				return FP16;
			case 4:
				return FP32;
			case 8:
				return FP64;
			default:
				throw new IllegalArgumentException(bytes + " bytes does not correspond to a supported precision");
		}
	}
}
