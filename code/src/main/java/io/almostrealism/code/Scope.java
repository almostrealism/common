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

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;

import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Nameable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A {@link Scope} is the container for {@link Variable}s, {@link Method}s, and other {@link Scope}s.
 *
 * @param <T>  The type of the value returned by this {@link Scope}.
 */
public class Scope<T> extends ArrayList<Scope<T>> implements ParameterizedGraph<Scope<T>, T>, Parent<Scope<T>>, Nameable {
	private String name;
	private List<Variable<?>> variables;
	private List<Method> methods;
	private List<Scope> required;

	private List inputs;

	/**
	 * Creates an empty {@link Scope}.
	 */
	public Scope() {
		this.variables = new ArrayList<>();
		this.methods = new ArrayList<>();
		this.required = new ArrayList<>();
	}

	/**
	 * Creates an empty {@link Scope} with the specified name.
	 */
	public Scope(String name) {
		this();
		setName(name);
	}

	@Override
	public String getName() { return name; }

	@Override
	public void setName(String name) { this.name = name; }

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
	 * @return  The {@link Scope}s that are required by this {@link Scope}.
	 */
	public List<Scope> getRequiredScopes() { return required; }

	public <A> List<ArrayVariable<? extends A>> getFinalArguments() {
		List<ArrayVariable<? extends A>> args = new ArrayList<>();

		if (inputs == null) {
			args.addAll(getArguments());
		} else {
			inputs.forEach(in -> {
				if (in instanceof Variable) {
					args.add((ArrayVariable<? extends A>) in);
				} else {
					args.addAll(((Scope) in).getArguments());
				}
			});
		}

		sortArguments(args);
		return args;
	}

	public <A> List<ArrayVariable<? extends A>> getArguments() {
		List<ArrayVariable<? extends A>> args = new ArrayList<>();

		extractArgumentDependencies(variables).forEach(args::add);
		methods.stream()
				.map(Method::getArguments)
				.flatMap(List::stream)
				.filter(v -> v instanceof InstanceReference)
				.map(v -> ((InstanceReference<?>) v).getReferent())
				.forEach(v -> args.add((ArrayVariable) v));
		this.stream()
				.map(Scope::getArguments)
				.flatMap(List::stream)
				.forEach(arg -> args.add((ArrayVariable<A>) arg));

		List<ArrayVariable<? extends A>> result = args.stream()
				.map(ArrayVariable::getRootDelegate).collect(Collectors.toList());
		result = removeDuplicateArguments(result);
		sortArguments(result);
		return result;
	}

	public void convertArgumentsToRequiredScopes() {
		inputs = getArguments().stream()
				.map(arg -> {
					if (arg.getProducer() instanceof Computation) {
						Scope s = ((Computation) arg.getProducer()).getScope();
						required.add(s);
						List<Expression> args = new ArrayList<>();
						s.getArguments().forEach(a -> args.add(new InstanceReference((Variable) a)));
						methods.add(new Method(null, s.getName(), args));
						return s;
					} else {
						return arg;
					}
				}).collect(Collectors.toList());

	}

	/**
	 * Writes the {@link Method}s and {@link Variable}s for this {@link Scope}
	 * to the specified {@link CodePrintWriter}, then writes any {@link Scope}s
	 * included as children of this {@link Scope}, in that order.
	 *
	 * @param w  {@link CodePrintWriter} to use for encoding the {@link Scope}.
	 */
	public void write(CodePrintWriter w) {
		for (Method m : getMethods()) { w.println(m); }
		for (Variable v : getVariables()) { w.println(v); }
		for (Scope s : getChildren()) { s.write(w); }
		w.flush();
	}

	public static <T> List<ArrayVariable<? extends T>> removeDuplicateArguments(List<ArrayVariable<? extends T>> arguments) {
		List<ArrayVariable<? extends T>> args = new ArrayList<>();
		arguments.stream().filter(Objects::nonNull).forEach(args::add);

		List<String> names = new ArrayList<>();
		Iterator<ArrayVariable<? extends T>> itr = args.iterator();

		while (itr.hasNext()) {
			ArrayVariable arg = itr.next();
			if (names.contains(arg.getName())) {
				itr.remove();
			} else {
				names.add(arg.getName());
			}
		}

		return args;
	}

	public static <T> void sortArguments(List<ArrayVariable<? extends T>> arguments) {
		if (arguments != null) {
			Comparator<ArrayVariable> c = Comparator.comparing(v -> v == null ? Integer.MAX_VALUE : v.getSortHint());
			// c = c.thenComparing(v -> v == null ? "" : v.getName());
			Collections.sort(arguments, c);
		}
	}

	private static List<ArrayVariable> extractArgumentDependencies(Collection<Variable<?>> vars) {
		List<ArrayVariable> args = new ArrayList<>();

		v:  for (Variable<?> var : vars) {
			if (var == null) continue v;

			if (var instanceof ArrayVariable && !args.contains(var)) {
				args.add((ArrayVariable) var);
			}

			if (var.getExpression() instanceof InstanceReference &&
					!args.contains(((InstanceReference) var.getExpression()).getReferent())) {
				if (((InstanceReference) var.getExpression()).getReferent() instanceof ArrayVariable) {
					args.add((ArrayVariable) ((InstanceReference) var.getExpression()).getReferent());
				}
			}

			extractArgumentDependencies(var.getDependencies()).stream()
					.filter(a -> !args.contains(a)).forEach(args::add);
		}

		return args;
	}
}
