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

package io.almostrealism.scope;

import io.almostrealism.code.Array;
import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.Computation;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.Tree;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;

import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Named;
import io.almostrealism.relation.Nameable;
import io.almostrealism.relation.Sortable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A {@link Scope} is the container for {@link Variable}s, {@link Method}s, and other {@link Scope}s.
 *
 * @param <T>  The type of the value returned by this {@link Scope}.
 */
public class Scope<T> extends ArrayList<Scope<T>> implements Tree<Scope<T>>, Nameable {
	public static final boolean enableInlining = true;

	private String name;
	private OperationMetadata metadata;
	private final List<Variable<?, ?>> variables;
	private final List<Method> methods;
	private final List<Metric> metrics;
	private final List<Scope> required;

	private List<Argument<?>> arguments;
	private boolean embedded;

	/**
	 * Creates an empty {@link Scope}.
	 */
	public Scope() {
		this.variables = new ArrayList<>();
		this.methods = new ArrayList<>();
		this.metrics = new ArrayList<>();
		this.required = new ArrayList<>();
	}

	/**
	 * Creates an empty {@link Scope} with the specified name.
	 */
	public Scope(String name) {
		this();
		setName(name);
	}

	public Scope(String name, OperationMetadata metadata) {
		this();
		setName(name);
		setMetadata(new OperationMetadata(metadata));
	}

	@Override
	public String getName() { return name; }

	@Override
	public void setName(String name) { this.name = name; }

	public OperationMetadata getMetadata() {
		if (metadata == null) {
			metadata = new OperationMetadata("Unknown", null);
		}

		metadata.setChildren(getChildren().stream().map(Scope::getMetadata).collect(Collectors.toList()));
		return metadata;
	}

	public void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

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

	public List<Metric> getMetrics() { return metrics; }

	public boolean isEmbedded() { return embedded; }

	public void setEmbedded(boolean embedded) { this.embedded = embedded; }

	@Override
	public Collection<Scope<T>> neighbors(Scope<T> node) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the {@link Scope}s that are required by this {@link Scope},
	 * a {@link List} that can be modified to add requirements.
	 */
	public List<Scope> getRequiredScopes() { return required; }

	/**
	 * @return  The {@link Scope}s that are required by this {@link Scope},
	 * and {@link Scope}s it contains, a {@link List} that cannot be modified
	 * to add requirements.
	 */
	public List<Scope> getAllRequiredScopes() {
		List<Scope> all = new ArrayList<>(required);
		stream().map(Scope::getAllRequiredScopes).flatMap(List::stream).forEach(all::add);
		return all;
	}

	public <A> List<Supplier<Evaluable<? extends A>>> getInputs() {
		return getArgumentVariables().stream()
				.map(var -> (Supplier) var.getProducer())
				.map(sup -> (Supplier<Evaluable<? extends A>>) sup).collect(Collectors.toList());
	}

	public <A> List<Argument<? extends A>> getDependencies() {
		List<Argument<? extends A>> result = arguments(arg -> (Argument<? extends A>) arg);
		sortArguments(result);
		return result;
	}

	public <A> List<Argument<? extends A>> getArguments() {
		List<Argument<? extends A>> args = arguments(arg -> {
			if (arg.getVariable().getDelegate() == null) {
				return arg.getVariable() instanceof ArrayVariable ? (Argument<? extends A>) arg : null;
			}

			if (!(arg.getVariable().getRootDelegate() instanceof ArrayVariable)) {
				throw new IllegalArgumentException("Only ArrayVariables can be used as Arguments");
			}

			// TODO  The specified Expectation probably is not accurate
			return new Argument(arg.getVariable().getRootDelegate(), Expectation.EVALUATE_AHEAD);
		});

		args = removeDuplicateArguments(args);
		sortArguments(args);
		return args;
	}

	public <A> List<ArrayVariable<? extends A>> getArgumentVariables() {
		return getArguments().stream()
				.map(Argument::getVariable)
				.filter(Objects::nonNull)
				.map(v -> (ArrayVariable<? extends A>) v)
				.collect(Collectors.toList());
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

			metrics.stream()
					.map(Metric::getArguments)
					.flatMap(List::stream)
					.map(v -> v.getReferent())
					.forEach(v -> args.add(new Argument(v, Expectation.WILL_ALTER)));
		} else {
			args.addAll(arguments.stream()
					.map(arg -> (Argument<?>) arg)
					.collect(Collectors.toList()));
		}

		getRequiredScopes().stream()
				.map(Scope::arguments)
				.flatMap(List::stream)
				.forEach(v -> args.add((Argument<?>) v));

//		List<Argument<?>> result = args.stream()
//				.map(Delegated::getRootDelegate)
//				.collect(Collectors.toList());
//
//		result = removeDuplicateArguments(result);

