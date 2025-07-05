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
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CLanguageOperations;

import java.util.List;
import java.util.function.Consumer;

public class MetalLanguageOperations extends CLanguageOperations {
	public static boolean enableThreadScope = false;

	public MetalLanguageOperations(Precision precision) {
		super(precision, false, true);
	}

	@Override
	public String kernelIndex(int index) {
		return "((long)" + super.kernelIndex(index) + ")";
	}

	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) {
		if (scope != null)
			return (!enableThreadScope || access == Accessibility.EXTERNAL) ? "device" : "thread";

		return null;
	}

	@Override
	public String annotationForLocalArray(Class type, String length) {
		if (enableThreadScope && type == Double.class) return "thread";
		return super.annotationForLocalArray(type, length);
	}

	@Override
	public boolean isInt64() { return true; }

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
			out.accept(", ");
			out.accept("uint global_id [[thread_position_in_grid]], ");
			out.accept("uint global_count [[threads_per_grid]]");
		} else {
			out.accept(", ");
			out.accept("uint global_id, ");
			out.accept("uint global_count");
		}
	}

	@Override
	protected String argumentPost(int index, boolean enableAnnotation, Accessibility access) {
		return (enableAnnotation && access == Accessibility.EXTERNAL) ? " [[buffer(" + index + ")]]" : super.argumentPost(index, enableAnnotation, access);
	}
}
