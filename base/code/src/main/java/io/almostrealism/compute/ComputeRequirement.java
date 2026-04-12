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

package io.almostrealism.compute;

import io.almostrealism.code.Precision;
import org.almostrealism.io.SystemUtils;

/**
 * An enumeration of the hardware or language backends that a computation can target.
 *
 * <p>Each constant identifies a specific execution context (CPU, GPU, FPGA) or a
 * target code language (C, OpenCL, Metal, JNI). The {@link #getMaximumPrecision()}
 * method returns the highest floating-point precision supported by that backend.</p>
 */
public enum ComputeRequirement {
	/** Native CPU execution. Supports full FP64 precision. */
	CPU,
	/** GPU execution. Limited to FP32 precision. */
	GPU,
	/** FPGA execution. Limited to FP32 precision. */
	FPGA,
	/** Plain C code generation. Supports FP64. */
	C,
	/** OpenCL kernel execution. FP64 on x86, FP32 on aarch64. */
	CL,
	/** Metal (Apple GPU) kernel execution. Limited to FP32. */
	MTL,
	/** JNI (Java Native Interface) execution. Supports FP64. */
	JNI,
	/** External process execution. Supports FP64. */
	EXTERNAL,
	/** Profiling-only mode (no real computation). Supports FP64. */
	PROFILING;

	/**
	 * Returns the highest {@link Precision} supported by this execution backend.
	 *
	 * @return the maximum floating-point precision for this backend
	 */
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
				return SystemUtils.isAarch64() ? Precision.FP32 : Precision.FP64;
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
