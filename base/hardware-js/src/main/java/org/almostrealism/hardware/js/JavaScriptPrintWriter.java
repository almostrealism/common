/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.hardware.js;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.expression.Expression;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.lang.CodePrintWriterAdapter;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@link CodePrintWriter} that writes JavaScript: declarations as {@code var}, method calls
 * as statements, and named scopes as functions.
 *
 * @author  Michael Murray
 */
public class JavaScriptPrintWriter extends CodePrintWriterAdapter {
	/**
	 * Creates a writer of JavaScript to the given output.
	 *
	 * @param p the output to write to
	 */
	public JavaScriptPrintWriter(PrintWriter p) {
		super(p, new JavaScriptLanguageOperations());
	}

	@Override
	public void println(ExpressionAssignment<?> v) {
		String assignment = v.getDestination().getExpression(getLanguage()) + " = " + toString(v.getExpression()) + ";";
		p.println(v.isDeclaration() ? "var " + assignment : assignment);
	}

	@Override
	public void println(Method m) {
		p.println(toString(m) + ";");
	}

	/**
	 * Begins a scope: a named scope is written as a function, which takes no parameters, and
	 * an unnamed one as a block.
	 */
	@Override
	public void beginScope(String name, OperationMetadata metadata, Accessibility access,
						   List<ArrayVariable<?>> arguments, List<Variable<?, ?>> parameters) {
		p.println(name == null ? "{" : "function " + name + "() {");
	}

	@Override
	public void endScope() { p.println("}"); }

	/**
	 * Returns the JavaScript for an expression, writing a method call with its member and
	 * arguments.
	 *
	 * @param e the expression
	 * @return the JavaScript
	 */
	protected String toString(Expression<?> e) {
		if (e instanceof Method m) {
			String member = m.getMember() == null ? "" : m.getMember() + ".";
			return member + m.getName() + "(" + toString(m.getArguments()) + ")";
		}

		return e.getExpression(getLanguage());
	}

	/**
	 * Returns the JavaScript for a list of method arguments, separated by commas.
	 *
	 * @param arguments the arguments
	 * @return the JavaScript
	 */
	protected String toString(List<Expression<?>> arguments) {
		return arguments.stream().map(this::toString).collect(Collectors.joining(", "));
	}
}
