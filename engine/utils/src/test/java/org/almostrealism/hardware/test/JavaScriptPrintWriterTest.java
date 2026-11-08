/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.test;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.scope.Method;
import org.almostrealism.hardware.js.JavaScriptLanguageOperations;
import org.almostrealism.hardware.js.JavaScriptPrintWriter;
import org.almostrealism.io.PrintWriter;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests the JavaScript backend's statements, scopes and language operations.
 */
public class JavaScriptPrintWriterTest extends TestSuiteBase {
	/** What the writer under test has written. */
	private final StringBuilder output = new StringBuilder();

	/** The writer under test, writing to {@link #output}. */
	private final JavaScriptPrintWriter js = new JavaScriptPrintWriter(PrintWriter.of(output::append));

	/**
	 * Returns a reference to a variable of the given name.
	 *
	 * @param name the variable name
	 * @return the reference
	 */
	private static Expression<Integer> variable(String name) {
		return new StaticReference<>(Integer.class, name);
	}

	/** A declaring assignment is written with {@code var}, and a plain one without. */
	@Test(timeout = 30000)
	public void assignments() {
		js.println(new ExpressionAssignment<>(true, variable("x"), variable("y")));
		js.println(new ExpressionAssignment<>(false, variable("x"), variable("z")));
		Assert.assertEquals("var x = y;\nx = z;\n", output.toString());
	}

	/** A method call is written as a statement, with its member and comma-separated arguments. */
	@Test(timeout = 30000)
	public void methodCalls() {
		js.println(new Method<>("gl", "uniform", List.of(variable("location"), variable("value"))));
		js.println(new Method<>("init", List.of()));
		Assert.assertEquals("gl.uniform(location, value);\ninit();\n", output.toString());
	}

	/** A method call used as an argument is written as a nested call. */
	@Test(timeout = 30000)
	public void nestedMethodCall() {
		Method<?> inner = new Method<>("Math", "sqrt", List.of(variable("v")));
		js.println(new ExpressionAssignment<>(true, variable("r"), (Expression) inner));
		Assert.assertEquals("var r = Math.sqrt(v);\n", output.toString());
	}

	/** A named scope is written as a function and an unnamed one as a block. */
	@Test(timeout = 30000)
	public void scopes() {
		js.beginScope("main", null, null, List.of(), List.of());
		js.endScope();
		js.beginScope(null, null, null, List.of(), List.of());
		js.endScope();
		Assert.assertEquals("function main() {\n}\n{\n}\n", output.toString());
	}

	/** JavaScript is untyped, runs one work item, and has its own name for pi. */
	@Test(timeout = 30000)
	public void languageOperations() {
		JavaScriptLanguageOperations lang = new JavaScriptLanguageOperations();
		Assert.assertEquals("Math.PI", lang.pi());
		Assert.assertEquals("0", lang.kernelIndex(0));
		Assert.assertEquals("", lang.nameForType(Double.class));
		Assert.assertEquals("3", new IntegerConstant(3).getExpression(lang));
	}
}
