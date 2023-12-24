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
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.CodePrintWriterAdapter;
import io.almostrealism.expression.Expression;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.InstanceReference;
import org.almostrealism.io.PrintStreamPrintWriter;
import org.almostrealism.io.PrintWriter;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.stream.IntStream;

public class CPrintWriter extends CodePrintWriterAdapter {
	private final String topLevelMethodName;
	private final Stack<Accessibility> accessStack;
	private final Stack<List<ArrayVariable<?>>> argumentStack;

	private boolean enableArgumentValueReads;
	private boolean enableArgumentValueWrites;

	private final boolean verbose;
	private boolean log;
	private int logCount;

	public CPrintWriter(OutputStream out, String topLevelMethodName, Precision precision) {
		this(new PrintStreamPrintWriter(new PrintStream(out)), topLevelMethodName, precision);
	}

	public CPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision) {
		this(p, topLevelMethodName, precision, false);
	}

	public CPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision, boolean isNative) {
		this(p, topLevelMethodName, precision, isNative, false);
	}

	public CPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision, boolean isNative, boolean verbose) {
		super(p, new CLanguageOperations(precision, isNative, false));
		this.topLevelMethodName = topLevelMethodName;
		this.accessStack = new Stack<>();
		this.argumentStack = new Stack<>();
		this.verbose = verbose;
		setScopePrefix("void");
	}

	public String getTopLevelMethodName() { return topLevelMethodName; }

	public void setEnableArgumentValueReads(boolean enableArgumentValueReads) {
		this.enableArgumentValueReads = enableArgumentValueReads;
	}

	public void setEnableArgumentValueWrites(boolean enableArgumentValueWrites) {
		this.enableArgumentValueWrites = enableArgumentValueWrites;
	}

	@Override
	public void beginScope(String name, OperationMetadata metadata, List<ArrayVariable<?>> arguments, Accessibility access) {
		if (arguments.size() > 150) {
			System.out.println("WARN: " + arguments.size() + " arguments to generated function");
		}

		renderMetadata(metadata);

		if (getTopLevelMethodName() == null) {
			super.beginScope(name, metadata, arguments, access);
			return;
		}

		if (access == Accessibility.EXTERNAL && getTopLevelMethodName() != null) {
			super.beginScope(getTopLevelMethodName(), metadata, arguments, access);
		} else {
			super.beginScope(name, metadata, arguments, access);
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

	protected void renderArgumentReads(List<ArrayVariable<?>> arguments) {
		if (((CLanguageOperations) language).isEnableArgumentDetailReads()) {
			IntStream.range(0, arguments.size())
					.mapToObj(i -> new ExpressionAssignment(true, new StaticReference(Integer.class, arguments.get(i).getName() + "Offset"),
							new StaticReference<>(Integer.class, "(int) offsetArr[" + i + "]")))
					.forEach(this::println);
			IntStream.range(0, arguments.size())
					.mapToObj(i -> new ExpressionAssignment(true, new StaticReference(Integer.class, arguments.get(i).getName() + "Size"),
							new StaticReference<>(Integer.class, "(int) sizeArr[" + i + "]")))
					.forEach(this::println);
			IntStream.range(0, arguments.size())
					.mapToObj(i -> new ExpressionAssignment(true, new StaticReference(Integer.class, arguments.get(i).getName() + "Dim0"),
							new StaticReference<>(Integer.class, "(int) dim0Arr[" + i + "]")))
					.forEach(this::println);
		}

		if (enableArgumentValueReads) {
			IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), false));
		}
	}

	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		if (enableArgumentValueWrites) {
			IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), true));
		}
	}

	protected void copyInline(int index, ArrayVariable<?> variable, boolean write) {
		String o = "((" + getLanguage().getPrecision().typeName() + " *) argArr[" + index + "])";
		String v = new InstanceReference<>(variable).getSimpleExpression(getLanguage());

		if (!write) println(getLanguage().getPrecision().typeName() + " *" + v + " = " + o + ";");
	}

	@Override
	public void println(ExpressionAssignment<?> variable) {
		if (variable.isDeclaration()) {
			println(annotationForAssignment(variable) + variable.getStatement(getLanguage()) + ";");
		} else {
			println(variable.getStatement(getLanguage()) + ";");
		}
	}

	@Override
	public void println(Metric m) {
		String ctr = m.getCounter().getExpression(getLanguage());
		println(ctr + " = fmod(" + ctr + " + 1, " + m.getLogFrequency() + ");");
		println("if (" + ctr + " == 0) {");
		m.getVariables().forEach((msg, var) -> printf(msg + ": %f", var.getExpression(getLanguage())));
		println("}");
	}

	public void println(String s) {
		println(s, true);
	}

	public void println(String s, boolean log) {
		super.println(s);
		if (verbose && this.log && log) {
			if (logCount >= 10 && logCount < 14) {
				super.println("if (commandQueue > 0)");
				printf("Reached %i", String.valueOf(logCount++));
			} else {
				logCount++;
			}
		}
	}

	protected void printLog() {
		if (verbose) {
			println("if (commandQueue > 0)", false);
			printf("Reached %i", String.valueOf(logCount++));
		}
	}

	protected void printLog(String message) {
		println("printf(\"" + message + "\\n\");", false);
	}

	protected void printf(String format, String arg) { printf(format, arg, true); }

	protected void printf(String format, String arg, boolean newLine) {
		println("printf(\"" + format + (newLine ? "\\n\", " : "\", ") + arg + ");", false);
	}

	protected String annotationForAssignment(ExpressionAssignment<?> assignment) {
		PhysicalScope scope = assignment.getPhysicalScope();
		if (language.annotationForPhysicalScope(scope) != null) {
			return language.annotationForPhysicalScope(scope) + " ";
		}

		return "";
	}

	protected String encode(Object data) {
		if (data instanceof Expression) {
			return ((Expression) data).getSimpleExpression(language);
		} else {
			throw new IllegalArgumentException("Unable to encode " + data);
		}
	}

	protected static String toString(Map<String, Variable> args, List<String> argumentOrder) {
		StringBuilder buf = new StringBuilder();

		for (int i = 0; i < argumentOrder.size(); i++) {
			Variable v = args.get(argumentOrder.get(i));

//			TODO
//			if (v instanceof ResourceVariable) {
//				buf.append(encode(v.getProducer()));
//			}

			if (i < argumentOrder.size() - 1) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
