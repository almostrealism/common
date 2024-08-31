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

package org.almostrealism.c;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.lang.DefaultLanguageOperations;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.scope.ArrayVariable;

import java.util.List;
import java.util.function.Consumer;

public class CLanguageOperations extends DefaultLanguageOperations {
	private boolean isNative;
	private boolean enableArgumentDetailReads;

	public CLanguageOperations(Precision precision,
							   boolean isNative,
							   boolean enableArgumentDetailReads) {
		super(precision, true);
		this.isNative = isNative;
		this.enableArgumentDetailReads = enableArgumentDetailReads;
	}

	public boolean isNative() { return isNative; }

	public boolean isEnableArgumentDetailReads() { return enableArgumentDetailReads; }

	@Override
	public String pi() {
		return getPrecision() == Precision.FP64 ? "M_PI" : "M_PI_F";
	}

	@Override
	public String kernelIndex(int index) {
		if (index != 0)
			throw new IllegalArgumentException();

		return "global_id";
	}

	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) {
		return null;
	}

	@Override
	public String nameForType(Class<?> type) {
		if (type == null) return "";

		if (type == Double.class) {
			return getPrecision().typeName();
		} else if (type == Integer.class || type == int[].class) {
			return "int";
		} else if (type == Long.class || type == long[].class) {
			return "long";
		} else if (type == Boolean.class) {
			return isNumericBoolean() ? "int" : "bool";
		} else {
			throw new IllegalArgumentException("Unable to encode " + type);
		}
	}

	public boolean isNumericBoolean() {
		return true;
	}

	@Override
	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			if (isNative()) {
				out.accept("long* argArr, uint32_t* offsetArr, uint32_t* sizeArr, uint32_t* dim0Arr, uint32_t count");
				return;
			} else if (isEnableArgumentDetailReads() && isEnableArrayVariables()) {
				if (!arguments.isEmpty()) {
					renderArguments(arguments, out, true, true, access, ParamType.ARRAY);
					out.accept(", ");
					out.accept(annotationForPhysicalScope(access, PhysicalScope.GLOBAL));
					out.accept(" int *offsetArr");
					out.accept(argumentPost(arguments.size(), true, access));
					out.accept(", ");
					out.accept(annotationForPhysicalScope(access, PhysicalScope.GLOBAL));
					out.accept(" int *sizeArr");
					out.accept(argumentPost(arguments.size() + 1, true, access));
					out.accept(", ");
					out.accept(annotationForPhysicalScope(access, PhysicalScope.GLOBAL));
					out.accept(" int *dim0Arr");
					out.accept(argumentPost(arguments.size() + 2, true, access));
				}

				return;
			}
		}

		super.renderArguments(arguments, out, access);
	}

	@Override
	public String getStatementTerminator() {
		return ";";
	}
}
