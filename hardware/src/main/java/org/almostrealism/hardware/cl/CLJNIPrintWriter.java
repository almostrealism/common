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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Method;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintWriter;
import org.jocl.cl_command_queue;
import org.jocl.cl_event;
import org.jocl.cl_mem;

import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class CLJNIPrintWriter extends CPrintWriter {
	private final Stack<Accessibility> accessStack;
	private final Stack<List<ArrayVariable<?>>> argumentStack;

	public CLJNIPrintWriter(PrintWriter p) {
		super(p);
		setExternalScopePrefix("JNIEXPORT void JNICALL");
		setEnableArrayVariables(true);
		accessStack = new Stack<>();
		argumentStack = new Stack<>();
	}

	@Override
	public void beginScope(String name, List<ArrayVariable<?>> arguments, Accessibility access) {
		super.beginScope(name, arguments, access);

		if (access == Accessibility.EXTERNAL) {
			renderArgumentReads(arguments);
		}

		accessStack.push(access);
		argumentStack.push(arguments);
	}

	@Override
	public void endScope() {
		if (accessStack.pop() == Accessibility.EXTERNAL) {
			renderArgumentWrites(argumentStack.pop());
		} else {
			argumentStack.pop();
		}

		super.endScope();
	}

	@Override
	public void println(Method method) {
		p.println(renderMethod(method));
	}

	@Override
	protected String nameForType(Class<?> type) {
		if (type == Integer.class || type == int[].class) {
			return "jint";
		} else if (type == Long.class || type == long[].class) {
			return "jlong";
		} else {
			return super.nameForType(type);
		}
	}

	@Override
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (access == Accessibility.EXTERNAL) {
			out.accept("JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jint count");
		} else {
			super.renderArguments(arguments, out, access);
		}
	}

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		println(new Variable<>("*argArr", long[].class, "(*env)->GetLongArrayElements(env, arg, 0)"));
		println(new Variable<>("*offsetArr", int[].class, "(*env)->GetIntArrayElements(env, offset, 0)"));
		println(new Variable<>("*sizeArr", int[].class, "(*env)->GetIntArrayElements(env, size, 0)"));

		String numberType = Hardware.getLocalHardware().getNumberTypeName();
		int numberSize = Hardware.getLocalHardware().getNumberSize();

		IntStream.range(0, arguments.size())
				.mapToObj(i -> new Variable("*" + arguments.get(i).getName(),
						new Expression<>(Double.class, "(" + numberType + "*) malloc("
											+ numberSize + " * sizeArr[" + i + "])")))
				.forEach(this::println);
		arguments.stream().map(argument -> new Variable<>(argument.getName() + "Offset",
				Integer.class, "0"))
				.forEach(this::println);
		IntStream.range(0, arguments.size())
				.mapToObj(i -> new Variable<>(arguments.get(i).getName() + "Size",
						Integer.class, "sizeArr[" + i + "]"))
				.forEach(this::println);
		println(new Variable("*nativeEventWaitList", cl_event.class, "NULL"));
		println(new Variable("*nativeEventPointer", cl_event.class, "NULL"));
		IntStream.range(0, arguments.size())
				.mapToObj(i -> clEnqueueBuffer(i, arguments.get(i), false))
				.forEach(super::println);
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		IntStream.range(0, arguments.size())
				.mapToObj(i -> clEnqueueBuffer(i, arguments.get(i), true))
				.forEach(super::println);
	}

	protected Method<Void> clEnqueueBuffer(int index, ArrayVariable<?> variable, boolean write) {
		int size = Hardware.getLocalHardware().getNumberSize();

		Expression<cl_command_queue> nativeCommandQueue =
				new Expression<>(cl_command_queue.class, "(cl_command_queue) commandQueue");
		Expression<cl_mem> nativeBuffer =
				new Expression<>(cl_mem.class, "(cl_mem) argArr[" + index + "]");
		Expression<Boolean> nativeBlocking =
				new Expression<>(Boolean.class, "(cl_bool) CL_TRUE");
		Expression<Integer> nativeOffset =
				new Expression<>(Integer.class, size + " * (size_t) offsetArr[" + index + "]");
		Expression<Integer> nativeCb =
				new Expression<>(Integer.class, size + " * (size_t) sizeArr[" + index + "]");
		Expression<Integer> nativeNumEvents =
				new Expression<>(Integer.class, "(cl_uint) 0");
		Expression<cl_event> nativeEventWaitList = new Expression<>(cl_event.class, "nativeEventWaitList");
		Expression<cl_event> nativeEventPointer = new Expression<>(cl_event.class, "nativeEventPointer");

		String method = write ? "clEnqueueWriteBuffer" : "clEnqueueReadBuffer";
		return new Method<>(Void.class, method, nativeCommandQueue,
				nativeBuffer, nativeBlocking, nativeOffset, nativeCb,
				new InstanceReference<>(variable), nativeNumEvents,
				nativeEventWaitList, nativeEventPointer);
	}
}
