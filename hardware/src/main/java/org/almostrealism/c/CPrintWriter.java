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

package org.almostrealism.c;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.compute.PhysicalScope;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.lang.CodePrintWriterAdapter;
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
import java.util.Stack;
import java.util.stream.IntStream;

/**
 * A print writer for generating C language code from computation scopes.
 *
 * <p>This writer extends {@link CodePrintWriterAdapter} to provide C-specific code generation,
 * including method signatures, argument handling, and scope management for compiled kernels.</p>
 */
public class CPrintWriter extends CodePrintWriterAdapter {
	/** The name of the top-level method to generate, or null to use scope names directly. */
	private final String topLevelMethodName;
	/** Stack tracking accessibility levels of nested scopes. */
	private final Stack<Accessibility> accessStack;
	/** Stack tracking array variable arguments for each nested scope level. */
	private final Stack<List<ArrayVariable<?>>> argumentStack;

	/** Whether to generate argument read operations at scope entry. */
	private boolean enableArgumentValueReads;
	/** Whether to generate argument write operations at scope exit. */
	private boolean enableArgumentValueWrites;

	/** Whether verbose logging is enabled for debug output during code generation. */
	private final boolean verbose;
	/** Whether logging is currently active for the current scope. */
	private boolean log;
	/** Counter for generating sequential log identifiers in verbose mode. */
	private int logCount;

	/**
	 * Constructs a CPrintWriter that writes to an output stream.
	 *
	 * @param out the output stream to write generated C code to
	 * @param topLevelMethodName the name of the top-level method to generate
	 * @param precision the floating-point precision for generated code
	 */
	public CPrintWriter(OutputStream out, String topLevelMethodName, Precision precision) {
		this(new PrintStreamPrintWriter(new PrintStream(out)), topLevelMethodName, precision);
	}

