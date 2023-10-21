/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.code;

public enum ComputeRequirement {
	CPU, GPU, FPGA, C, CL, MTL, JNI, EXTERNAL, PROFILING;

	public Precision getMaximumPrecision() {
			switch (this) {
			case CPU:
				return Precision.FP64;
			case GPU:
				return Precision.FP32;
			case FPGA:
				return Precision.FP32;
			case C:
				return Precision.FP64;
			case CL:
				return Precision.FP64;
			case MTL:
				return Precision.FP32;
			case JNI:
				return Precision.FP64;
			case EXTERNAL:
				return Precision.FP64;
			case PROFILING:
				return Precision.FP64;
			default:
				throw new IllegalStateException("Unexpected value: " + this);
		}
	}
}
