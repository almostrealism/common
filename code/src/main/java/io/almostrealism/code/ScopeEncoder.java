package io.almostrealism.code;

import org.almostrealism.io.PrintWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class ScopeEncoder implements Function<Scope, String>, PrintWriter {
	private final Function<PrintWriter, CodePrintWriter> generator;
	private final Accessibility access;

	private final CodePrintWriter output;
	private StringBuffer result;

	private final List<String> functionsWritten;

	private int indent = 0;

	public ScopeEncoder(Function<PrintWriter, CodePrintWriter> generator,
						Accessibility access) {
		this(generator, access, new ArrayList<>());
	}

	protected ScopeEncoder(Function<PrintWriter, CodePrintWriter> generator,
						   Accessibility access,
						   List<String> functionsWritten) {
		this.generator = generator;
		this.access = access;
		this.output = generator.apply(this);
		this.functionsWritten = functionsWritten;
	}

	@Override
	public String apply(Scope scope) {
		if (functionsWritten.contains(scope.getName())) {
			return null;
		}

		functionsWritten.add(scope.getName());

		this.result = new StringBuffer();

		scope.getAllRequiredScopes().stream()
				.map(new ScopeEncoder(generator, Accessibility.INTERNAL, functionsWritten))
				.filter(Objects::nonNull)
				.forEach(result::append);

		output.beginScope(scope.getName(), scope.getArgumentVariables(), access);
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
