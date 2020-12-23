package io.almostrealism.code.test;

import java.util.ArrayList;
import java.util.List;

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.PrintStreamPrintWriter;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Method;
import io.almostrealism.code.Variable;
import org.almostrealism.js.JavaScriptPrintWriter;

public class CodePrintWriterTest {
	// @Test
	public void methodTest() {
		CodePrintWriter p = new JavaScriptPrintWriter(new PrintStreamPrintWriter(System.out));
		
		List<Expression<?>> args = new ArrayList<>();
		args.add(new Expression<>(Double.class, Hardware.getLocalHardware().stringForDouble(1)));
		
		p.beginScope("test", new ArrayList<>());
		p.println(new Variable<>("v", Scalar.class, new Method<>(null, "func", args)));
		p.endScope();

		args = new ArrayList<>();
		args.add(new Expression<>(new Method<>(null, "test", new ArrayList<>())));
		
		p.beginScope("next", new ArrayList<>());
		p.println(new Variable<>("v", Scalar.class, new Method<>(null, "func", args)));
		p.endScope();
		
		p.flush();
	}
}
