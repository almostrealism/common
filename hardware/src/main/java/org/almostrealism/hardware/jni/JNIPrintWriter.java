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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Method;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;
import org.almostrealism.c.CPrintWriter;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintWriter;
import org.jocl.cl_command_queue;
import org.jocl.cl_mem;

import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class JNIPrintWriter extends CPrintWriter {
	Stack<List<ArrayVariable<?>>> argumentStack;

	public JNIPrintWriter(PrintWriter p) {
		super(p);
		setScopePrefix("JNIEXPORT void JNICALL");
		argumentStack = new Stack<>();
	}

	@Override
	public void beginScope(String name, List<ArrayVariable<?>> arguments) {
		super.beginScope(name, arguments);
		renderArgumentReads(arguments);
		argumentStack.push(arguments);
	}

	@Override
	public void endScope() {
		renderArgumentWrites(argumentStack.pop());
		super.endScope();
	}

	@Override
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out) {
		out.accept("JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jint count");
	}

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		IntStream.range(0, arguments.size())
				.mapToObj(i -> {
					ArrayVariable<Double> v = new ArrayVariable(arguments.get(i).getNameProvider(), arguments.get(i).getName(), new Expression<>(Integer.class, "size[" + i + "]"));
					v.setExpression(new Expression<>(Double.class, (String) null));
					return v;
				}).forEach(this::println);
		IntStream.range(0, arguments.size())
				.mapToObj(i -> clEnqueueBuffer(i, arguments.get(i), false))
				.forEach(this::println);
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		IntStream.range(0, arguments.size())
				.mapToObj(i -> clEnqueueBuffer(i, arguments.get(i), true))
				.forEach(this::println);
	}

	protected Method<Void> clEnqueueBuffer(int index, ArrayVariable<?> variable, boolean write) {
		int size = Hardware.getLocalHardware().getNumberSize();

		Expression<cl_command_queue> nativeCommandQueue =
				new Expression<>(cl_command_queue.class, "(cl_command_queue) commandQueue");
		Expression<cl_mem> nativeBuffer =
				new Expression<>(cl_mem.class, "(cl_mem) arg[" + index + "]");
		Expression<Boolean> nativeBlocking =
				new Expression<>(Boolean.class, "CL_TRUE");
		Expression<Integer> nativeOffset =
				new Expression<>(Integer.class, size + " * offset[" + index + "]");
		Expression<Integer> nativeCb =
				new Expression<>(Integer.class, size + " * size[" + index + "]");
		Expression<Integer> nativeNumEvents =
				new Expression<>(Integer.class, "0");
		Expression<Object> nullExp = new Expression<>(Object.class, "NULL");

		String method = write ? "clEnqueueWriteBuffer" : "clEnqueueReadBuffer";
		return new Method<>(Void.class, method, nativeCommandQueue,
				nativeBuffer, nativeBlocking, nativeOffset, nativeCb,
				new InstanceReference<>(variable), nativeNumEvents, nullExp, nullExp);
	}
}
