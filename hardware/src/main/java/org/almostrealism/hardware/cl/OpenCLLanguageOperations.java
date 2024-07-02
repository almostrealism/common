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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.Precision;
import org.almostrealism.c.CLanguageOperations;
import org.almostrealism.hardware.Hardware;
import org.jocl.cl_event;

public class OpenCLLanguageOperations extends CLanguageOperations {

	public OpenCLLanguageOperations(Precision precision) {
		super(precision, false, false);
	}

	@Override
	public String kernelIndex(int index) {
		return "get_global_id(" + index + ")";
	}

	@Override
	public String nameForType(Class<?> type) {
		if (type == cl_event.class) {
			return "cl_event";
		}

		return super.nameForType(type);
	}

	@Override
	public String annotationForPhysicalScope(Accessibility access, PhysicalScope scope) {
		String volatilePrefix = Hardware.getLocalHardware().isMemoryVolatile() ? "volatile " : "";
		if (scope != null) return volatilePrefix + (scope == PhysicalScope.LOCAL ? "__local" : "__global");
		return null;
	}
}
