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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Method;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class CJNIPrintWriter extends CPrintWriter {
	public static final boolean enableInlineCopy = true;

	private final Stack<Accessibility> accessStack;
	private final Stack<List<ArrayVariable<?>>> argumentStack;

	public CJNIPrintWriter(PrintWriter p) {
		super(p);
		setExternalScopePrefix("JNIEXPORT void JNICALL");
		setEnableArrayVariables(true);
		accessStack = new Stack<>();
		argumentStack = new Stack<>();
	}

	@Override
	public void beginScope(String name, List<ArrayVariable<?>> arguments, Accessibility access) {
		if (!enableInlineCopy && access == Accessibility.EXTERNAL) {
			renderReadWrite();
		}

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
		String numberType = Hardware.getLocalHardware().getNumberTypeName();
		int numberSize = Hardware.getLocalHardware().getNumberSize();

		println(new Variable<>("*argArr", long[].class, "(*env)->GetLongArrayElements(env, arg, 0)"));
		println(new Variable<>("*offsetArr", int[].class, "(*env)->GetIntArrayElements(env, offset, 0)"));
		println(new Variable<>("*sizeArr", int[].class, "(*env)->GetIntArrayElements(env, size, 0)"));

		IntStream.range(0, arguments.size())
				.mapToObj(i -> new Variable("*" + arguments.get(i).getName(),
						new Expression<>(Double.class, "(" + numberType + "*) malloc("
								+ numberSize + " * (int) sizeArr[" + i + "])")))
				.forEach(this::println);
		arguments.stream().map(argument -> new Variable<>(argument.getName() + "Offset",
				Integer.class, "0"))
				.forEach(this::println);
		IntStream.range(0, arguments.size())
				.mapToObj(i -> new Variable<>(arguments.get(i).getName() + "Size",
						Integer.class, "(int) sizeArr[" + i + "]"))
				.forEach(this::println);

		if (enableInlineCopy) {
			IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), false));
		} else {
			IntStream.range(0, arguments.size())
					.mapToObj(i -> copyMethod(i, arguments.get(i), false))
					.forEach(super::println);
		}
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		if (enableInlineCopy) {
			IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), true));
		} else {
			IntStream.range(0, arguments.size())
					.mapToObj(i -> copyMethod(i, arguments.get(i), true))
					.forEach(super::println);
		}
	}

	protected Method<Void> copyMethod(int index, ArrayVariable<?> variable, boolean write) {
		int size = Hardware.getLocalHardware().getNumberSize();

		Expression<double[]> nativeBuffer =
				new Expression<>(double[].class, "(double *) argArr[" + index + "]");
		Expression<Integer> nativeOffset =
				new Expression<>(Integer.class, "offsetArr[" + index + "]");
		Expression<Integer> nativeCb =
				new Expression<>(Integer.class, "sizeArr[" + index + "]");

		String method = write ? "write" : "read";

		return new Method<>(Void.class, method,
					nativeBuffer, nativeOffset, nativeCb,
					new InstanceReference<>(variable));
	}

	protected void copyInline(int index, ArrayVariable<?> variable, boolean write) {
		String o = "((double *) argArr[" + index + "])";
		String offset = "offsetArr[" + index + "]";
		String size = "sizeArr[" + index + "]";
		String v = new InstanceReference<>(variable).getExpression();

		if (!write) {
			println("for (int i = 0; i < " + size + "; i++) {");
			println("\t" + v + "[i] = " + o + "[" + offset + " + i];");
			println("}");
		} else {
			println("for (int i = 0; i < " + size + "; i++) {");
			println("\t" + o + "[" + offset + " + i] = " + v + "[i];");
			println("}");
		}
	}

	private void renderReadWrite() {
		println("void read(double* o, int offset, int size, double* v) {");
		println("\tfor (int i = 0; i < size; i++) {");
		println("\t\tv[i] = o[offset + i];");
		println("\t}");
		println("}");

		println("void write(double* o, int offset, int size, double* v) {");
		println("\tfor (int i = 0; i < size; i++) {");
		println("\t\to[offset + i] = v[i];");
		println("\t}");
		println("}");
	}
}
