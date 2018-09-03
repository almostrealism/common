package io.almostrealism.code.test;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Method;
import io.almostrealism.code.Variable;
import io.almostrealism.js.JavaScriptPrintWriter;

public class CodePrintWriterTest {
	@Test
	public void methodTest() {
		CodePrintWriter p = new JavaScriptPrintWriter(new PrintWriter(System.out));
		
		Map<String, Variable> args = new HashMap<>();
		args.put("arg", new Variable<Integer>("in", Integer.class, 1));
		
		p.beginScope("test");
		p.println(new Variable<>("v", Integer.class, new Method<>(null, "func", Arrays.asList("arg"), args)));
		p.endScope();
		
		
		args.put("arg", new Variable<>("test", Integer.class, new Method<>(null, "test", Arrays.asList(), new HashMap())));
		
		p.beginScope("next");
		p.println(new Variable<>("v", Integer.class, new Method<>(null, "func", Arrays.asList("arg"), args)));
		p.endScope();
		
		p.flush();
	}
}
