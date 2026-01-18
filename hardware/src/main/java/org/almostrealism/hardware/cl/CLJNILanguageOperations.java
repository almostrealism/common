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

import io.almostrealism.code.Precision;
import org.almostrealism.c.CJNILanguageOperations;
import org.jocl.cl_event;

/**
 * {@link io.almostrealism.lang.LanguageOperations} for OpenCL JNI code generation.
 *
 * <p>Extends {@link CJNILanguageOperations} to include OpenCL-specific JNI methods
 * (clEnqueueWriteBuffer, clEnqueueReadBuffer, clWaitForEvents) and type mappings.</p>
 *
 * @see CLJNIPrintWriter
 * @see CLNativeComputeContext
 */
public class CLJNILanguageOperations extends CJNILanguageOperations {
	/**
	 * Creates a new OpenCL JNI language operations instance with the specified precision.
	 * Registers OpenCL memory transfer methods (clEnqueueWriteBuffer, clEnqueueReadBuffer,
	 * clWaitForEvents) as library methods.
	 *
	 * @param precision  the floating-point precision (FP32 or FP64) for type mappings
	 */
	public CLJNILanguageOperations(Precision precision) {
		super(precision);
		getLibraryMethods().add("clEnqueueWriteBuffer");
		getLibraryMethods().add("clEnqueueReadBuffer");
		getLibraryMethods().add("clWaitForEvents");
	}

	/**
	 * Returns the C type name for the given Java class.
	 * Maps {@link cl_event} to "cl_event" in addition to the base JNI type mappings.
	 *
	 * @param type  the Java class to map to a C type name
	 * @return the C type name for the class
	 */
	@Override
	public String nameForType(Class<?> type) {
		if (type == cl_event.class) {
			return "cl_event";
		}

		return super.nameForType(type);
	}
}
