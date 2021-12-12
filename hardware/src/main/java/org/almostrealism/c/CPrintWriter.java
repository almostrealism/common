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
import io.almostrealism.code.CodePrintWriterAdapter;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.Method;
import io.almostrealism.code.ResourceVariable;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.InstanceReference;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintStreamPrintWriter;
import org.almostrealism.io.PrintWriter;
import org.jocl.cl_event;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class CPrintWriter extends CodePrintWriterAdapter {
	private final String topLevelMethodName;
	private final Stack<Accessibility> accessStack;
	private final Stack<List<ArrayVariable<?>>> argumentStack;

	public CPrintWriter(OutputStream out, String topLevelMethodName) {
		this(new PrintStreamPrintWriter(new PrintStream(out)), topLevelMethodName);
	}

	public CPrintWriter(PrintWriter p, String topLevelMethodName) {
		super(p);
		this.topLevelMethodName = topLevelMethodName;
		this.accessStack = new Stack<>();
		this.argumentStack = new Stack<>();
		setScopePrefix("void");
		setEnableArrayVariables(true);
	}

	public String getTopLevelMethodName() { return topLevelMethodName; }

	@Override
	public void beginScope(String name, List<ArrayVariable<?>> arguments, Accessibility access) {
		if (getTopLevelMethodName() == null) {
			super.beginScope(name, arguments, access);
			return;
		}

		if (access == Accessibility.EXTERNAL && getTopLevelMethodName() != null) {
			super.beginScope(getTopLevelMethodName(), arguments, access);
		} else {
			super.beginScope(name, arguments, access);
		}

		if (access == Accessibility.EXTERNAL) {
			renderArgumentReads(arguments);
		}

		accessStack.push(access);
		argumentStack.push(arguments);
	}

	@Override
	public void endScope() {
		if (getTopLevelMethodName() == null) {
			super.endScope();
			return;
		}

		if (accessStack.pop() == Accessibility.EXTERNAL) {
			renderArgumentWrites(argumentStack.pop());
		} else {
			argumentStack.pop();
		}

		super.endScope();
	}

	@Override
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (topLevelMethodName != null && access == Accessibility.EXTERNAL) {
			out.accept("long* argArr, uint32_t* offsetArr, uint32_t* sizeArr, uint32_t count");
		} else {
			super.renderArguments(arguments, out, access);
		}
	}

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		IntStream.range(0, arguments.size())
				.mapToObj(i -> new Variable<>(arguments.get(i).getName() + "Offset",
						Integer.class, "(int) offsetArr[" + i + "]"))
				.forEach(this::println);
		IntStream.range(0, arguments.size())
				.mapToObj(i -> new Variable<>(arguments.get(i).getName() + "Size",
						Integer.class, "(int) sizeArr[" + i + "]"))
				.forEach(this::println);

		IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), false));
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), true));
	}

	protected void copyInline(int index, ArrayVariable<?> variable, boolean write) {
		String o = "((double *) argArr[" + index + "])";
		String v = new InstanceReference<>(variable).getExpression();

		if (!write) println("double *" + v + " = " + o + ";");
	}

	@Override
	public void println(Variable<?, ?> variable) {
		if (variable.isDeclaration()) {
			if (variable.getProducer() == null) {
				if (variable.getExpression() == null || variable.getExpression().getExpression() == null) {
					if (variable.getArraySize() == null) {
						this.p.println(annotationForVariable(variable) + typePrefix(variable.getType()) +
										variable.getName());
					} else {
						this.p.println(annotationForVariable(variable) + typePrefix(variable.getType()) +
								variable.getName() + "[" + variable.getArraySize().getExpression() + "];");
					}
				} else {
					this.p.println(annotationForVariable(variable) + typePrefix(variable.getType()) + variable.getName() +
									" = " + variable.getExpression().getValue() + ";");
				}
			} else {
				this.p.println(annotationForVariable(variable) + typePrefix(variable.getType()) + variable.getName() +
								" = " + encode(variable.getExpression()) + ";");
			}
		} else {
			if (variable.getExpression() == null) {
				//   this.p.println(variable.getName() + " = null;");
			} else {
				this.p.println(variable.getName() + " = " +
								encode(variable.getExpression()) + ";");
			}
		}
	}

	@Override
	public void println(Method method) {
		p.println(renderMethod(method));
	}

	protected void printf(String format, String arg) { printf(format, arg, true); }

	protected void printf(String format, String arg, boolean newLine) {
		println("printf(\"" + format + (newLine ? "\\n\", " : "\", ") + arg + ");");
	}

	public static String renderAssignment(Variable<?, ?> var) {
		return var.getName() + " = " + var.getExpression().getValue() + ";";
	}

	protected String annotationForVariable(Variable<?, ?> var) {
		if (annotationForPhysicalScope(var.getPhysicalScope()) != null) {
			return annotationForPhysicalScope(var.getPhysicalScope()) + " ";
		}

		return "";
	}

	@Override
	protected String nameForType(Class<?> type) { return typeString(type); }

	private static String typeString(Class type) {
		if (type == null) return "";

		if (type == Double.class) {
			return Hardware.getLocalHardware().getNumberTypeName();
		} else if (type == Integer.class || type == int[].class) {
			return "int";
		} else if (type == Long.class || type == long[].class) {
			return "long";
		} else if (type == cl_event.class) {
			return "cl_event";
		} else {
			throw new IllegalArgumentException("Unable to encode " + type);
		}
	}

	protected static String encode(Object data) {
		if (data instanceof Expression) {
			return ((Expression) data).getExpression();
		} else {
			throw new IllegalArgumentException("Unable to encode " + data);
		}
	}

	protected static String toString(Map<String, Variable> args, List<String> argumentOrder) {
		StringBuilder buf = new StringBuilder();

		for (int i = 0; i < argumentOrder.size(); i++) {
			Variable v = args.get(argumentOrder.get(i));

			if (v instanceof ResourceVariable) {
				buf.append(encode(v.getProducer()));
			}

			if (i < argumentOrder.size() - 1) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
