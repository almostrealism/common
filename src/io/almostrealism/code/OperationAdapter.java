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

import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Compactable;
import org.almostrealism.util.Named;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class OperationAdapter<T> implements Compactable, NameProvider, Named {
	public static final boolean enableNullInputs = true;

	private static long functionId = 0;

	private String function;

	private List<Argument<? extends T>> arguments;

	private Map<Supplier<Evaluable>, List<Variable<?>>> variables;
	private List<Supplier<Evaluable>> variableOrder;
	private List<String> variableNames;

	public OperationAdapter(Supplier<Evaluable<? extends T>>... args) {
		setArguments(Arrays.asList(arguments(args)));
	}

	public OperationAdapter(Argument<? extends T>... args) {
		if (args.length > 0) setArguments(Arrays.asList(args));
	}

	public void setFunctionName(String name) { function = name; }

	@Override
	public String getFunctionName() { return function; }

	@Override
	public String getName() { return operationName(getClass(), getFunctionName()); }

	public int getArgsCount() { return getArguments().size(); }

	protected void setArguments(List<Argument<? extends T>> arguments) { this.arguments = arguments; }

	public List<Argument<? extends T>> getArguments() {
		Scope.sortArguments(arguments);
		return arguments;
	}

	public List<Supplier<? extends Evaluable<? extends T>>> getArgumentProducers() {
		return getArguments().stream().map(arg -> arg == null ? null : arg.getProducer()).collect(Collectors.toList());
	}

	public Supplier<? extends Evaluable<? extends T>> getArgumentProducer(int argIndex) {
		return getArguments().get(argIndex).getProducer();
	}

	public void init() {
		if (function == null) setFunctionName(functionName(getClass()));
		purgeVariables();
		initArgumentNames();
	}

	protected void initArgumentNames() {
		initArgumentNames(getArguments());
	}

	protected void initArgumentNames(List<Argument<? extends T>> args) {
		int i = 0;
		for (Argument arg : args) {
			if (arg != null) {
				arg.setName(getArgumentName(i));
				arg.setAnnotation(getDefaultAnnotation());
				arg.getExpression().setType(Double.class);
			}
			i++;
		}
	}

	public Scope compile() { return compile(this); }

	public Scope compile(NameProvider p) { return null; }

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
		} else {
			throw new IllegalArgumentException(peer + " is not a OperationAdapter");
		}
	}

	public void absorbVariables(OperationAdapter peer) {
		peer.getVariables().forEach(v -> addVariable((Variable) v));
	}

	public void purgeVariables() {
		this.variables = new HashMap<>();
		this.variableOrder = new ArrayList<>();
		this.variableNames = new ArrayList<>();
	}

	protected static <T> Argument<T>[] arguments(Supplier<Evaluable<? extends T>>... producers) {
		Argument args[] = new Argument[producers.length];
		for (int i = 0; i < args.length; i++) {
			if (!enableNullInputs && producers[i] == null) {
				throw new IllegalArgumentException("Null argument at index " + i);
			}

			args[i] = producers[i] == null ? null : new Argument(producers[i]);
		}

		return args;
	}

	protected void removeDuplicateArguments() { setArguments(removeDuplicateArguments(getArguments())); }

	protected List<Argument<? extends T>> removeDuplicateArguments(List<Argument<? extends T>> arguments) {
		List<Argument<? extends T>> args = new ArrayList<>();
		args.addAll(arguments);

		List<String> names = new ArrayList<>();
		Iterator<Argument<? extends T>> itr = args.iterator();

		while (itr.hasNext()) {
			Argument arg = itr.next();
			if (names.contains(arg.getName())) {
				itr.remove();
			} else {
				names.add(arg.getName());
			}
		}

		return args;
	}

	@Override
	public synchronized void compact() {
		getArgumentProducers().stream().filter(p -> p instanceof Compactable).forEach(p -> ((Compactable) p).compact());
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

	public static String operationName(Class c, String functionName) {
		String name = c.getSimpleName();
		if (name == null || name.trim().length() <= 0) name = "anonymous";
		if (name.equals("AcceleratedOperation") || name.equals("AcceleratedProducer")) name = functionName;
		return name;
	}
}
