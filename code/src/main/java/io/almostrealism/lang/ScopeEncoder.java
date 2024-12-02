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

package io.almostrealism.lang;

import io.almostrealism.code.Accessibility;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.PrintWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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

		List<ArrayVariable<?>> arrayVariables = new ArrayList<>();
		arrayVariables.addAll(scope.getArgumentVariables());
		scope.getParameters().stream()
				.filter(v -> v instanceof ArrayVariable)
				.forEach(v -> arrayVariables.add((ArrayVariable<?>) v));

		List<Variable<?, ?>> parameters = (List<Variable<?, ?>>) scope.getParameters().stream()
				.filter(v -> !(v instanceof ArrayVariable))
				.collect(Collectors.toList());

		output.beginScope(scope.getName(), scope.getMetadata(), access, arrayVariables, parameters);
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
