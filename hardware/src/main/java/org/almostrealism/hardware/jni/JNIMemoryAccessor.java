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

package org.almostrealism.hardware.jni;

import io.almostrealism.expression.InstanceReference;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;

/**
 * Interface for generating C code that accesses memory passed via JNI argument arrays.
 *
 * <p>{@link JNIMemoryAccessor} abstracts memory access patterns for different memory types
 * (standard RAM, Metal unified memory, OpenCL buffers) by generating appropriate C code to
 * access data from the {@code argArr} parameter passed to native methods.</p>
 *
 * <h2>JNI Function Signature</h2>
 *
 * <p>Native methods receive arguments as arrays:</p>
 * <pre>
 * void JNICALL Java_..._apply(
 *     JNIEnv *env,
 *     jobject obj,
 *     jlong commandQueue,
 *     jlongArray argArr,      // Array of memory pointers
 *     jintArray offsetArr,    // Array of offsets
 *     jintArray sizeArr,      // Array of sizes
 *     jint count,
 *     jint globalId,
 *     jlong kernelSize
 * )
 * </pre>
 *
 * <h2>Memory Access Pattern</h2>
 *
 * <p>{@link #copyInline} generates C code to declare pointers from {@code argArr}:</p>
 * <pre>{@code
 * // For argument at index 0:
 * double *arg0 = ((double *) argArr[0]);
 *
 * // For argument at index 1:
 * float *arg1 = ((float *) argArr[1]);
 * }</pre>
 *
 * <h2>Implementation Examples</h2>
 *
 * <h3>DefaultJNIMemoryAccessor (Standard RAM)</h3>
 * <pre>
 * // Generated code:
 * double *input = ((double *) argArr[0]);
 * double *output = ((double *) argArr[1]);
 *
 * // Direct pointer access to heap memory
 * </pre>
 *
 * <h3>MetalJNIMemoryAccessor (Unified Memory)</h3>
 * <pre>
 * // Generated code similar but memory is in Metal unified address space
 * // Accessible by both CPU and GPU
 * </pre>
 *
 * <h2>Usage in Code Generation</h2>
 *
 * <pre>{@code
 * JNIMemoryAccessor accessor = new DefaultJNIMemoryAccessor();
 * ArrayVariable<Double> input = new ArrayVariable<>("input", ...);
 *
 * // Generate pointer declaration
 * String code = accessor.copyInline(lang, 0, input, false);
 * // Returns: "double *input = ((double *) argArr[0]);"
 * }</pre>
 *
 * <h2>Write vs Read</h2>
 *
 * <p>The {@code write} parameter (currently unused) is intended for future optimizations
 * where write-only memory might use different access patterns:</p>
 * <pre>{@code
 * // Read access: full pointer declaration
 * accessor.copyInline(lang, 0, var, false);  // double *var = ...
 *
 * // Write access: potentially different (future)
 * accessor.copyInline(lang, 0, var, true);   // Currently returns null
 * }</pre>
 *
 * @see DefaultJNIMemoryAccessor
 * @see CJNIPrintWriter
 * @see NativeComputeContext
 */
public interface JNIMemoryAccessor {
	default String copyInline(LanguageOperations lang, int index, ArrayVariable<?> variable, boolean write) {
		String o = "((" + lang.getPrecision().typeName() + " *) argArr[" + index + "])";
		String v = new InstanceReference<>(variable).getSimpleExpression(lang);

		if (write) {
			return null;
		} else {
			return lang.getPrecision().typeName() + " *" + v + " = " + o + ";";
		}
	}
}
