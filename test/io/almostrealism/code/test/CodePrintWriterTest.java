package io.almostrealism.code.test;

import java.util.ArrayList;
import java.util.List;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.io.PrintStreamPrintWriter;
import org.almostrealism.util.StaticProducer;
import org.junit.Test;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Method;
import io.almostrealism.code.Variable;
import io.almostrealism.js.JavaScriptPrintWriter;

public class CodePrintWriterTest {
	@Test
	public void methodTest() {
		CodePrintWriter p = new JavaScriptPrintWriter(new PrintStreamPrintWriter(System.out));
		
		List<Variable> args = new ArrayList<>();
		args.add(new Variable("in", StaticProducer.of(1)));
		
		p.beginScope("test");
		p.println(new Variable<>("v", Scalar.class, new Method<>(null, "func", args)));
		p.endScope();

		args = new ArrayList<>();
		args.add(new Variable<>("test", new Method<>(null, "test", new ArrayList<>())));
		
		p.beginScope("next");
		p.println(new Variable<>("v", Scalar.class, new Method<>(null, "func", args)));
		p.endScope();
		
		p.flush();
	}
}
