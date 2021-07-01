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

package io.almostrealism.code.test;

import java.util.ArrayList;
import java.util.List;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintStreamPrintWriter;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Method;
import io.almostrealism.code.Variable;
import org.almostrealism.util.JavaScriptPrintWriter;

public class CodePrintWriterTest {
	// @Test
	public void methodTest() {
		CodePrintWriter p = new JavaScriptPrintWriter(new PrintStreamPrintWriter(System.out));
		
		List<Expression<?>> args = new ArrayList<>();
		args.add(new Expression<>(Double.class, Hardware.getLocalHardware().stringForDouble(1)));
		
		p.beginScope("test", new ArrayList<>(), Accessibility.EXTERNAL);
		p.println(new Variable<>("v", new Method<>(Scalar.class, null, "func", args)));
		p.endScope();

		args = new ArrayList<>();
		args.add(new Method<>(Scalar.class, null, "test", new ArrayList<>()));
		
		p.beginScope("next", new ArrayList<>(), Accessibility.EXTERNAL);
		p.println(new Variable<>("v", new Method<>(Scalar.class, null, "func", args)));
		p.endScope();
		
		p.flush();
	}
}
