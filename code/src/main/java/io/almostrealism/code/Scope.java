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

package io.almostrealism.code;

import io.almostrealism.code.Argument.Expectation;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;

import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Named;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Nameable;
import io.almostrealism.relation.Sortable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A {@link Scope} is the container for {@link Variable}s, {@link Method}s, and other {@link Scope}s.
 *
 * @param <T>  The type of the value returned by this {@link Scope}.
 */
public class Scope<T> extends ArrayList<Scope<T>> implements ParameterizedGraph<Scope<T>, T>, Parent<Scope<T>>, Nameable {
	public static final boolean enableInlining = false;

	private String name;
	private final List<Variable<?, ?>> variables;
	private final List<Method> methods;
	private final List<Scope> required;

	private List<Argument<?>> arguments;
	private boolean embedded;

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
	public List<Variable<?, ?>> getVariables() { return variables; }

	/**
	 * @return  The {@link Method}s in this {@link Scope}.
	 */
	public List<Method> getMethods() { return methods; }

	/**
	 * @return  The inner {@link Scope}s contained by this {@link Scope}.
	 */
	@Override
	public List<Scope<T>> getChildren() { return this; }

	public boolean isEmbedded() { return embedded; }

	public void setEmbedded(boolean embedded) { this.embedded = embedded; }

	/**
	 * @return  The {@link Scope}s that are required by this {@link Scope}.
	 */
	public List<Scope> getRequiredScopes() { return required; }

	public <A> List<Supplier<Evaluable<? extends A>>> getInputs() {
		return arguments(arg -> ((ArrayVariable) arg.getVariable()).getProducer());
	}

	public <A> List<Argument<? extends A>> getArguments() {
		List<Argument<? extends A>> result = arguments(arg -> (Argument<? extends A>) arg);
		sortArguments(result);
		return result;
	}

	public <A> List<ArrayVariable<? extends A>> getArgumentVariables() {
		List<ArrayVariable<? extends A>> result = arguments(arg -> (ArrayVariable<? extends A>) arg.getVariable());
		sortArguments(result);
		return result;
	}

	protected List<Argument<?>> arguments() { return arguments(Function.identity()); }

	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();

		if (arguments == null) {
			args.addAll(extractArgumentDependencies(variables));
			sortArguments(args);

			this.stream()
					.map(Scope::arguments)
					.flatMap(List::stream)
					.forEach(args::add);

			methods.stream()
					.map(Method::getArguments)
					.flatMap(List::stream)
					.filter(v -> v instanceof InstanceReference)
					.map(v -> ((InstanceReference<?>) v).getReferent())
					.forEach(v -> args.add(new Argument((Variable) v, Expectation.EVALUATE_AHEAD)));
		} else {
			args.addAll(arguments.stream()
					.map(arg -> (Argument<?>) arg)
					.collect(Collectors.toList()));
		}

		getRequiredScopes().stream()
				.map(Scope::arguments)
				.flatMap(List::stream)
				.forEach(v -> args.add((Argument<?>) v));

		List<Argument<?>> result = args.stream()
				.map(Delegated::getRootDelegate)
				.collect(Collectors.toList());

		result = removeDuplicateArguments(result);

		return result.stream().map(mapper).collect(Collectors.toList());
	}

	public void convertArgumentsToRequiredScopes() {
		if (this.arguments != null) {
			return;
		}

		List<Argument<?>> args = new ArrayList<>();

		getArguments()
				.stream()
				.map(arg -> {
					Variable<?, ?> var = arg.getVariable();

					if (!(var.getProducer() instanceof Computation)
							|| var.getProducer() instanceof DynamicProducer
							|| var.getProducer() instanceof ProducerArgumentReference) {
						return Collections.singletonList(arg);
					}

					Scope s = ((Computation) var.getProducer()).getScope();
					s.convertArgumentsToRequiredScopes();

					// Attempt to simply include the scope
					// inline, otherwise introduce a method
					if (tryAbsorb(s)) {
						return s.getArguments();
					} else {
						s.setEmbedded(true);
						required.add(s);
						methods.add(s.call());
						return Collections.emptyList();
					}
				})
				.map(list -> (List<Argument<?>>) list)
				.flatMap(List::stream)
				.forEach(args::add);

		this.arguments = args;
	}

	public Method<?> call() {
		List<Expression> args = getArgumentVariables().stream()
				.map(a -> new InstanceReference((Variable) a))
				.collect(Collectors.toList());
		return new Method(Double.class, getName(), args);
	}

	/**
	 * Attempt to inline the specified {@link Scope}. This will only be
	 * successful if the specified {@link Scope} contains nothing but
	 * variable assignments; any {@link Scope} with child {@link Scope}s
	 * or method references will not be inlined.
	 *
	 * @return  True if the {@link Scope} was inlined, false otherwise.
	 */
	public boolean tryAbsorb(Scope<T> s) {
		if (!enableInlining) return false;

		if (!s.getChildren().isEmpty()) return false;
		if (!s.getMethods().isEmpty()) return false;

		IntStream.range(0, s.getVariables().size()).forEach(i -> variables.add(i, s.getVariables().get(i)));
		return true;
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

	public static <T extends Sortable> void sortArguments(List<T> arguments) {
		if (arguments != null) {
			Comparator<T> c = Comparator.comparing(v -> v == null ? Integer.MAX_VALUE : v.getSortHint());
			// c = c.thenComparing(v -> v == null ? "" : v.getName());
			arguments.sort(c);
		}
	}

	public static <T> List<Argument<? extends T>> removeDuplicateArguments(List<Argument<? extends T>> args) {
		return Named.removeDuplicates(args, (a, b) -> {
			if (a.getExpectation() == Expectation.WILL_EVALUATE) {
				return a;
			} else if (b.getExpectation() == Expectation.WILL_EVALUATE) {
				return b;
			}

			return a;
		});
	}


	private static List<Argument<?>> extractArgumentDependencies(Collection<Variable<?, ?>> vars) {
		return extractArgumentDependencies(vars, true);
	}

	private static List<Argument<?>> extractArgumentDependencies(Collection<Variable<?, ?>> vars, boolean top) {
		List<Argument<?>> args = new ArrayList<>();

		v: for (Variable<?, ?> var : vars) {
			Variable v = var;

			if (v == null) continue v;
			if (v.getDelegate() != null) v = v.getDelegate();

			if (v instanceof ArrayVariable) {
				Argument<?> arg = new Argument(v, top ? Expectation.WILL_EVALUATE : Expectation.EVALUATE_AHEAD);

				if (!args.contains(arg)) {
					args.add(arg);
				}
			}

			if (var.getExpression() instanceof InstanceReference) {
				Argument<?> arg = new Argument(((InstanceReference) var.getExpression()).getReferent(),
												Expectation.EVALUATE_AHEAD);
				if (arg.getVariable() instanceof ArrayVariable && !args.contains(arg)) {
					args.add(arg);
				}
			}

			extractArgumentDependencies(var.getDependencies(), false).stream()
					.filter(a -> !args.contains(a)).forEach(args::add);
		}

		return args;
	}
}