	/**
	 * Constructs a CPrintWriter with a print writer backend.
	 *
	 * @param p the print writer to write generated C code to
	 * @param topLevelMethodName the name of the top-level method to generate
	 * @param precision the floating-point precision for generated code
	 */
	public CPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision) {
		this(p, topLevelMethodName, precision, false);
	}

	/**
	 * Constructs a CPrintWriter with native code generation option.
	 *
	 * @param p the print writer to write generated C code to
	 * @param topLevelMethodName the name of the top-level method to generate
	 * @param precision the floating-point precision for generated code
	 * @param isNative true to generate native (JNI) compatible code
	 */
	public CPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision, boolean isNative) {
		this(p, topLevelMethodName, precision, isNative, false);
	}

	/**
	 * Constructs a CPrintWriter with full configuration options.
	 *
	 * @param p the print writer to write generated C code to
	 * @param topLevelMethodName the name of the top-level method to generate
	 * @param precision the floating-point precision for generated code
	 * @param isNative true to generate native (JNI) compatible code
	 * @param verbose true to enable verbose logging output during code generation
	 */
	public CPrintWriter(PrintWriter p, String topLevelMethodName, Precision precision, boolean isNative, boolean verbose) {
		super(p, new CLanguageOperations(precision, isNative, false));
		this.topLevelMethodName = topLevelMethodName;
		this.accessStack = new Stack<>();
		this.argumentStack = new Stack<>();
		this.verbose = verbose;
		setScopePrefix("void");
	}

	/**
	 * Returns the name of the top-level method being generated.
	 *
	 * @return the top-level method name
	 */
	public String getTopLevelMethodName() { return topLevelMethodName; }

	/**
	 * Enables or disables generation of argument value read operations at scope entry.
	 *
	 * @param enableArgumentValueReads true to generate code that reads argument values
	 */
	public void setEnableArgumentValueReads(boolean enableArgumentValueReads) {
		this.enableArgumentValueReads = enableArgumentValueReads;
	}

	/**
	 * Enables or disables generation of argument value write operations at scope exit.
	 *
	 * @param enableArgumentValueWrites true to generate code that writes argument values
	 */
	public void setEnableArgumentValueWrites(boolean enableArgumentValueWrites) {
		this.enableArgumentValueWrites = enableArgumentValueWrites;
	}

	/**
	 * Returns whether the current scope has external accessibility.
	 *
	 * @return true if the current scope is externally accessible
	 */
	protected boolean isExternalScope() {
		return accessStack.peek() == Accessibility.EXTERNAL;
	}

	/**
	 * Begins a new scope in the generated C code.
	 *
	 * <p>For external scopes, uses the top-level method name if configured, and generates
	 * argument read operations if enabled. Pushes accessibility and arguments onto internal
	 * stacks for tracking nested scopes.</p>
	 *
	 * @param name the scope name (may be overridden by topLevelMethodName for external scopes)
	 * @param metadata operation metadata for rendering comments
	 * @param access the accessibility level of this scope
	 * @param arguments array variables passed as arguments to this scope
	 * @param parameters additional parameters for this scope
	 */
	@Override
	public void beginScope(String name, OperationMetadata metadata, Accessibility access,
						   List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters) {
		if (arguments.size() > 150) {
			System.out.println("WARN: " + arguments.size() + " arguments to generated function");
		}

		renderMetadata(metadata);

		if (getTopLevelMethodName() == null) {
			super.beginScope(name, metadata, access, arguments, parameters);
			return;
		}

		if (access == Accessibility.EXTERNAL && getTopLevelMethodName() != null) {
			if (!parameters.isEmpty())
				throw new UnsupportedOperationException();

			super.beginScope(getTopLevelMethodName(), metadata, access, arguments, parameters);
		} else {
			super.beginScope(name, metadata, access, arguments, parameters);
		}

		if (access == Accessibility.EXTERNAL) {
			renderArgumentReads(arguments);
		}

		accessStack.push(access);
		argumentStack.push(arguments);
	}

	/**
	 * Ends the current scope in the generated C code.
	 *
	 * <p>For external scopes, generates argument write operations if enabled.
	 * Pops accessibility and arguments from internal stacks.</p>
	 */
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

	/**
	 * Renders C code for reading argument values at scope entry.
	 *
	 * <p>Generates offset and size variable assignments if detail reads are enabled,
	 * and inline copy statements if argument value reads are enabled.</p>
	 *
	 * @param arguments the list of array variables to generate read code for
	 */
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
		}

		if (enableArgumentValueReads) {
			IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), false));
		}
	}

	/**
	 * Renders C code for writing argument values at scope exit.
	 *
	 * @param arguments the list of array variables to generate write code for
	 */
	protected void renderArgumentWrites(List<ArrayVariable<?>> arguments) {
		if (enableArgumentValueWrites) {
			IntStream.range(0, arguments.size()).forEach(i -> copyInline(i, arguments.get(i), true));
		}
	}

	/**
	 * Generates an inline copy statement for an argument variable.
	 *
	 * @param index the argument index in the argument array
	 * @param variable the array variable to copy
	 * @param write true to generate a write operation, false for read
	 */
	protected void copyInline(int index, ArrayVariable<?> variable, boolean write) {
		String o = "((" + getLanguage().getPrecision().typeName() + " *) argArr[" + index + "])";
		String v = new InstanceReference<>(variable).getSimpleExpression(getLanguage());

		if (!write) println(getLanguage().getPrecision().typeName() + " *" + v + " = " + o + ";");
	}

	/**
	 * Prints an expression assignment statement to the output.
	 *
	 * <p>For declarations, includes the appropriate type annotation based on physical scope.</p>
	 *
	 * @param variable the expression assignment to print
	 */
	@Override
	public void println(ExpressionAssignment<?> variable) {
		if (variable.isDeclaration()) {
			println(annotationForAssignment(variable) + variable.getStatement(getLanguage()) + ";");
		} else {
			println(variable.getStatement(getLanguage()) + ";");
		}
	}

	/**
	 * Prints a metric logging statement to the output.
	 *
	 * <p>Generates code that periodically prints variable values based on the metric's log frequency.</p>
	 *
	 * @param m the metric to generate logging code for
	 */
	@Override
	public void println(Metric m) {
		String ctr = m.getCounter().getExpression(getLanguage());
		println(ctr + " = fmod(" + ctr + " + 1, " + m.getLogFrequency() + ");");
		println("if (" + ctr + " == 0) {");
		m.getVariables().forEach((msg, var) -> printf(msg + ": %f", var.getExpression(getLanguage())));
		println("}");
	}

	/**
	 * Prints a string followed by a newline, with logging enabled.
	 *
	 * @param s the string to print
	 */
	public void println(String s) {
		println(s, true);
	}

	/**
	 * Prints a string followed by a newline.
	 *
	 * <p>If verbose mode is enabled and logging is active, may also generate debug output.</p>
	 *
	 * @param s the string to print
	 * @param log true to enable verbose logging for this statement
	 */
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

	/**
	 * Prints a debug log statement if verbose mode is enabled.
	 *
	 * <p>Generates a printf statement with an incrementing log counter.</p>
	 */
	protected void printLog() {
		if (verbose) {
			println("if (commandQueue > 0)", false);
			printf("Reached %i", String.valueOf(logCount++));
		}
	}

	/**
	 * Prints a debug log statement with a custom message.
	 *
	 * @param message the message to include in the generated printf statement
	 */
	protected void printLog(String message) {
		println("printf(\"" + message + "\\n\");", false);
	}

	/**
	 * Generates a printf statement with a newline.
	 *
	 * @param format the printf format string
	 * @param arg the argument expression to print
	 */
	protected void printf(String format, String arg) { printf(format, arg, true); }

	/**
	 * Generates a printf statement.
	 *
	 * @param format the printf format string
	 * @param arg the argument expression to print
	 * @param newLine true to append a newline to the output
	 */
	protected void printf(String format, String arg, boolean newLine) {
		println("printf(\"" + format + (newLine ? "\\n\", " : "\", ") + arg + ");", false);
	}

	/**
	 * Returns the type annotation prefix for an assignment based on its physical scope.
	 *
	 * @param assignment the expression assignment to get the annotation for
	 * @return the annotation string with trailing space, or empty string if none
	 */
	protected String annotationForAssignment(ExpressionAssignment<?> assignment) {
		PhysicalScope scope = assignment.getPhysicalScope();
		if (language.annotationForPhysicalScope(null, scope) != null) {
			return language.annotationForPhysicalScope(null, scope) + " ";
		}

		return "";
	}

	/**
	 * Encodes data as a C expression string.
	 *
	 * @param data the data to encode, must be an {@link Expression}
	 * @return the C language representation of the expression
	 * @throws IllegalArgumentException if data is not an Expression
	 */
	protected String encode(Object data) {
		if (data instanceof Expression) {
			return ((Expression) data).getSimpleExpression(language);
		} else {
			throw new IllegalArgumentException("Unable to encode " + data);
		}
	}

	/**
	 * Converts a map of variables to a comma-separated argument string.
	 *
	 * @param args the map of variable names to variables
	 * @param argumentOrder the ordered list of argument names
	 * @return a comma-separated string of encoded arguments
	 */
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
