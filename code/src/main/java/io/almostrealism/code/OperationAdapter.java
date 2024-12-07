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

package io.almostrealism.code;

import io.almostrealism.expression.InstanceReference;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Parent;
import io.almostrealism.uml.Named;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.util.DescribableParent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class OperationAdapter<T, C> implements
											NameProvider, DescribableParent<C>,
											Destroyable, OperationInfo,
											NamedFunction, Named {

	public static boolean enableFunctionPrefix = false;
	private static long functionId = 0;

	private String function;

	private List<Supplier<Evaluable<? extends T>>> inputs;
	private List<Argument<? extends T>> arguments;
	private boolean sortedArguments;

	private List<ExpressionAssignment<?>> variables;
	private OperationMetadata metadata;

	@SafeVarargs
	public OperationAdapter(Supplier<Evaluable<? extends T>>... input) {
		setInputs(input);
	}

	@SafeVarargs
	public OperationAdapter(Argument<? extends T>... args) {
		if (args.length > 0) setArguments(Arrays.asList(args));
	}

	@Override
	public void setFunctionName(String name) { function = name; }

	@Override
	public String getFunctionName() { return function; }

	@Override
	public String getVariablePrefix() {
		if (enableFunctionPrefix) {
			return getFunctionName();
		} else {
			String f = getFunctionName();
			if (f.contains("_")) f = f.substring(f.lastIndexOf("_"));
			return f;
		}
	}

	protected void setMetadata(OperationMetadata metadata) { this.metadata = metadata; }

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public String getName() { return operationName(null, getClass(), getFunctionName()); }

	public int getArgsCount() { return getArguments().size(); }

	@SafeVarargs
	protected final void setInputs(Supplier<Evaluable<? extends T>>... input) { setInputs(Arrays.asList(input)); }
	protected void setInputs(List<Supplier<Evaluable<? extends T>>> inputs) { this.inputs = inputs; }

	public List<Supplier<Evaluable<? extends T>>> getInputs() { return inputs; }

	protected void setArguments(List<Argument<? extends T>> arguments) {
		this.arguments = arguments;
	}

	public synchronized List<Argument<? extends T>> getArguments() {
		if (!sortedArguments) {
			sortedArguments = Scope.sortArguments(arguments);
		}

		return arguments;
	}

	public synchronized List<ArrayVariable<? extends T>> getArgumentVariables() {
		if (getArguments() == null) return null;

		return getArguments().stream()
				.map(arg -> Optional.ofNullable(arg).map(Argument::getVariable).orElse(null))
				.map(var -> (ArrayVariable<? extends T>) var)
				.collect(Collectors.toList());
	}

	public ArrayVariable getArgumentForInput(Supplier<Evaluable<? extends T>> input) {
		if (getArgumentVariables() == null) {
			throw new IllegalArgumentException(getName() + " is not compiled");
		}

		return getArgumentForInput((List) getArgumentVariables(), (Supplier) input);
	}

	public void resetArguments() { this.arguments = null; }

	public void init() {
		if (function == null) setFunctionName(functionName(getClass()));
		metadata = prepareMetadata(new OperationMetadata(getFunctionName(), getName()));

		purgeVariables();
	}

	protected OperationMetadata prepareMetadata(OperationMetadata metadata) {
		return metadata;
	}

	/**
	 * Presently this serves a dual purpose: to do actual compilation of Scope
	 * from Computation in implementors that facilitate the invocation of a
	 * Computation, but also to do necessary initialization even in cases where
	 * a Computation is not being prepared for invocation. This is likely
	 * adding to the confusion of having a shared parent between the two types
	 * of accelerated operations (those compiling a Computation vs those that
	 * simply execute code). There seems to be no reason to deal with this now,
	 * as there will eventually be no need for accelerated operations which
	 * are not Computation based, so when that process is over one of the two
	 * roles this method plays won't exist, and it will be clear what it is for.
	 */
	public abstract Scope compile();

	public abstract boolean isCompiled();

	/**
	 * Take care of anything necessary after compilation. This may be called
	 * when a parent operation (one that cites this as an argument, for example)
	 * is compiled and the compile method was not called, but some work may
	 * still need to be done. This implementation identifies any arguments that
	 * are {@link OperationAdapter}s and calls their {@link #postCompile()}
	 * method, so it should be delegated to in the case that this method is
	 * overridden to do something else.
	 */
	public void postCompile() {
		getArgumentVariables().stream()
				.map(Variable::getProducer)
				.map(arg -> arg instanceof OperationAdapter ? (OperationAdapter) arg : null)
				.filter(Objects::nonNull)
				.forEach(OperationAdapter::postCompile);
	}

	public void addVariable(Variable<?, ?> v) {
		addVariable(new InstanceReference<>(v).assign(null));
	}

	public void addVariable(ExpressionAssignment<?> v) {
		if (variables == null) {
			variables = new ArrayList<>();
		}

		variables.add(v);
	}

	public boolean containsVariable(ExpressionAssignment<?> v) {
		return getVariables().contains(v);
	}

	public List<ExpressionAssignment<?>> getVariables() { return variables == null ? Collections.emptyList() : variables; }

	public void purgeVariables() { this.variables = null; }

	@Deprecated
	protected synchronized void removeDuplicateArguments() { setArguments(Scope.removeDuplicateArguments(getArguments())); }

	protected void waitFor(Semaphore semaphore) {
		if (semaphore == null) return;
		semaphore.waitFor();
	}

	@Override
	public void destroy() {
		if (getInputs() != null) {
			getInputs().stream().map(in -> in instanceof Producer ? (Producer) in : null)
					.filter(Objects::nonNull)
					.forEach(Producer::destroy);
		}

		resetArguments();
	}

	@Override
	public String description(List<String> children) {
		return Optional.ofNullable(getMetadata()).map(OperationMetadata::getShortDescription).orElse("null") +
				"(" + String.join(", ", children) + ")";
	}

	public static ArrayVariable getArgumentForInput(List<ArrayVariable> vars, Supplier<Evaluable> input) {
		if (input == null) return null;

		// Check for argument variables for which the original producer is
		// the specified input
		Set<ArrayVariable> var = vars.stream()
				.filter(arg -> arg != null && input.equals(arg.getProducer()))
				.collect(Collectors.toSet());
		if (var.size() == 1) return var.iterator().next();
		if (var.size() > 1) {
			throw new IllegalArgumentException("Multiple arguments match input");
		}

		// Additionally, check for variables for which the original producer
		// delegates to the specified input
		var = vars.stream()
				.filter(Objects::nonNull)
				.filter(arg -> arg.getProducer() instanceof Delegated)
				.filter(arg -> input.equals(((Delegated) arg.getProducer()).getDelegate()))
				.collect(Collectors.toSet());
		if (var.size() == 1) return var.iterator().next();
		if (var.size() > 1) {
			throw new IllegalArgumentException("Multiple arguments match input");
		}

		return null;
	}

	protected static String functionName(Class c) {
		String s = c.getSimpleName();
		if (s.length() == 0) {
			s = "anonymous";
		}

		if (s.length() < 2) {
			throw new IllegalArgumentException(c.getName() + " has too short of a simple name to use for a function");
		}

		return "f_" + s.substring(0, 1).toLowerCase() + s.substring(1) + "_" + functionId++;
	}

	public static String operationName(Named named, Class c, String functionName) {
		if (named != null && named.getName() != null) {
			return named.getName();
		}

		String name = c.getSimpleName();
		if (name == null || name.trim().length() <= 0) name = "anonymous";
		if (name.equals("AcceleratedOperation") || name.equals("AcceleratedProducer")) name = functionName;
		return name;
	}
}
