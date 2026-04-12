/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.scope.Variable;
import org.almostrealism.io.PrintWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Renders a {@link Scope} tree to a string of generated source code.
 *
 * <p>Applies the active {@link ScopeSettings} simplification configuration
 * and writes the resulting code through a {@link CodePrintWriter} produced by a
 * language-specific factory function.</p>
 */
public class ScopeEncoder implements Function<Scope, String>, PrintWriter {
	/**
	 * Maximum number of characters that may be accumulated in a single encoding run.
	 * Exceeding this limit throws an {@link IllegalArgumentException}.
	 */
	public static final int MAX_CHARACTERS = 512 * 1024 * 1024;

	/** Factory that produces the language-specific {@link CodePrintWriter}. */
	private final Function<PrintWriter, CodePrintWriter> generator;

	/** The accessibility level applied to all encoded declarations. */
	private final Accessibility access;

	/** The language-specific writer that formats individual statements. */
	private final CodePrintWriter output;

	/** The accumulated output buffer. */
	private StringBuilder result;

	/** Names of functions already written in a previous encoding pass, used to avoid duplicates. */
	private final List<String> functionsWritten;

	/**
	 * Constructs a scope encoder with an empty function-written list.
	 *
	 * @param generator a factory function that creates the language-specific writer
	 * @param access    the accessibility level for encoded declarations
	 */
	public ScopeEncoder(Function<PrintWriter, CodePrintWriter> generator,
						Accessibility access) {
		this(generator, access, new ArrayList<>());
	}

	/**
	 * Constructs a scope encoder that shares a function-written list with a parent encoder,
	 * preventing the same function from being emitted twice.
	 *
	 * @param generator        a factory function that creates the language-specific writer
	 * @param access           the accessibility level for encoded declarations
	 * @param functionsWritten a mutable list of already-written function names
	 */
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
		} else if (scope.getStatements().size() > ScopeSettings.maxStatements) {
			throw new IllegalArgumentException();
		}

		functionsWritten.add(scope.getName());

		this.result = new StringBuilder();

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
	public void moreIndent() { }

	@Override
	public void lessIndent() { }

	@Override
	public void print(String s) { append(s); }

	@Override
	public void println(String s) { append(s); println(); }

	@Override
	public void println() { append("\n"); }

	/**
	 * Appends a string to the output buffer, enforcing the {@link #MAX_CHARACTERS} limit.
	 *
	 * @param s the string to append
	 * @throws IllegalArgumentException if appending would exceed the maximum character limit
	 */
	protected void append(String s) {
		if (result.length() + s.length() > MAX_CHARACTERS) {
			throw new IllegalArgumentException("Cannot encode Scope with more than " + MAX_CHARACTERS + " characters");
		}

		result.append(s);
	}
}
