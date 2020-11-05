/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.code;

import org.almostrealism.graph.ParameterizedGraph;
import org.almostrealism.graph.Parent;
import org.almostrealism.util.Nameable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link Scope} is the container for {@link Variable}s, {@link Method}s, and other {@link Scope}s.
 *
 * @param <T>  The type of the value returned by this {@link Scope}.
 */
public class Scope<T> extends ArrayList<Scope<T>> implements ParameterizedGraph<Scope<T>, T>, Parent<Scope<T>>, Nameable {
	private String name;
	private List<Variable<?>> variables;
	private List<Method> methods;

	/**
	 * Creates an empty {@link Scope}.
	 */
	public Scope() {
		this.variables = new ArrayList<>();
		this.methods = new ArrayList<>();
	}

	/**
	 * Creates an empty {@link Scope} with the specified name.
	 */
	public Scope(String name) {
		this();
		setName(name);
	}

	public String getName() { return name; }

	public void setName(String name) { this.name = name; }

	public List<Argument> getArguments() {
		List<Argument> args = new ArrayList<>();
		extractArgumentDependencies(variables).forEach(args::add);
		methods.stream()
				.map(Method::getArguments)
				.flatMap(List::stream)
				.filter(v -> v instanceof ArgumentReference)
				.map(v -> ((ArgumentReference) v).getReferent())
				.forEach(v -> args.add((Argument) v));
		this.stream()
				.map(Scope::getArguments)
				.flatMap(List::stream)
				.forEach(args::add);
		sortArguments(args);
		return args;
	}

	private static List<Argument> extractArgumentDependencies(Collection<Variable<?>> vars) {
		List<Argument> args = new ArrayList<>();

		v:  for (Variable<?> var : vars) {
			if (var == null) continue v;

			if (var instanceof Argument && !args.contains(var)) {
				args.add((Argument) var);
			}

			if (var.getExpression() instanceof ArgumentReference &&
					!args.contains(((ArgumentReference) var.getExpression()).getReferent())) {
				args.add(((ArgumentReference) var.getExpression()).getReferent());
			}

			extractArgumentDependencies(var.getDependencies()).stream()
					.filter(a -> !args.contains(a)).forEach(args::add);
		}

		return args;
	}

	/**
	 * @return  The {@link Variable}s in this {@link Scope}.
	 */
	public List<Variable<?>> getVariables() { return variables; }

	/**
	 * @return  The {@link Method}s in this {@link Scope}.
	 */
	public List<Method> getMethods() { return methods; }

	/**
	 * @return  The inner {@link Scope}s contained by this {@link Scope}.
	 */
	@Override
	public List<Scope<T>> getChildren() { return this; }

	/**
	 * Writes the {@link Variable}s and {@link Method}s for this {@link Scope}
	 * to the specified {@link CodePrintWriter}, then writes any {@link Scope}s
	 * included as children of this {@link Scope}.
	 *
	 * @param w  {@link CodePrintWriter} to use for encoding the {@link Scope}.
	 */
	public void write(CodePrintWriter w) {
		for (Variable v : getVariables()) { w.println(v); }
		for (Method m : getMethods()) { w.println(m); }
		for (Scope s : getChildren()) { s.write(w); }
		w.flush();
	}

	public static void sortArguments(List<Argument> arguments) {
		if (arguments != null) {
			Collections.sort(arguments, Comparator.comparing(v -> v == null ? Integer.MAX_VALUE : v.getSortHint()));
		}
	}
}
