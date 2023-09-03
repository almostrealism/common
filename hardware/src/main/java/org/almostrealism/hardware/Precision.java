/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware;

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
}
