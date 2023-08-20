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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Accessibility;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.code.PhysicalScope;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.function.Consumer;

public class MetalPrintWriter extends CPrintWriter {

	public MetalPrintWriter(PrintWriter p) {
		this(p, null);
	}

	public MetalPrintWriter(PrintWriter p, String topLevelMethodName) {
		super(p, topLevelMethodName, false);
		setScopePrefix("[[kernel]] void");
		setEnableArrayVariables(true);
		setEnableArgumentDetailReads(true);
	}

	@Override
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		super.renderArguments(arguments, out, access);

		if (!arguments.isEmpty()) {
			out.accept(", ");
			out.accept("uint global_id [[thread_position_in_grid]], ");
			out.accept("uint global_count [[threads_per_grid]]");
		}
	}

	@Override
	public void println(Method method) {
		p.println(renderMethod(method));
	}

	@Override
	protected String annotationForPhysicalScope(PhysicalScope scope) {
		if (scope != null) return "device";
		return null;
	}

	@Override
	protected String argumentPost(int index) {
		return " [[buffer(" + index + ")]]";
	}
}
