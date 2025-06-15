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

package io.almostrealism.scope;

import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.code.Computation;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.NameProvider;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.ArrayDeclaration;
import io.almostrealism.kernel.KernelIndexChild;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTree;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.Parent;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;

import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Named;
import io.almostrealism.uml.Nameable;
import io.almostrealism.relation.Sortable;
import io.almostrealism.uml.Signature;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A {@link Scope} is the container for {@link Statement}s,
 * {@link Method}s, and other {@link Scope}s.
 *
 * @param <T>  The type of the value returned by this {@link Scope}.
 */
public class Scope<T> extends ArrayList<Scope<T>>
		implements Fragment, KernelTree<Scope<T>>,
					OperationInfo, Signature, Nameable,
					ConsoleFeatures {
	public static final boolean enableInlining = true;
	public static final Console console = Console.root().child();

	public static boolean verbose = SystemUtils.isEnabled("AR_SCOPE_VERBOSE").orElse(false);
	public static ScopeTimingListener timing;

	private String name;
	private int refIdx;
	private OperationMetadata metadata;
	private List<ComputeRequirement> requirements;

	private final List<Statement<?>> statements;
	private final List<Variable<?, ?>> parameters;

	private final List<ExpressionAssignment<?>> variables; // TODO  Remove
	private final List<Method> methods; // TODO  Remove

	private final List<Metric> metrics;
	private final List<Scope> required;
	private Set<KernelIndexChild> kernelChildren;

	private List<Argument<?>> arguments;
	private boolean embedded;

	/**
	 * Creates an empty {@link Scope}.
	 */
	public Scope() {
		this.statements = new ArrayList<>();
		this.parameters = new ArrayList<>();
		this.variables = new ArrayList<>();
		this.methods = new ArrayList<>();
		this.metrics = new ArrayList<>();
		this.required = new ArrayList<>();
	}

	/**
	 * Creates an empty {@link Scope} with the specified name.
	 * This method, without providing {@link OperationMetadata},
	 * should be avoided in favor of including metadata.
	 */
	public Scope(String name) {
		this();
		setName(name);
	}

	/**
	 * Creates an empty {@link Scope} with the specified name.
	 * and {@link OperationMetadata}.
	 */
	public Scope(String name, OperationMetadata metadata) {
		this();
		setName(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	@Override
	public String getName() { return name; }

	@Override
	public void setName(String name) { this.name = name; }

	@Override
	public OperationMetadata getMetadata() {
		if (metadata == null) {
			metadata = new OperationMetadata("Unknown", null);
		}

		// It might be useful for the metadata to include the children of this Scope,
		// but this will hide the children of the operation which produced the Scope
		// metadata.setChildren(getChildren().stream().map(Scope::getMetadata).collect(Collectors.toList()));
		return metadata;
	}

	public void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

	public List<ComputeRequirement> getComputeRequirements() { return requirements; }
	public void setComputeRequirements(List<ComputeRequirement> requirements) { this.requirements = requirements; }

	public List<Statement<?>> getStatements() { return statements; }

	public List<Variable<?, ?>> getParameters() {
		return parameters;
	}

	@Override
	public boolean add(Scope<T> child) {
		if (child == null) {
			throw new IllegalArgumentException();
		}

		return super.add(child);
	}

	public Scope<T> addCase(Expression<Boolean> condition, Statement<?> statement) {
		Scope<T> scope = new Scope<>();
		scope.getStatements().add(statement);
		return addCase(condition, scope, null);
	}

	public Scope<T> addCase(Expression<Boolean> condition, Scope<T> scope) {
		return addCase(condition, scope, null);
	}

	public Scope<T> addCase(Expression<Boolean> condition, Scope<T> scope, Scope<T> altScope) {
		Optional<Boolean> v = condition.getSimplified().booleanValue();
		if (v.isPresent()) {
			if (v.get()) {
				add(scope);
			} else if (altScope != null) {
				add(altScope);
			}

			return scope;
		}

		Cases cases = new Cases<>();
		cases.addCase(condition, scope);
		if (altScope != null) cases.add(altScope);
		add(cases);
		return scope;
	}

	/** @return  The {@link ExpressionAssignment}s in this {@link Scope}. */
	@Deprecated
	public List<ExpressionAssignment<?>> getVariables() { return variables; }

	/** @return  The {@link Method}s in this {@link Scope}. */
	@Deprecated
	public List<Method> getMethods() { return methods; }

	/** @return  The inner {@link Scope}s contained by this {@link Scope}. */
	@Override
	public List<Scope<T>> getChildren() { return this; }

	@Deprecated
	public Set<KernelIndexChild> getKernelChildren() { return kernelChildren; }

	@Deprecated
	public void setKernelChildren(Set<KernelIndexChild> kernelChildren) { this.kernelChildren = kernelChildren; }

	public List<Metric> getMetrics() { return metrics; }

	public boolean isEmbedded() { return embedded; }
	public void setEmbedded(boolean embedded) { this.embedded = embedded; }

	public boolean includesComputeRequirement(ComputeRequirement requirement) {
		if (requirements == null) return false;
		return requirements.contains(requirement);
	}

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

	public Expression<Integer> declareInteger(String name, Expression<? extends Number> value) {
		Expression<Integer> i = new StaticReference(Integer.class, name);
		getStatements().add(new ExpressionAssignment<>(true, i, (Expression<Integer>) value));
		return i;
	}

	public Expression<Double> declareDouble(String name, Expression<? extends Number> value) {
		Expression<Double> i = new StaticReference(Double.class, name);
		getStatements().add(new ExpressionAssignment<>(true, i, (Expression) value));
		return i;
	}

	public ArrayVariable<?> declareArray(NameProvider np, String name, Expression<Integer> size) {
		if (size.intValue().orElse(1) <= 0) {
			throw new IllegalArgumentException("Array size cannot be less than 1");
		}

		getStatements().add(new ArrayDeclaration(Double.class, name, size));

		ArrayVariable v = new ArrayVariable<>(np, Double.class, name, size);
		v.setDisableOffset(true);
		return v;
	}

	public <V> ExpressionAssignment<V> assign(Expression<V> dest, Expression<?> src) {
		ExpressionAssignment<V> assignment = new ExpressionAssignment<>(dest, (Expression) src);
		getStatements().add(assignment);
		return assignment;
	}

	protected List<Argument<?>> arguments() { return arguments(Function.identity()); }

	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();

		if (arguments == null) {
			args.addAll(extractArgumentDependencies(statements
					.stream().flatMap(e -> e.getDependencies().stream()).collect(Collectors.toList())));
			args.addAll(extractArgumentDependencies(variables
					.stream().flatMap(e -> e.getDependencies().stream()).collect(Collectors.toList())));
			sortArguments(args);

			List<String> declaredArrays = getStatements().stream()
					.map(s -> s instanceof ArrayDeclaration ? (ArrayDeclaration) s : null)
					.filter(Objects::nonNull)
					.map(ArrayDeclaration::getName)
					.collect(Collectors.toList());

			List<String> declaredParameters = getParameters().stream()
					.map(Variable::getName)
					.collect(Collectors.toList());

			this.stream()
					.map(Scope::arguments)
					.flatMap(List::stream)
					.filter(v -> !declaredArrays.contains(v.getVariable().getName()))
					.filter(v -> !declaredParameters.contains(v.getVariable().getName()))
					.forEach(args::add);

			methods.stream()
					.map(Method::getArguments)
					.flatMap(List::stream)
					.filter(v -> v instanceof InstanceReference)
					.map(v -> ((InstanceReference<?, ?>) v).getReferent())
					.forEach(v -> args.add(new Argument((Variable) v, Expectation.EVALUATE_AHEAD)));

			metrics.stream()
					.map(Metric::getArguments)
					.flatMap(List::stream)
					.map(v -> v.getDependencies())
					.flatMap(List::stream)
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

		if (args.stream().anyMatch(Objects::isNull)) {
			throw new UnsupportedOperationException();
		}

		return removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}

	public List<String> convertArgumentsToRequiredScopes(KernelStructureContext context) {
		if (this.arguments != null) {
			return Collections.emptyList();
		}

		// Because of the fact that child scopes have dependencies which are included in
		// the dependencies of the parent scope, this conversion must be depth first.
		// This gives the scope where the dependency originated the opportunity to
		// converted it into a dependency, rather than converting everything into a
		// required scope at the top level which will often not preserve the expected
		// order of execution of those requirements, unless somehow the dependency list
		// is coincidentally in the correct order

		List<String> convertedScopes = new ArrayList<>();
		stream().map(tScope -> tScope.convertArgumentsToRequiredScopes(context))
				.flatMap(List::stream).forEach(convertedScopes::add);

		List<Argument<?>> args = new ArrayList<>();
		List<Computation> convertedComputations = new ArrayList<>();

		getDependencies()
				.stream()
				.map(arg -> {
					Variable<?, ?> var = arg.getVariable();

					// Argument references may be Computations, but they cannot
					// be converted to required scopes because they refer directly
					// to data passed into evaluation of the compiled Scope
					if (var.getProducer() instanceof ProducerArgumentReference) {
						return Collections.singletonList(arg);
					}

					Computation computation = null;

					// If the argument variable is produced by a computation, or is
					// produced by something that delegates to a computation, it
					// should be converted to a required scope
					// DynamicProducers are excluded, however, because it is not
					// generally possible to generate a Scope from a producer
					// that is powered by a Function, as most are
					if (var.getProducer() instanceof Computation &&
							!(var.getProducer() instanceof DynamicProducer)) {
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
					}

					// If the computation has already been converted,
					// skip over it
					if (convertedComputations.contains(computation)) {
						return Collections.emptyList();
					}

					// If the variable in fact refers to this Scope, it should not be
					// recursively made into a requirement of itself
					// Function names are globally unique, making this detection possible
					// but there is perhaps a better way than string comparison eventually
					if (getName() != null && computation instanceof NameProvider && getName().equals(((NameProvider) computation).getFunctionName())) {
						return Collections.singletonList(arg);
					}

					Scope<?> s = computation.getScope(context);
					if (s.getName() != null && s.getName().equals(getName())) {
						return Collections.singletonList(arg);
					}

					// If the computation produces a Scope that was already
					// converted by a child scope, skip over it
					if (convertedScopes.contains(s.getName())) {
						return Collections.singletonList(arg);
					}

					// If the Scope contains this Scope, it should not be
					// recursively made into a requirement of itself
					if (s.contains(this)) {
						return Collections.singletonList(arg);
					}

					// If the Scope has ComputeRequirements that are not
					// included in this Scope, it should not be included
					// as it may need to target a different computing device
					if (s.getComputeRequirements() != null &&
							s.getComputeRequirements().stream().anyMatch(r -> !includesComputeRequirement(r))) {
						return Collections.singletonList(arg);
					}

					// Recursively convert the required Scope's arguments
					// into required scopes themselves
					// s.convertArgumentsToRequiredScopes();
					convertedScopes.addAll(s.convertArgumentsToRequiredScopes(context));

					// Attempt to simply include the scope
					// inline, otherwise introduce a method
					if (tryAbsorb(s)) {
						convertedComputations.add(computation);
						convertedScopes.add(s.getName());
						return s.getDependencies();
					} else {
						s.setEmbedded(true);
						required.add(s);
						methods.add(s.call());
						convertedComputations.add(computation);
						convertedScopes.add(s.getName());

						// Dependencies will be covered by inspecting the required scope,
						// so no need to add them here
						return Collections.emptyList();
					}
				})
				.map(list -> (List<Argument<?>>) list)
				.flatMap(List::stream)
				.forEach(args::add);

		this.arguments = args;
		return convertedScopes;
	}

	public Method<?> call(Expression<?>... parameters) {
		List<Expression> args = new ArrayList<>();
		getArgumentVariables().stream()
				.map(a -> new InstanceReference((Variable) a))
				.forEach(args::add);
		for (Expression<?> p : parameters) { args.add(p); }
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
	public boolean tryAbsorb(Scope<?> s) {
		if (!enableInlining) return false;

		if (!s.isInlineable()) return false;
		if (!s.getChildren().isEmpty()) return false;
		if (!s.getMethods().isEmpty()) return false;
		if (s.getVariables().stream().anyMatch(ExpressionAssignment::isDeclaration)) return false;
		if (s.getStatements().stream()
				.map(v -> v instanceof ExpressionAssignment ? (ExpressionAssignment) v : null)
				.filter(Objects::nonNull).anyMatch(ExpressionAssignment::isDeclaration)) {
			return false;
		}

		IntStream.range(0, s.getVariables().size()).forEach(i -> variables.add(i, s.getVariables().get(i)));
		IntStream.range(0, s.getStatements().size()).forEach(i -> statements.add(i, s.getStatements().get(i)));
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

		if (getKernelChildren() != null) {
			for (KernelIndexChild c : getKernelChildren()) {
				StaticReference ref = new StaticReference(Integer.class, c.getName());
				w.println(new ExpressionAssignment(true, ref, c));
			}
		}

		for (Method m : getMethods()) { w.println(m); }
		for (Statement s : getStatements()) { w.println(s); }
		for (ExpressionAssignment<?> v : getVariables()) { w.println(v); }
		for (Scope s : getChildren()) { s.write(w); }
		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	@Override
	public Scope<T> simplify(KernelStructureContext context, int depth) {
		Scope<T> scope = (Scope<T>) generate(getChildren()
				.stream().map(s -> s.simplify(context, depth + 1)).collect(Collectors.toList()));
		scope.getRequiredScopes().addAll(getRequiredScopes()
				.stream().map(s -> s.simplify(context, depth + 1)).collect(Collectors.toList()));
		scope.getParameters().addAll(getParameters());

		UnaryOperator simplification = simplification(context);
		scope.getMethods().addAll((List) getMethods()
				.stream().map(simplification).collect(Collectors.toList()));

		scope.getStatements().addAll(processReplacements((List) getStatements()
						.stream().map(simplification).collect(Collectors.toList()),
				() -> Optional.ofNullable(ExpressionCache.getCurrent())
						.filter(Predicate.not(ExpressionCache::isEmpty))
						.map(ExpressionCache::getFrequentExpressions)
						.orElse(Collections.emptyList())));

		scope.getVariables().addAll((List) getVariables()
				.stream().map(simplification).collect(Collectors.toList()));
		scope.getMetrics().addAll(getMetrics());

		List<KernelIndexChild> kernelChildren = new ArrayList<>();
		if (getKernelChildren() != null) kernelChildren.addAll(getKernelChildren());
		kernelChildren.addAll(generateKernelChildren(scope.getStatements()));
		kernelChildren.addAll(generateKernelChildren(scope.getVariables()));
		scope.setKernelChildren(kernelChildren.stream().map(KernelIndexChild::renderAlias).collect(Collectors.toSet()));
		return scope;
	}

	@Override
	public Parent<Scope<T>> generate(List<Scope<T>> children) {
		Scope<T> scope = new Scope<>(getName(), getMetadata());
		scope.refIdx = refIdx;

		scope.setComputeRequirements(getComputeRequirements());
		scope.getChildren().addAll(children);
		return scope;
	}

	@Override
	public boolean equals(Object o) {
		return Objects.equals(getName(), ((Scope) o).getName());
	}

	@Override
	public int hashCode() {
		return getName() == null ? 0 : getName().hashCode();
	}

	@Override
	public String describe() { return getName(); }

	@Override
	public Console console() { return console; }

	@Deprecated
	protected <T extends Statement> List<KernelIndexChild> generateKernelChildren(List<T> values) {
		List<KernelIndexChild> kernelChildren = new ArrayList<>();

		for (T value : values) {
			if (value instanceof ExpressionAssignment) {
				((ExpressionAssignment) value).getExpression().children()
						.filter(c -> c instanceof KernelIndexChild)
						.forEach(c -> kernelChildren.add((KernelIndexChild) c));
			}
		}

		return kernelChildren;
	}

	protected List<Statement<?>> processReplacements(List<Statement<?>> statements,
													 Supplier<List<Expression<?>>> replacementTargets) {
		if (!ScopeSettings.enableReplacements) return statements;

		long start = System.nanoTime();

		try {
			Set<Expression<?>> processed = new HashSet<>();
			List<Statement<?>> declarations = new ArrayList<>();
			Map<StaticReference, Expression<?>> replacements = new HashMap<>();

			Set<Expression<?>> targets = new HashSet<>(replacementTargets.get());

			while (!targets.isEmpty() && replacements.size() < ScopeSettings.getMaximumReplacements()) {
				if (verbose) log("Processing " + targets.size() + " replacement targets");
				boolean updated = false;

				// Replace all targets which are used, but not already declared
				r: for (Expression<?> e : targets) {
					if (replacements.size() >= ScopeSettings.getMaximumReplacements()) {
						break r;
					}

					boolean inUse = statements.stream()
							.filter(ExpressionAssignment.class::isInstance)
							.map(s -> (ExpressionAssignment) s)
							.map(ExpressionAssignment::getExpression)
							.anyMatch(exp -> exp.contains(e));
					boolean alreadyDeclared = statements.stream()
							.filter(ExpressionAssignment.class::isInstance)
							.map(s -> (ExpressionAssignment) s)
							.filter(ExpressionAssignment::isDeclaration)
							.map(ExpressionAssignment::getExpression)
							.anyMatch(exp -> exp.equals(e));

					if (inUse && !alreadyDeclared) {
						StaticReference ref = new StaticReference<>(e.getType(), getName() + "_" + refIdx++);
						declarations.add(new ExpressionAssignment(true, ref, e));
						replacements.put(ref, e);
						updated = true;
					}

					// Record the target, as it
					// should not be visited again
					processed.add(e);
				}

				// If any replacements were declared, update all
				// the statements to include them
				if (updated) {
					List<Statement<?>> next = new ArrayList<>();

					for (Statement<?> s : statements) {
						if (s instanceof ExpressionAssignment) {
							ExpressionAssignment assignment = (ExpressionAssignment) s;

							for (StaticReference r : replacements.keySet()) {
								Expression<?> e = replacements.get(r);
								if (assignment.getExpression().contains(e)) {
									assignment = new ExpressionAssignment(
											assignment.isDeclaration(),
											assignment.getDestination(),
											assignment.getExpression().replace(e, r));
								}
							}

							next.add(assignment);
						} else {
							next.add(s);
						}
					}

					// The process will be repeated, but with the
					// updated statements and any new targets that
					// may have been identified in the process
					statements = next;
				}

				// Reset the targets and prepare to review
				// any new replacement opportunities that
				// have not already been reviewed
				targets.clear();
				replacementTargets.get().stream()
						.filter(Predicate.not(processed::contains))
						.forEach(targets::add);
			}

			// If no replacements were made, return the statements
			if (declarations.isEmpty()) return statements;

			// Otherwise, combine the declarations with the updated statements
			List<Statement<?>> result = new ArrayList<>();
			result.addAll(declarations);
			result.addAll(statements);
			return result;
		} finally {
			if (timing != null) {
				timing.recordDuration(getMetadata(), getMetadata(),
						"processReplacements", System.nanoTime() - start);
			}
		}
	}

	private <S extends Statement<S>> UnaryOperator<S> simplification(KernelStructureContext context) {
		return t -> {
			long start = System.nanoTime();

			OperationMetadata metadata = context instanceof OperationInfo ?
					((OperationInfo) context).getMetadata() : getMetadata();

			try {
				return t.simplify(context);
			} finally {
				if (timing != null) {
					timing.recordDuration(metadata, getMetadata(),
							"simplify", System.nanoTime() - start);
				}
			}
		};
	}

	public static <T extends Argument<?>> boolean sortArguments(List<T> arguments) {
		if (arguments != null) {
			Comparator<T> c = Comparator.comparing(v -> Optional.ofNullable(v)
					.map(Sortable::getSortHint).orElse(Integer.MAX_VALUE));
			c = c.thenComparing(v -> v == null || v.getName() == null ? "" : v.getName());
			arguments.sort(c);
			return true;
		}

		return false;
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
//			if (!(var instanceof Array) && var.getExpression() instanceof InstanceReference) {
//				arg = new Argument((Variable) ((InstanceReference) var.getExpression()).getReferent(),
//												Expectation.EVALUATE_AHEAD);
//				if (!args.contains(arg)) {
//					args.add(arg);
//				}
//			}

			// Recursive dependencies are computed
			extractArgumentDependencies(var.getDependencies(), false).stream()
					.filter(a -> !args.contains(a)).forEach(args::add);
		}

		return args;
	}
}
