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

import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Named;
import io.almostrealism.relation.Provider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class OperationAdapter<T> implements Compactable, NameProvider, Named {
	public static final boolean enableNullInputs = true;

	private static long functionId = 0;

	private String function;

	private List<Supplier<Evaluable<? extends T>>> inputs;
	private List<ArrayVariable<? extends T>> arguments;

	private Map<Supplier<Evaluable>, List<Variable<?>>> variables;
	private List<Supplier<Evaluable>> variableOrder;
	private List<String> variableNames;

	public OperationAdapter(Supplier<Evaluable<? extends T>>... input) {
		setInputs(input);
	}

	public OperationAdapter(ArrayVariable<? extends T>... args) {
		if (args.length > 0) setArguments(Arrays.asList(args));
	}

	public void setFunctionName(String name) { function = name; }

	@Override
	public String getFunctionName() { return function; }

	@Override
	public String getName() { return operationName(null, getClass(), getFunctionName()); }

	public int getArgsCount() { return getArguments().size(); }

	protected void setInputs(Supplier<Evaluable<? extends T>>... input) { setInputs(Arrays.asList(input)); }
	protected void setInputs(List<Supplier<Evaluable<? extends T>>> inputs) { this.inputs = inputs; }

	public List<Supplier<Evaluable<? extends T>>> getInputs() { return inputs; }

	protected void setArguments(List<ArrayVariable<? extends T>> arguments) {
		this.arguments = arguments;
	}

	public List<ArrayVariable<? extends T>> getArguments() {
		Scope.sortArguments(arguments);
		return arguments;
	}

	public ArrayVariable getArgumentForInput(Supplier<Evaluable<? extends T>> input) {
		for (ArrayVariable arg : getArguments()) {
			if (arg != null && arg.getProducer() == input) {
				return arg;
			}
		}

		return null;
	}

	public void init() {
		if (function == null) setFunctionName(functionName(getClass()));
		purgeVariables();
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
	 * are not Computation based (especially considering the introduction of
	 * ExplicitScope), so when that process is over one of the two roles this
	 * methods plays wont exist and it will be clear what it is for.
	 */
	public abstract Scope compile();

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
		getArguments().stream()
				.map(Variable::getProducer)
				.map(arg -> arg instanceof OperationAdapter ? (OperationAdapter) arg : null)
				.filter(Objects::nonNull)
				.forEach(OperationAdapter::postCompile);
	}

	public void addVariable(Variable v) {
		List<Variable<?>> existing = variables.get(v.getProducer());
		if (existing == null) {
			existing = new ArrayList<>();
			variables.put(v.getProducer(), existing);
		}

		if (!variableNames.contains(v.getName())) {
			variableNames.add(v.getName());

			if (!existing.contains(v)) existing.add(v);
			if (!variableOrder.contains(v.getProducer())) variableOrder.add(v.getProducer());
		} else if (containsVariable(v)) {
			if (!existing.contains(v)) {
				System.out.println("Variable name was already used with a different producer");
			}
		} else {
			System.out.println("WARN: Variable name was reused");
		}
	}

	public boolean containsVariable(Variable v) {
		return getVariables().contains(v);
	}

	public List<Variable<?>> getVariables() {
		return variableOrder.stream()
				.map(variables::get)
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}

	public void absorbVariables(Supplier peer) {
		if (peer instanceof OperationAdapter) {
			absorbVariables((OperationAdapter) peer);
		} else if (peer.get() instanceof Provider) {
			return; // Providers do not have variables to absorb
		} else {
			throw new IllegalArgumentException(peer + " is not a OperationAdapter");
		}
	}

	public void absorbVariables(OperationAdapter peer) {
		if (peer != null) peer.getVariables().forEach(v -> addVariable((Variable) v));
	}

	public void purgeVariables() {
		this.variables = new HashMap<>();
		this.variableOrder = new ArrayList<>();
		this.variableNames = new ArrayList<>();
	}

	protected void removeDuplicateArguments() { setArguments(Scope.removeDuplicateArguments(getArguments())); }

	@Override
	public synchronized void compact() {
		getInputs().stream().filter(p -> p instanceof Compactable).forEach(p -> ((Compactable) p).compact());
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
