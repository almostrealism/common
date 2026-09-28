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

package org.almostrealism.hardware.cuda;

import io.almostrealism.code.Precision;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.io.PrintWriter;

import java.util.List;

/**
 * Writes scopes as CUDA C++ source.
 *
 * <p>The top-level scope is emitted as an {@code extern "C" __global__} kernel, so that its
 * name is not mangled and the compiled module exposes it under the scope name. Other
 * scopes become {@code __device__} functions.</p>
 *
 * <p>A CUDA grid is a whole number of blocks, so the last block generally extends past the
 * requested number of work items. Every kernel therefore begins by computing its
 * {@code global_id} and returning when it falls outside
 * {@code [global_offset, global_offset + global_count)}.</p>
 */
public class CudaPrintWriter extends CPrintWriter {
	/**
	 * Creates a writer for a program whose kernel is named {@code topLevelMethodName}.
	 *
	 * @param p                  the destination for the source
	 * @param topLevelMethodName the kernel name
	 * @param precision          the floating-point precision of generated code
	 */
	public CudaPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision) {
		super(p, topLevelMethodName, precision, false);
		language = new CudaLanguageOperations(precision);
		setExternalScopePrefix("extern \"C\" __global__ void");
		setInternalScopePrefix("__device__ void");
	}

	@Override
	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		println("long global_id = (long) blockIdx.x * blockDim.x + threadIdx.x + global_offset;");
		println("if (global_id >= global_offset + global_count) return;");
		super.renderArgumentReads(arguments);
	}
}
