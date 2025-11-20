/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.io.PrintWriter;

import java.util.List;

/**
 * Specialized {@link CPrintWriter} that generates C code with JNI function signatures and memory access.
 *
 * <p>{@link CJNIPrintWriter} extends {@link CPrintWriter} to generate native methods compatible with
 * Java's JNI (Java Native Interface). It handles:</p>
 * <ul>
 *   <li><strong>JNI signatures:</strong> JNIEXPORT/JNICALL function declarations</li>
 *   <li><strong>Array parameter access:</strong> GetLongArrayElements, GetIntArrayElements</li>
 *   <li><strong>Parallelism loops:</strong> Automatic loop generation for multi-threaded execution</li>
 *   <li><strong>Memory accessor integration:</strong> Delegates pointer declarations to {@link JNIMemoryAccessor}</li>
 * </ul>
 *
 * <h2>Generated Function Signature</h2>
 *
 * <pre>
 * JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation0_apply(
 *     JNIEnv *env,
 *     jobject obj,
 *     jlong commandQueue,
 *     jlongArray arg,
 *     jintArray offset,
 *     jintArray size,
 *     jint count,
 *     jint global_index,
 *     jlong global_total
 * )
 * </pre>
 *
 * <h2>Parallelism Code Generation</h2>
 *
 * <p>When parallelism > 1, wraps the kernel body in a for loop:</p>
 * <pre>{@code
 * // parallelism = 8:
 * for (int global_id = global_index; global_id < global_total; global_id += 8) {
 *     // Kernel body uses global_id to determine which subset to process
 * }
 * }</pre>
 *
 * <h2>JNI Array Access</h2>
 *
 * <p>Extracts native arrays from JNI array parameters:</p>
 * <pre>
 * long *argArr = (*env)->GetLongArrayElements(env, arg, 0);
 * int *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
 * int *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
 *
 * // ... use arrays ...
 *
 * (*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
 * (*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
 * (*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
 * </pre>
 *
 * <h2>Memory Accessor Delegation</h2>
 *
 * <p>Uses {@link JNIMemoryAccessor} to generate argument pointer declarations:</p>
 * <pre>{@code
 * // For each argument:
 * accessor.copyInline(lang, index, variable, false);
 * // Generates:
 * // double *input = ((double *) argArr[0]);
 * }</pre>
 *
 * <h2>Usage in Code Generation</h2>
 *
 * <pre>{@code
 * PrintWriter pw = new PrintWriter(outputStream);
 * JNIMemoryAccessor accessor = new DefaultJNIMemoryAccessor();
 *
 * CJNIPrintWriter writer = new CJNIPrintWriter(
 *     pw,
 *     "Java_org_..._GeneratedOperation0_apply",  // Function name
 *     8,                                          // Parallelism
 *     new CJNILanguageOperations(Precision.FP64),
 *     accessor
 * );
 *
 * ScopeEncoder encoder = new ScopeEncoder(w -> writer, Accessibility.EXTERNAL);
 * String code = encoder.apply(scope);
 * }</pre>
 *
 * @see CPrintWriter
 * @see JNIMemoryAccessor
 * @see NativeComputeContext
 */
public class CJNIPrintWriter extends CPrintWriter {
	private JNIMemoryAccessor accessor;
	private int parallel;

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName, int parallelism,
						   LanguageOperations lang, JNIMemoryAccessor memAccess) {
		this(p, topLevelMethodName, parallelism, lang, memAccess, false);
	}

	public CJNIPrintWriter(PrintWriter p, String topLevelMethodName, int parallelism,
						   LanguageOperations lang, JNIMemoryAccessor memAccess, boolean verbose) {
		super(p, topLevelMethodName, lang.getPrecision(), verbose);
		parallel = parallelism;
		language = lang;
		accessor = memAccess;
		setExternalScopePrefix("JNIEXPORT void JNICALL");
		setEnableArgumentValueReads(true);
		setEnableArgumentValueWrites(true);
	}

	@Override
	public void beginScope(String name, OperationMetadata metadata, Accessibility access, List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters) {
		super.beginScope(name, metadata, access, arguments, parameters);

		if (access == Accessibility.EXTERNAL) {
			if (parallel > 1) {
				println("for (int global_id = global_index ; global_id < global_total; global_id += " + parallel + ") {");
			}
		}
	}

	@Override
	public void endScope() {
		if (isExternalScope() && parallel > 1) {
			println("}");
		}

		super.endScope();
	}

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		println(new ExpressionAssignment<long[]>(true,
				new StaticReference(long[].class, "*argArr"),
				new StaticReference<>(long[].class, "(*env)->GetLongArrayElements(env, arg, 0)")));
		println(new ExpressionAssignment<int[]>(true,
				new StaticReference(int[].class, "*offsetArr"),
				new StaticReference<>(int[].class, "(*env)->GetIntArrayElements(env, offset, 0)")));
		println(new ExpressionAssignment<int[]>(true,
				new StaticReference(int[].class, "*sizeArr"),
				new StaticReference<>(int[].class, "(*env)->GetIntArrayElements(env, size, 0)")));

		super.renderArgumentReads(arguments);
	}

	@Override
	protected void copyInline(int index, ArrayVariable<?> variable, boolean write) {
		String access = accessor.copyInline(getLanguage(), index, variable, write);
		if (access != null) println(access);
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		super.renderArgumentWrites(arguments);

		println("(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);");
		println("(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);");
		println("(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);");
	}
}
