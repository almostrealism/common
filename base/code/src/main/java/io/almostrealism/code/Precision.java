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
 * Enumerates the supported floating-point precisions for computation backends.
 *
 * <p>The precision determines the number of bytes per element, the minimum representable
 * value, machine epsilon, the type name used in generated code (e.g., {@code "float"} for FP32),
 * and the formatting of numeric literals in generated expressions.</p>
 *
 * @see MemoryProvider
 * @see io.almostrealism.lang.LanguageOperations
 */
public enum Precision {
	/** 16-bit half-precision floating point (bfloat). */
	FP16,
	/** 32-bit single-precision floating point. */
	FP32,
	/** 64-bit double-precision floating point. */
	FP64;

	/**
	 * Returns the number of bytes required to store one element at this precision.
	 *
	 * @return the byte count per element (2, 4, or 8)
	 */
	public int bytes() {
		switch (this) {
			case FP16:
				return 2;
			case FP32:
				return 4;
			case FP64:
				return 8;
			default:
				throw new RuntimeException("Unknown precision");
		}
	}

	/**
	 * Returns the most negative finite value representable at this precision.
	 *
	 * @return the minimum (most negative) finite floating-point value
	 */
	public double minValue() {
		switch (this) {
			case FP16:
				return -3.3895313e+38;
			case FP32:
				return -3.4028234663852886e+38;
			case FP64:
				return -1.7976931348623157e+308;
			default:
				throw new RuntimeException("Unknown precision");
		}
	}

	/**
	 * Returns the approximate machine epsilon for this precision (non-strict variant).
	 *
	 * @return the machine epsilon
	 */
	public double epsilon() {
		return epsilon(false);
	}

	/**
	 * Returns the machine epsilon for this precision.
	 *
	 * @param strict if {@code true}, returns the exact theoretical epsilon; if {@code false},
	 *               returns a slightly larger practical value for numerical comparisons
	 * @return the machine epsilon
	 */
	public double epsilon(boolean strict) {
		switch (this) {
			case FP16:
				return strict ? 0.0009765625 : 1e-4;
			case FP32:
				return strict ? 1.1920928955078125e-7 : 1e-5;
			case FP64:
				return strict ? 2.220446049250313e-16 : 1e-7;
			default:
				throw new RuntimeException("Unknown precision");
		}
	}

	/**
	 * Returns the type name used in generated code for this precision.
	 *
	 * @return the type name (e.g., {@code "float"} for FP32, {@code "double"} for FP64)
	 */
	public String typeName() {
		switch (this) {
			case FP16:
				return "bfloat";
			case FP32:
				return "float";
			case FP64:
				return "double";
			default:
				return "float";
		}
	}

	/**
	 * Returns the generated code literal for the given integer value at this precision.
	 *
	 * @param i the integer value to format
	 * @return the literal string
	 * @throws UnsupportedOperationException if the value is out of range for this precision
	 */
	public String stringForInt(int i) {
		if (this == Precision.FP16 && (i < -32768 || i > 32767)) {
			throw new UnsupportedOperationException();
		}

		return String.valueOf(i);
	}

	/**
	 * Returns the generated code literal for the given long value at this precision.
	 *
	 * @param l the long value to format
	 * @return the literal string
	 * @throws UnsupportedOperationException if the value is out of range for this precision
	 */
	public String stringForLong(long l) {
		if (this == Precision.FP64) {
			return String.valueOf(l);
		} else if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException(String.valueOf(l));
		}

		return stringForInt(Math.toIntExact(l));
	}

	/**
	 * Returns the generated code literal for the given double value at this precision.
	 *
	 * @param d the double value to format
	 * @return the literal string, appropriately cast or formatted for the target precision
	 */
	public String stringForDouble(double d) {
		boolean enableCast = false;

		String raw = rawStringForDouble(d);

		if (enableCast && this == Precision.FP32) {
			return "((float) " + raw + ")";
		} else {
			return raw;
		}
	}

	/**
	 * Returns the raw string representation of the given double value at this precision,
	 * without any cast wrapper.
	 *
	 * <p>For non-FP64 precisions, the value is cast to {@code float} first. Infinite values
	 * are replaced with the maximum finite value; NaN is replaced with {@code "0.0"}.
	 *
	 * @param d the double value to format
	 * @return the raw literal string
	 */
	public String rawStringForDouble(double d) {
		if (this != Precision.FP64) {
			Float f = (float) d;
			if (f.isInfinite()) {
				return String.valueOf(f > 0 ? Float.MAX_VALUE : Float.MIN_VALUE);
			} else if (f.isNaN()) {
				return "0.0";
			}

			return String.valueOf((float) d);
		} else {
			Double v = d;
			if (v.isInfinite()) {
				return String.valueOf(v > 0 ? Double.MAX_VALUE : Double.MIN_VALUE);
			} else if (v.isNaN()) {
				return "0.0";
			}

			return String.valueOf(d);
		}
	}
}
