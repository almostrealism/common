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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CLanguageOperations;

import java.util.List;
import java.util.function.Consumer;

/**
 * Language operations for CUDA C++ kernels compiled with NVRTC.
 *
 * <p>Arguments use the scalar layout of the OpenCL backend rather than the argument buffers
 * Metal uses: a kernel receives every argument pointer, then an {@code int} offset for every
 * argument, then an {@code int} size for every argument. CUDA has no equivalent of Metal's
 * {@code setBytes}, and scalar parameters need no per-dispatch allocation.</p>
 *
 * <p>After the arguments, a kernel receives {@code long global_count} and
 * {@code long global_offset}, from which {@link CudaPrintWriter} derives {@code global_id}.
 * Helper functions receive {@code global_id} and {@code global_count} as parameters, the
 * same way Metal threads them through.</p>
 */
public class CudaLanguageOperations extends CLanguageOperations {
	/**
	 * Creates language operations for the given precision.
	 *
	 * @param precision the floating-point precision of generated code
	 */
	public CudaLanguageOperations(Precision precision) {
		super(precision, false, false);
	}

	@Override
	public boolean isNumericBoolean() {
		return false;
	}

	@Override
	protected void renderParameters(String methodName, List<Expression> parameters, Consumer<String> out) {
		super.renderParameters(methodName, parameters, out);
		out.accept(", global_id, global_count");
	}

	@Override
	public void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, Accessibility access) {
		super.renderParameters(arguments, out, access);

		if (access == Accessibility.EXTERNAL) {
			out.accept(", long global_count, long global_offset");
		} else {
			out.accept(", long global_id, long global_count");
		}
	}
}