		return removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}

	public void convertArgumentsToRequiredScopes() {
		if (this.arguments != null) {
			return;
		}

		// Because of the fact that child scopes have dependencies which are included in
		// the dependencies of the parent scope, this conversion must be depth first.
		// This gives the scope where the dependency originated the opportunity to
		// converted it into a dependency, rather than converting everything into a
		// required scope at the top level which will often not preserve the expected
		// order of execution of those requirements, unless somehow the dependency list
		// is coincidentally in the correct order
		forEach(Scope::convertArgumentsToRequiredScopes);

		List<Argument<?>> args = new ArrayList<>();
		List<Computation> convertedComputations = new ArrayList<>();

		getDependencies()
				.stream()
				.map(arg -> {
					Variable<?, ?> var = arg.getVariable();

					// Argument references may be Computations, but they cannot
					// by converted to required scopes because they refer directly
					// to data passed into evaluation of the compiled Scope
					if (var.getProducer() instanceof ProducerArgumentReference) {
						return Collections.singletonList(arg);
					}

					Computation computation = null;

					// If the argument variable is produced by a computation, or is
					// produced by something that delegates to a computation, it
					// should be converted to a required scope
					// DynamicProducers are excluded, however, because its not
					// generally possible to generate a Scope from a producer
					// that is powered by a Function, as most are
					if (var.getProducer() instanceof Computation && !(var.getProducer() instanceof DynamicProducer)) {
						computation = (Computation) var.getProducer();
					} else if (var.getProducer() instanceof Delegated) {
						Delegated delegated = (Delegated) var.getProducer();
						if (delegated.getDelegate() instanceof Computation &&
								!(delegated.getDelegate() instanceof DynamicProducer) &&
								!(delegated.getDelegate() instanceof ProducerArgumentReference)) {
							computation = (Computation) delegated.getDelegate();
						}
					}

					if (computation == null) {
						return Collections.singletonList(arg);
					} else if (convertedComputations.contains(computation)) {
						return Collections.emptyList();
					}

					// If the variable in fact refers to this Scope, it should not be
					// recursively made into a requirement of itself
					// Function names are globally unique, making this detection possible
					// but there is perhaps a better way than string comparison eventually
					if (getName() != null && computation instanceof NameProvider && getName().equals(((NameProvider) computation).getFunctionName())) {
						return Collections.singletonList(arg);
					}

					Scope s = computation.getScope();
					if (s.getName() != null && s.getName().equals(getName())) {
						return Collections.singletonList(arg);
					}

					// Recursively convert the required Scope's arguments
					// into required scopes themselves
					s.convertArgumentsToRequiredScopes();

					// Attempt to simply include the scope
					// inline, otherwise introduce a method
					if (tryAbsorb(s)) {
						convertedComputations.add(computation);
						return s.getDependencies();
					} else {
						s.setEmbedded(true);
						required.add(s);
						methods.add(s.call());
						convertedComputations.add(computation);

						// Dependencies will be covered by inspecting the required scope,
						// so no need to add them here
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
	 * Subclasses can override this method to indicate that they cannot be inlined.
	 */
	public boolean isInlineable() {
		return true;
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

		if (!s.isInlineable()) return false;
		if (!s.getChildren().isEmpty()) return false;
		if (!s.getMethods().isEmpty()) return false;
		if (s.getVariables().stream().anyMatch(v -> v.isDeclaration())) return false;

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
		w.renderMetadata(getMetadata());
		for (Method m : getMethods()) { w.println(m); }
		for (Variable v : getVariables()) { w.println(v); }
		for (Scope s : getChildren()) { s.write(w); }
		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	@Deprecated
	public static Scope verbatim(String code) {
		return new Scope() {
			public void write(CodePrintWriter w) {
				w.println(code);
			}
		};
	}

	public static <T extends Sortable> void sortArguments(List<T> arguments) {
		if (arguments != null) {
			Comparator<T> c = Comparator.comparing(v -> Optional.ofNullable(v).map(Sortable::getSortHint).orElse(Integer.MAX_VALUE));
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

	protected static List<Argument<?>> extractArgumentDependencies(Collection<Variable<?, ?>> vars) {
		return extractArgumentDependencies(vars, true);
	}

	private static List<Argument<?>> extractArgumentDependencies(Collection<Variable<?, ?>> vars, boolean top) {
		List<Argument<?>> args = new ArrayList<>();

		v: for (Variable<?, ?> var : vars) {
			if (var == null) continue v;

			Argument<?> arg = new Argument(var, top ? Expectation.WILL_EVALUATE : Expectation.EVALUATE_AHEAD);
			if (!args.contains(arg)) args.add(arg);

			// When the variable itself is an InstanceReference, the referent is a dependency
			if (!(var instanceof Array) && var.getExpression() instanceof InstanceReference) {
				arg = new Argument((Variable) ((InstanceReference) var.getExpression()).getReferent(),
												Expectation.EVALUATE_AHEAD);
				if (!args.contains(arg)) {
					args.add(arg);
				}
			}

			// Recursive dependencies are computed
			extractArgumentDependencies(var.getDependencies(), false).stream()
					.filter(a -> !args.contains(a)).forEach(args::add);
		}

		return args;
	}
}
