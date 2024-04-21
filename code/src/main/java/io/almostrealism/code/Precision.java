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

public enum Precision {
	FP16, FP32, FP64;

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

	public double epsilon() {
		switch (this) {
			case FP16:
				return 0.0009765625;
			case FP32:
				return 1.1920928955078125e-7;
			case FP64:
				return 2.220446049250313e-16;
			default:
				throw new RuntimeException("Unknown precision");
		}
	}

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

	public String stringForInt(int i) {
		if (this == Precision.FP16 && (i < -32768 || i > 32767)) {
			throw new UnsupportedOperationException();
		}

		return String.valueOf(i);
	}

	public String stringForLong(long l) {
		if (this != Precision.FP64) {
			throw new UnsupportedOperationException();
		}

		return String.valueOf(l);
	}

	public String stringForDouble(double d) {
		boolean enableCast = false;

		String raw = rawStringForDouble(d);

		if (enableCast && this == Precision.FP32) {
			return "((float) " + raw + ")";
		} else {
			return raw;
		}
	}

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
