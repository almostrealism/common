package io.almostrealism.code;

import org.almostrealism.io.PrintWriter;

import java.util.ArrayList;
import java.util.function.Function;

public class ScopeEncoder implements Function<Scope, String>, PrintWriter {
	private CodePrintWriter output;
	private StringBuffer result;

	private int indent = 0;

	public ScopeEncoder(Function<PrintWriter, CodePrintWriter> generator) {
		output = generator.apply(this);
	}

	@Override
	public String apply(Scope scope) {
		this.result = new StringBuffer();

		scope.getRequiredScopes().stream().map(this).forEach(result::append);

		output.beginScope(scope.getName(), scope.getFinalArguments());
		scope.write(output);
		output.endScope();

		return result.toString();
	}

	@Override
	public void moreIndent() { indent++; }

	@Override
	public void lessIndent() { indent--; }

	@Override
	public void print(String s) { result.append(s); }

	@Override
	public void println(String s) { result.append(s); println(); }

	@Override
	public void println() { result.append("\n"); }
}
