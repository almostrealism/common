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

import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.NameProvider;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.Statement;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.expression.ArrayDeclaration;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.kernel.KernelIndexChild;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.KernelTree;
import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Sortable;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.uml.Nameable;
import io.almostrealism.uml.Named;
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
 * A {@link Scope} is the primary container for executable code elements in the Almost Realism
 * computation framework. It serves as a hierarchical structure that holds {@link Statement}s,
 * {@link Method}s, {@link Variable}s, and nested child {@link Scope}s.
 *
 * <p>{@link Scope} is central to the code generation pipeline, representing a unit of computation
 * that can be compiled into native code for hardware acceleration. Scopes can be nested to create
 * complex control flow structures, and they support conditional execution through the
 * {@link #addCase(Expression, Scope)} mechanism.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><b>Hierarchical Structure</b>: Scopes can contain child scopes, creating a tree structure
 *       that represents nested code blocks</li>
 *   <li><b>Argument Management</b>: Automatically tracks dependencies and arguments required
 *       for scope execution through {@link #getArguments()} and {@link #getDependencies()}</li>
 *   <li><b>Inlining Support</b>: Can absorb simpler scopes via {@link #tryAbsorb(Scope)} to
 *       reduce method call overhead</li>
 *   <li><b>Code Generation</b>: Writes generated code through {@link #write(CodePrintWriter)}</li>
 *   <li><b>Simplification</b>: Supports expression simplification via {@link #simplify(KernelStructureContext, int)}</li>
 *   <li><b>Compute Requirements</b>: Can specify hardware requirements (CPU, GPU, etc.) for execution</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a scope for a computation
 * Scope<Double> scope = new Scope<>("myComputation", metadata);
 *
 * // Add statements
 * Expression<Double> result = scope.declareDouble("result", someExpression);
 * scope.assign(outputVar, result);
 *
 * // Add conditional logic
 * scope.addCase(condition, trueBranchScope, falseBranchScope);
 *
 * // Write generated code
 * scope.write(codePrintWriter);
 * }</pre>
 *
 * <h2>Scope Lifecycle</h2>
 * <ol>
 *   <li>Creation: Scope is instantiated with a name and optional metadata</li>
 *   <li>Population: Statements, variables, and child scopes are added</li>
 *   <li>Argument Resolution: Dependencies are resolved via {@link #convertArgumentsToRequiredScopes(KernelStructureContext)}</li>
 *   <li>Simplification: Expressions are simplified for optimal code generation</li>
 *   <li>Code Generation: The scope is written to a {@link CodePrintWriter}</li>
 * </ol>
 *
 * @param <T> the type of value returned by this {@link Scope}, typically representing
 *            the output type of the computation
 *
 * @see Statement
 * @see Variable
 * @see Argument
 * @see Cases
 * @see Method
 * @see CodePrintWriter
 */
public class Scope<T> extends ArrayList<Scope<T>>
		implements Fragment, KernelTree<Scope<T>>,
					OperationInfo, Signature, Nameable,
					ConsoleFeatures {
	/**
	 * Global flag controlling whether scope inlining is enabled.
	 * When true, simple scopes can be absorbed into their parent scope
	 * to reduce method call overhead during code generation.
	 */
	public static final boolean enableInlining = true;

	/**
	 * Console instance for logging scope-related messages.
	 * Child of the root console for hierarchical logging.
	 */
	public static final Console console = Console.root().child();

	/**
	 * Global flag for verbose logging. Controlled by the AR_SCOPE_VERBOSE
	 * environment variable. When enabled, additional debug information
	 * is logged during scope processing.
	 */
	public static boolean verbose = SystemUtils.isEnabled("AR_SCOPE_VERBOSE").orElse(false);

	/**
	 * Optional timing listener for performance profiling of scope operations.
	 * When set, records duration metrics for simplification and replacement processing.
	 */
	public static ScopeTimingListener timing;

	/** The unique name identifier for this scope. */
	private String name;

	/**
	 * Reference index counter used for generating unique variable names
	 * during expression replacement processing.
	 */
	private int refIdx;

	/** Metadata describing this scope's operation for profiling and debugging. */
	private OperationMetadata metadata;

	/**
	 * List of compute requirements specifying hardware constraints
	 * (e.g., GPU, specific memory requirements) for this scope's execution.
	 */
	private List<ComputeRequirement> requirements;

	/** The list of executable statements contained in this scope. */
	private final List<Statement<?>> statements;

	/**
	 * Parameters declared within this scope, representing local variables
	 * that are not passed as arguments from parent scopes.
	 */
	private final List<Variable<?, ?>> parameters;

	/**
	 * Expression assignments (variable declarations and assignments) in this scope.
	 * @deprecated This field is scheduled for removal; use statements instead.
	 */
	private final List<ExpressionAssignment<?>> variables; // TODO  Remove

	/**
	 * Method references called within this scope.
	 * @deprecated This field is scheduled for removal.
	 */
	private final List<Method> methods; // TODO  Remove

	/** Performance metrics to be recorded during scope execution. */
	private final List<Metric> metrics;

	/**
	 * List of scopes that must be executed before this scope.
	 * These represent dependencies that have been converted from arguments.
	 */
	private final List<Scope> required;

	/**
	 * Set of kernel index children for parallel execution contexts.
	 * @deprecated Use of kernel children is being phased out.
	 */
	private Set<KernelIndexChild> kernelChildren;

	/**
	 * Cached list of arguments for this scope. Populated during
	 * argument resolution via {@link #convertArgumentsToRequiredScopes(KernelStructureContext)}.
	 */
	private List<Argument<?>> arguments;

	/**
	 * Flag indicating whether this scope is embedded within another scope
	 * as a required dependency rather than as a direct child.
	 */
	private boolean embedded;

	/**
	 * Creates an empty {@link Scope} with no name or metadata.
	 * <p>All internal collections (statements, parameters, variables, methods,
	 * metrics, and required scopes) are initialized as empty lists.</p>
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
	 * <p><b>Note:</b> This constructor does not include {@link OperationMetadata},
	 * which is recommended for proper profiling and debugging. Consider using
	 * {@link #Scope(String, OperationMetadata)} instead.</p>
	 *
	 * @param name the unique identifier for this scope, used in code generation
	 *             and for referencing this scope from other scopes
	 */
	public Scope(String name) {
		this();
		setName(name);
	}

	/**
	 * Creates an empty {@link Scope} with the specified name and metadata.
	 * <p>This is the preferred constructor as it ensures proper metadata
	 * tracking for profiling and debugging purposes.</p>
	 *
	 * @param name     the unique identifier for this scope
	 * @param metadata operation metadata describing this scope's purpose and origin;
	 *                 a copy is made to prevent external modification
	 * @throws IllegalArgumentException if metadata is null
	 */
	public Scope(String name, OperationMetadata metadata) {
		this();
		setName(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	/**
	 * Returns the unique name identifier for this scope.
	 *
	 * @return the scope name, or null if not set
	 */
	@Override
	public String getName() { return name; }

	/**
	 * Sets the unique name identifier for this scope.
	 *
	 * @param name the scope name to set
	 */
	@Override
	public void setName(String name) { this.name = name; }

	/**
	 * Returns the operation metadata for this scope.
	 * <p>If no metadata has been set, creates a default metadata instance
	 * with name "Unknown".</p>
	 *
	 * @return the operation metadata, never null
	 */
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

	/**
	 * Sets the operation metadata for this scope.
	 *
	 * @param metadata the operation metadata to associate with this scope
	 */
	public void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

	/**
	 * Returns the list of compute requirements for this scope.
	 *
	 * @return the compute requirements, or null if none specified
	 */
	public List<ComputeRequirement> getComputeRequirements() { return requirements; }

	/**
	 * Sets the compute requirements for this scope.
	 *
	 * @param requirements the list of hardware/compute constraints
	 */
	public void setComputeRequirements(List<ComputeRequirement> requirements) { this.requirements = requirements; }

	/**
	 * Returns the list of statements contained in this scope.
	 * <p>The returned list is mutable and can be modified to add or remove statements.</p>
	 *
	 * @return the mutable list of statements
	 */
	public List<Statement<?>> getStatements() { return statements; }

	/**
	 * Returns the list of parameters (local variables) declared in this scope.
	 * <p>Parameters are variables that are declared within the scope rather than
	 * being passed in as arguments.</p>
	 *
	 * @return the mutable list of parameters
	 */
	public List<Variable<?, ?>> getParameters() {
		return parameters;
	}

	/**
	 * Adds a child scope to this scope.
	 *
	 * @param child the child scope to add; must not be null
	 * @return true (as specified by {@link ArrayList#add(Object)})
	 * @throws IllegalArgumentException if child is null
	 */
	@Override
	public boolean add(Scope<T> child) {
		if (child == null) {
			throw new IllegalArgumentException();
		}

		return super.add(child);
	}

	/**
	 * Adds a conditional case with a single statement.
	 * <p>Creates a new scope containing the statement and adds it as a conditional case.</p>
	 *
	 * @param condition the boolean condition that must be true for the statement to execute
	 * @param statement the statement to execute when the condition is true
	 * @return the created scope containing the statement
	 */
	public Scope<T> addCase(Expression<Boolean> condition, Statement<?> statement) {
		Scope<T> scope = new Scope<>();
		scope.getStatements().add(statement);
		return addCase(condition, scope, null);
	}

	/**
	 * Adds a conditional case with a scope to execute when the condition is true.
	 *
	 * @param condition the boolean condition that must be true for the scope to execute
	 * @param scope     the scope to execute when the condition is true
	 * @return the provided scope
	 */
	public Scope<T> addCase(Expression<Boolean> condition, Scope<T> scope) {
		return addCase(condition, scope, null);
	}

	/**
	 * Adds a conditional case with optional alternative scope (if-else pattern).
	 * <p>If the condition can be statically evaluated to a constant boolean value,
	 * only the appropriate branch is added (optimization). Otherwise, a {@link Cases}
	 * structure is created to represent the conditional at runtime.</p>
	 *
	 * @param condition the boolean condition to evaluate
	 * @param scope     the scope to execute when the condition is true
	 * @param altScope  the scope to execute when the condition is false (may be null)
	 * @return the provided scope (the "true" branch)
	 */
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

	/**
	 * Returns the expression assignments in this scope.
	 *
	 * @return the list of expression assignments
	 * @deprecated Use {@link #getStatements()} instead; this field is scheduled for removal.
	 */
	@Deprecated
	public List<ExpressionAssignment<?>> getVariables() { return variables; }

	/**
	 * Returns the method references in this scope.
	 *
	 * @return the list of methods
	 * @deprecated This field is scheduled for removal.
	 */
	@Deprecated
	public List<Method> getMethods() { return methods; }

	/**
	 * Returns the child scopes contained within this scope.
	 * <p>This scope extends {@link ArrayList}, so this method returns {@code this}.</p>
	 *
	 * @return this scope as a list of child scopes
	 */
	@Override
	public List<Scope<T>> getChildren() { return this; }

	/**
	 * Returns the kernel index children for parallel execution contexts.
	 *
	 * @return the set of kernel index children, or null if not set
	 * @deprecated Use of kernel children is being phased out.
	 */
	@Deprecated
	public Set<KernelIndexChild> getKernelChildren() { return kernelChildren; }

	/**
	 * Sets the kernel index children for parallel execution contexts.
	 *
	 * @param kernelChildren the set of kernel index children
	 * @deprecated Use of kernel children is being phased out.
	 */
	@Deprecated
	public void setKernelChildren(Set<KernelIndexChild> kernelChildren) { this.kernelChildren = kernelChildren; }

	/**
	 * Returns the performance metrics associated with this scope.
	 *
	 * @return the mutable list of metrics
	 */
	public List<Metric> getMetrics() { return metrics; }

	/**
	 * Checks whether this scope is embedded within another scope as a required dependency.
	 *
	 * @return true if this scope is embedded, false otherwise
	 */
	public boolean isEmbedded() { return embedded; }

	/**
	 * Sets whether this scope is embedded within another scope as a required dependency.
	 *
	 * @param embedded true to mark this scope as embedded
	 */
	public void setEmbedded(boolean embedded) { this.embedded = embedded; }

	/**
	 * Checks whether this scope includes the specified compute requirement.
	 *
	 * @param requirement the compute requirement to check for
	 * @return true if the requirement is included, false if not included or if no requirements are set
	 */
	public boolean includesComputeRequirement(ComputeRequirement requirement) {
		if (requirements == null) return false;
		return requirements.contains(requirement);
	}

	/**
	 * Returns the neighboring scopes of the given node in the kernel tree.
	 * <p>This method is not supported for {@link Scope}.</p>
	 *
	 * @param node the node to find neighbors for
	 * @return never returns normally
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Collection<Scope<T>> neighbors(Scope<T> node) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the scopes that are required by this scope as direct dependencies.
	 * <p>Required scopes must be executed before this scope. The returned list
	 * is mutable and can be modified to add or remove requirements.</p>
	 *
	 * @return the mutable list of required scopes
	 */
	public List<Scope> getRequiredScopes() { return required; }

	/**
	 * Returns all scopes required by this scope and its descendants.
	 * <p>This method recursively collects required scopes from all child scopes.</p>
	 *
	 * @return an immutable view of all required scopes in the hierarchy
	 */
	public List<Scope> getAllRequiredScopes() {
		List<Scope> all = new ArrayList<>(required);
		stream().map(Scope::getAllRequiredScopes).flatMap(List::stream).forEach(all::add);
		return all;
	}

	/**
	 * Returns the input producers for this scope's arguments.
	 * <p>Extracts the producer suppliers from all argument variables.</p>
	 *
	 * @param <A> the type of value produced by the inputs
	 * @return list of supplier-wrapped evaluables for each argument
	 */
	public <A> List<Supplier<Evaluable<? extends A>>> getInputs() {
		return getArgumentVariables().stream()
				.map(var -> (Supplier) var.getProducer())
				.map(sup -> (Supplier<Evaluable<? extends A>>) sup).collect(Collectors.toList());
	}

	/**
	 * Returns all dependencies of this scope as arguments.
	 * <p>Dependencies include all variables that this scope depends on,
	 * sorted by their sort hints.</p>
	 *
	 * @param <A> the type of value held by the dependencies
	 * @return sorted list of dependency arguments
	 */
	public <A> List<Argument<? extends A>> getDependencies() {
		List<Argument<? extends A>> result = arguments(arg -> (Argument<? extends A>) arg);
		sortArguments(result);
		return result;
	}

	/**
	 * Returns the arguments required for this scope's execution.
	 * <p>Arguments are the external inputs that must be provided when executing
	 * this scope. This method filters dependencies to include only those that
	 * are {@link ArrayVariable}s and resolves delegate variables to their roots.</p>
	 *
	 * @param <A> the type of value held by the arguments
	 * @return sorted list of arguments with duplicates removed
	 * @throws IllegalArgumentException if a delegate variable is not an ArrayVariable
	 */
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

	/**
	 * Returns the array variables representing this scope's arguments.
	 * <p>Extracts the underlying {@link ArrayVariable}s from the arguments.</p>
	 *
	 * @param <A> the type of value held by the variables
	 * @return list of array variables for this scope's arguments
	 */
	public <A> List<ArrayVariable<? extends A>> getArgumentVariables() {
		return getArguments().stream()
				.map(Argument::getVariable)
				.filter(Objects::nonNull)
				.map(v -> (ArrayVariable<? extends A>) v)
				.collect(Collectors.toList());
	}

	/**
	 * Declares an integer variable within this scope and initializes it with the given value.
	 * <p>Adds an {@link ExpressionAssignment} statement to this scope's statements.</p>
	 *
	 * @param name  the name of the variable to declare
	 * @param value the initial value expression
	 * @return a reference expression to the declared variable
	 */
	public Expression<Integer> declareInteger(String name, Expression<? extends Number> value) {
		Expression<Integer> i = new StaticReference(Integer.class, name);
		getStatements().add(new ExpressionAssignment<>(true, i, (Expression<Integer>) value));
		return i;
	}

	/**
	 * Declares a double-precision variable within this scope and initializes it with the given value.
	 * <p>Adds an {@link ExpressionAssignment} statement to this scope's statements.</p>
	 *
	 * @param name  the name of the variable to declare
	 * @param value the initial value expression
	 * @return a reference expression to the declared variable
	 */
	public Expression<Double> declareDouble(String name, Expression<? extends Number> value) {
		Expression<Double> i = new StaticReference(Double.class, name);
		getStatements().add(new ExpressionAssignment<>(true, i, (Expression) value));
		return i;
	}

	/**
	 * Declares an array variable within this scope.
	 * <p>Adds an {@link ArrayDeclaration} statement and returns a reference to the array.
	 * The array is configured with offset disabled.</p>
	 *
	 * @param name the name of the array variable
	 * @param size the size expression for the array; must evaluate to a value greater than 0
	 * @return an {@link ArrayVariable} reference to the declared array
	 * @throws IllegalArgumentException if the size is less than 1
	 */
	public ArrayVariable<?> declareArray(String name, Expression<Integer> size) {
		if (size.intValue().orElse(1) <= 0) {
			throw new IllegalArgumentException("Array size cannot be less than 1");
		}

		getStatements().add(new ArrayDeclaration(Double.class, name, size));

		ArrayVariable v = new ArrayVariable<>(Double.class, name, size);
		v.setDisableOffset(true);
		return v;
	}

	/**
	 * Creates an assignment statement within this scope.
	 * <p>Adds an {@link ExpressionAssignment} statement that assigns the source expression
	 * to the destination expression.</p>
	 *
	 * @param <V>  the type of value being assigned
	 * @param dest the destination expression (left-hand side)
	 * @param src  the source expression (right-hand side)
	 * @return the created assignment statement
	 */
	public <V> ExpressionAssignment<V> assign(Expression<V> dest, Expression<?> src) {
		ExpressionAssignment<V> assignment = new ExpressionAssignment<>(dest, (Expression) src);
		getStatements().add(assignment);
		return assignment;
	}

	/**
	 * Returns the arguments for this scope using the identity mapper.
	 *
	 * @return list of arguments
	 */
	protected List<Argument<?>> arguments() { return arguments(Function.identity()); }

	/**
	 * Collects and maps all arguments for this scope.
	 * <p>Arguments are collected from statements, variables, child scopes, methods, metrics,
	 * and required scopes. If arguments have already been computed and cached, the cached
	 * version is returned.</p>
	 *
	 * @param <A>    the type to map arguments to
	 * @param mapper function to transform each argument
	 * @return list of mapped arguments with duplicates removed
	 * @throws UnsupportedOperationException if any argument is null
	 */
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

	/**
	 * Converts argument dependencies that are backed by {@link Computation}s into required scopes.
	 * <p>This method performs a depth-first traversal to ensure that dependencies are converted
	 * in the correct order. Arguments backed by computations are either inlined (if simple enough)
	 * or added as required scopes with method calls.</p>
	 *
	 * <p>The conversion process:</p>
	 * <ol>
	 *   <li>Recursively process child scopes first (depth-first)</li>
	 *   <li>For each dependency, check if it's backed by a {@link Computation}</li>
	 *   <li>If the computation can be inlined, absorb it; otherwise, add as a required scope</li>
	 *   <li>Cache the resulting arguments list for future calls</li>
	 * </ol>
	 *
	 * @param context the kernel structure context for scope generation
	 * @return list of scope names that were converted (for deduplication in parent scopes)
	 */
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

	/**
	 * Creates a method call expression for invoking this scope.
	 * <p>Constructs a {@link Method} reference that can be used to call this scope
	 * as a function, passing all argument variables and any additional parameters.</p>
	 *
	 * @param parameters additional parameters to pass to the method call
	 * @return a method reference for calling this scope
	 */
	public Method<?> call(Expression<?>... parameters) {
		List<Expression> args = new ArrayList<>();
		getArgumentVariables().stream()
				.map(a -> new InstanceReference((Variable) a))
				.forEach(args::add);
		for (Expression<?> p : parameters) { args.add(p); }
		return new Method(Double.class, getName(), args);
	}

	/**
	 * Determines whether this scope can be inlined into a parent scope.
	 * <p>Subclasses can override this method to indicate that they cannot be inlined
	 * due to structural constraints or other requirements.</p>
	 *
	 * @return true if this scope can be inlined, false otherwise
	 */
	public boolean isInlineable() {
		return true;
	}

	/**
	 * Attempts to inline the specified scope into this scope.
	 * <p>Inlining is only successful if the specified scope contains only simple
	 * assignments without declarations. Scopes with child scopes, method references,
	 * or variable declarations cannot be inlined.</p>
	 *
	 * <p>When successful, the variables and statements from the specified scope
	 * are prepended to this scope's collections.</p>
	 *
	 * @param s the scope to attempt to inline
	 * @return true if the scope was successfully inlined, false otherwise
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
				StaticReference ref = new StaticReference(c.getAliasType(), c.getName());
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

	/**
	 * Creates a simplified version of this scope by applying expression simplification
	 * to all contained elements.
	 * <p>This method recursively simplifies child scopes, required scopes, methods,
	 * statements, and variables. It also processes expression replacements to extract
	 * common subexpressions.</p>
	 *
	 * @param context the kernel structure context providing simplification rules
	 * @param depth   the current recursion depth (used for nested simplification)
	 * @return a new simplified scope with the same structure but optimized expressions
	 */
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


		Set<KernelIndexChild> kernelChildren = new HashSet<>();
		if (getKernelChildren() != null) kernelChildren.addAll(getKernelChildren());
		scope.setKernelChildren(kernelChildren);

		return scope;
	}

	/**
	 * Generates a new scope with the given children while preserving this scope's configuration.
	 * <p>Copies the name, metadata, reference index, and compute requirements to the new scope.</p>
	 *
	 * @param children the child scopes to include in the generated scope
	 * @return a new scope with the specified children
	 */
	@Override
	public Parent<Scope<T>> generate(List<Scope<T>> children) {
		Scope<T> scope = new Scope<>(getName(), getMetadata());
		scope.refIdx = refIdx;

		scope.setComputeRequirements(getComputeRequirements());
		scope.getChildren().addAll(children);
		return scope;
	}

	/**
	 * Returns the signature string for this scope.
	 * <p>Uses the metadata signature if available, otherwise falls back to the scope name.</p>
	 *
	 * @return the signature string identifying this scope
	 */
	@Override
	public String signature() {
		String signature = getMetadata().getSignature();
		if (signature == null) signature = getName();

		return signature;
	}

	/**
	 * Compares this scope to another object for equality.
	 * <p>Two scopes are considered equal if they have the same name.</p>
	 *
	 * @param o the object to compare with
	 * @return true if the objects are equal, false otherwise
	 */
	@Override
	public boolean equals(Object o) {
		return Objects.equals(getName(), ((Scope) o).getName());
	}

	/**
	 * Returns a hash code for this scope based on its name.
	 *
	 * @return the hash code value
	 */
	@Override
	public int hashCode() {
		return getName() == null ? 0 : getName().hashCode();
	}

	/**
	 * Returns a description of this scope (its name).
	 *
	 * @return the scope name
	 */
	@Override
	public String describe() { return getName(); }

	/**
	 * Returns the console instance for logging.
	 *
	 * @return the scope's console
	 */
	@Override
	public Console console() { return console; }

	/**
	 * Generates kernel index children from the given statements.
	 *
	 * @param <T>    the type of statements
	 * @param values the statements to scan for kernel index children
	 * @return list of kernel index children found in the statements
	 * @deprecated Use of kernel children is being phased out.
	 */
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

	/**
	 * Processes expression replacements to extract common subexpressions.
	 * <p>This method identifies frequently-used expressions and replaces them with
	 * variable references to avoid redundant computation. The process iterates
	 * until no more replacements can be made or the maximum replacement limit is reached.</p>
	 *
	 * <p>The algorithm:</p>
	 * <ol>
	 *   <li>Identify expressions that are used but not yet declared</li>
	 *   <li>Create variable declarations for these expressions</li>
	 *   <li>Replace occurrences of the expressions with variable references</li>
	 *   <li>Repeat until no new replacement opportunities exist</li>
	 * </ol>
	 *
	 * @param statements         the list of statements to process
	 * @param replacementTargets supplier of expressions that are candidates for replacement
	 * @return the processed statements with common subexpressions extracted
	 */
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

	/**
	 * Creates a simplification operator for statements within the given context.
	 * <p>The returned operator applies simplification to each statement and records
	 * timing information if a timing listener is configured.</p>
	 *
	 * @param <S>     the type of statement
	 * @param context the kernel structure context for simplification
	 * @return a unary operator that simplifies statements
	 */
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

	/**
	 * Sorts a list of arguments by their sort hints, then by name.
	 * <p>Arguments without sort hints are placed at the end. Arguments with
	 * null or empty names are sorted before named arguments when hints are equal.</p>
	 *
	 * @param <T>       the type of argument
	 * @param arguments the list of arguments to sort (modified in place)
	 * @return true if the list was sorted, false if the list was null
	 */
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

	/**
	 * Removes duplicate arguments from a list, preferring arguments with {@code WILL_EVALUATE} expectation.
	 * <p>When duplicates are found (by name), the argument with {@code WILL_EVALUATE} expectation
	 * is preferred if present; otherwise, the first argument is kept.</p>
	 *
	 * @param <T>  the type of value held by the arguments
	 * @param args the list of arguments potentially containing duplicates
	 * @return a new list with duplicates removed
	 */
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

	/**
	 * Extracts argument dependencies from a collection of variables.
	 * <p>This is a convenience method that calls {@link #extractArgumentDependencies(Collection, boolean)}
	 * with {@code top = true}.</p>
	 *
	 * @param vars the variables to extract dependencies from
	 * @return list of arguments representing the dependencies
	 */
	protected static List<Argument<?>> extractArgumentDependencies(Collection<Variable<?, ?>> vars) {
		return extractArgumentDependencies(vars, true);
	}

	/**
	 * Extracts argument dependencies from a collection of variables.
	 * <p>Creates {@link Argument} instances for each variable with appropriate expectations.
	 * Top-level variables get {@code WILL_EVALUATE}, nested dependencies get {@code EVALUATE_AHEAD}.
	 * Recursively processes variable dependencies.</p>
	 *
	 * @param vars the variables to extract dependencies from
	 * @param top  true if these are top-level variables, false for nested dependencies
	 * @return list of arguments representing the dependencies
	 */
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
