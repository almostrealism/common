/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.c;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Method;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class OpenCLPrintWriter extends CPrintWriter {

	public OpenCLPrintWriter(PrintWriter p) {
		super(p);
		setScopePrefix("__kernel void");
	}

	@Override
	public void println(Method method) {
		p.println(renderMethod(method));
	}

	@Override
	protected String annotationForPhysicalScope(PhysicalScope scope) {
		if (scope != null) return scope == PhysicalScope.LOCAL ? "__local" : "__global";
		return null;
	}

	@Override
	protected void renderParameters(List<Expression> parameters, Consumer<String> out) {
		List<ArrayVariable<?>> arguments = parameters.stream()
				.map(exp -> (InstanceReference) exp)
				.map(InstanceReference::getReferent)
				.map(v -> (ArrayVariable<?>) v)
				.collect(Collectors.toList());

		if (!arguments.isEmpty()) {
			renderArguments(arguments, out, false, false, null, "", "");
			out.accept(", ");
			renderArguments(arguments, out, false, false, Integer.class, "", "Offset");
			out.accept(", ");
			renderArguments(arguments, out, false, false, Integer.class, "", "Size");
		}
	}

	@Override
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out) {
		if (!arguments.isEmpty()) {
			renderArguments(arguments, out, true, true, null, "*", "");
			out.accept(", ");
			renderArguments(arguments, out, true, false, Integer.class, "", "Offset");
			out.accept(", ");
			renderArguments(arguments, out, true, false, Integer.class, "", "Size");
		}
	}
}
