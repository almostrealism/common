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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import org.almostrealism.hardware.jni.CJNIPrintWriter;
import org.almostrealism.hardware.jni.DefaultJNIMemoryAccessor;
import org.almostrealism.io.PrintWriter;
import org.jocl.cl_command_queue;
import org.jocl.cl_event;
import org.jocl.cl_mem;

import java.util.List;
import java.util.stream.IntStream;

/**
 * {@link org.almostrealism.io.PrintWriter} for OpenCL JNI native code generation.
 *
 * <p>Generates JNI C code that calls OpenCL APIs (clEnqueueReadBuffer, clEnqueueWriteBuffer)
 * to transfer data between Java and OpenCL device memory.</p>
 *
 * <h2>Generated Argument Handling</h2>
 *
 * <pre>{@code
 * // Argument reads generate:
 * long *argArr = (*env)->GetLongArrayElements(env, arg, 0);      // cl_mem pointers
 * int *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);  // Offsets
 * int *sizeArr = (*env)->GetIntArrayElements(env, size, 0);      // Sizes
 *
 * // Allocate host buffers
 * float *arg0 = (float*) malloc(4 * sizeArr[0]);
 *
 * // Read from device
 * clEnqueueReadBuffer(commandQueue, (cl_mem) argArr[0], CL_TRUE,
 *                     4 * offsetArr[0], 4 * sizeArr[0], arg0, 0, NULL, NULL);
 *
 * // After computation, write back:
 * clEnqueueWriteBuffer(...);
 * }</pre>
 *
 * @see CLJNILanguageOperations
 * @see CLNativeComputeContext
 */
public class CLJNIPrintWriter extends CJNIPrintWriter {
	public CLJNIPrintWriter(PrintWriter p, String topLevelMethodName, int parallelism, LanguageOperations lang) {
		super(p, topLevelMethodName, parallelism, lang, new DefaultJNIMemoryAccessor());
	}

	@Override
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

		String numberType = getLanguage().getPrecision().typeName();
		int numberSize = getLanguage().getPrecision().bytes();

		IntStream.range(0, arguments.size())
				.mapToObj(i ->
						new ExpressionAssignment(
								new StaticReference<>(Double.class, "*" + arguments.get(i).getName()),
								new StaticReference<>(Double.class, "(" + numberType + "*) malloc("
											+ numberSize + " * sizeArr[" + i + "])")))
				.forEach(this::println);
		arguments.stream().map(argument ->
						new ExpressionAssignment(
								new StaticReference<>(Integer.class, argument.getName() + "Offset"),
								new StaticReference<>(Integer.class, "0")))
				.forEach(this::println);
		IntStream.range(0, arguments.size())
				.mapToObj(i ->
						new ExpressionAssignment(
								new StaticReference(Integer.class, arguments.get(i).getName() + "Size"),
								new StaticReference<>(Integer.class, "sizeArr[" + i + "]")))
				.forEach(this::println);

		println(new ExpressionAssignment(
				new StaticReference(cl_event.class, "*nativeEventWaitList"),
				new StaticReference<>(cl_event.class, "NULL")));
		println(new ExpressionAssignment(
				new StaticReference(cl_event.class, "*nativeEventPointer"),
				new StaticReference<>(cl_event.class, "NULL")));
		IntStream.range(0, arguments.size())
				.mapToObj(i -> clEnqueueBuffer(i, arguments.get(i), false))
				.forEach(super::println);
	}

	@Override
	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		IntStream.range(0, arguments.size())
				.mapToObj(i -> clEnqueueBuffer(i, arguments.get(i), true))
				.forEach(this::println);

		arguments.forEach(arg -> println("free(" + arg.getName() + ");"));
		super.renderArgumentWrites(arguments);
	}

	protected Method<Void> free(ArrayVariable<?> variable) {
		return new Method(Void.class, "free", new InstanceReference<>(variable));
	}

	protected Method<Void> clEnqueueBuffer(int index, ArrayVariable<?> variable, boolean write) {
		int size = getLanguage().getPrecision().bytes();

		Expression<cl_command_queue> nativeCommandQueue =
				new StaticReference<>(cl_command_queue.class, "(cl_command_queue) commandQueue");
		Expression<cl_mem> nativeBuffer =
				new StaticReference<>(cl_mem.class, "(cl_mem) argArr[" + index + "]");
		Expression<Boolean> nativeBlocking =
				new StaticReference<>(Boolean.class, "(cl_bool) CL_TRUE");
		Expression<Integer> nativeOffset =
				new StaticReference<>(Integer.class, size + " * (size_t) offsetArr[" + index + "]");
		Expression<Integer> nativeCb =
				new StaticReference<>(Integer.class, size + " * (size_t) sizeArr[" + index + "]");
		Expression<Integer> nativeNumEvents =
				new StaticReference<>(Integer.class, "(cl_uint) 0");
		Expression<cl_event> nativeEventWaitList = new StaticReference<>(cl_event.class, "nativeEventWaitList");
		Expression<cl_event> nativeEventPointer = new StaticReference<>(cl_event.class, "nativeEventPointer");

		String method = write ? "clEnqueueWriteBuffer" : "clEnqueueReadBuffer";
		return new Method<>(Void.class, method, nativeCommandQueue,
				nativeBuffer, nativeBlocking, nativeOffset, nativeCb,
				new InstanceReference<>(variable), nativeNumEvents,
				nativeEventWaitList, nativeEventPointer);
	}
}
