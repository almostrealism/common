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

package org.almostrealism.bool;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.NAryExpression;
import io.almostrealism.relation.Compactable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.AcceleratedEvaluable;
import org.almostrealism.hardware.MemWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AcceleratedConjunctionAdapter<T extends MemWrapper> extends AcceleratedConditionalStatementAdapter<T> {
	private List<AcceleratedConditionalStatement<? extends T>> conjuncts;
	private Supplier<Evaluable<? extends T>> trueValue, falseValue;
	private ArrayVariable<T> trueVar, falseVar;

	public AcceleratedConjunctionAdapter(int memLength,
											 Function<Integer, Supplier<T>> blankValue) {
		this(memLength, blankValue, null, null);
	}

	public AcceleratedConjunctionAdapter(int memLength,
										 Function<Integer, Supplier<T>> blankValue,
										 Supplier<Evaluable<? extends T>> trueValue, Supplier<Evaluable<? extends T>> falseValue,
										 AcceleratedConditionalStatement<? extends T>... conjuncts) {
		this(memLength, blankValue.apply(memLength), trueValue, falseValue, conjuncts);
	}

	public AcceleratedConjunctionAdapter(int memLength,
										 	Supplier<T> blankValue,
										 	Supplier<Evaluable<? extends T>> trueValue,
										 	Supplier<Evaluable<? extends T>> falseValue,
										 	AcceleratedConditionalStatement<? extends T>... conjuncts) {
		super(memLength, blankValue);
		this.trueValue = trueValue;
		this.falseValue = falseValue;
		this.conjuncts = Arrays.asList(conjuncts);
	}

	@Override
	protected void initArgumentNames() { initArgumentNames(getArguments(false)); }

	@Override
	protected void removeDuplicateArguments() { setArguments(Scope.removeDuplicateArguments(getArguments(false))); }

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(conjuncts.stream(), map);
		ScopeLifecycle.prepareArguments(Stream.of(trueValue), map);
		ScopeLifecycle.prepareArguments(Stream.of(falseValue), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		ScopeLifecycle.prepareScope(conjuncts.stream(), manager);
		ScopeLifecycle.prepareScope(Stream.of(trueValue), manager);
		ScopeLifecycle.prepareScope(Stream.of(falseValue), manager);

		List<ArrayVariable<? extends MemWrapper>> args = new ArrayList<>();
		args.add(getArguments(false).get(0));
		args.addAll(getOperands());

		this.trueVar = new ArrayVariable(this, getArgumentName(1), trueValue);
		args.add(this.trueVar);

		this.falseVar = new ArrayVariable(this, getArgumentName(2), falseValue);
		args.add(this.falseVar);

		setArguments(args);
	}

	@Override
	public List<ArrayVariable<? extends MemWrapper>> getArguments() { return getArguments(true); }

	protected List<ArrayVariable<? extends MemWrapper>> getArguments(boolean includeConjuncts) {
		if (super.getArguments() == null) return null;

		List<ArrayVariable<? extends MemWrapper>> all = new ArrayList<>();
		all.addAll(super.getArguments());

		if (includeConjuncts) {
			conjuncts.stream()
					.map(AcceleratedConditionalStatement::getArguments)
					.map(AcceleratedEvaluable::excludeResult)
					.flatMap(List::stream)
					.filter(Objects::nonNull)
					.filter(v -> !all.contains(v))
					.forEach(all::add);
		}

		return Scope.removeDuplicateArguments(all);
	}

	@Override
	public List<Variable<?>> getVariables() {
		List<Variable<?>> all = new ArrayList<>();
		all.addAll(super.getVariables());
		conjuncts.stream()
				.map(AcceleratedConditionalStatement::getVariables)
				.flatMap(List::stream)
				.filter(v -> !all.contains(v))
				.forEach(all::add);
		return all;
	}

	@Override
	public Expression getCondition() {
		return new NAryExpression(Boolean.class, "&",
				conjuncts.stream().map(AcceleratedConditionalStatement::getCondition)
						.collect(Collectors.toList()));
	}

	@Override
	public List<ArrayVariable<Scalar>> getOperands() {
		return conjuncts.stream().flatMap(c -> c.getOperands().stream()).collect(Collectors.toList());
	}

	@Override
	public ArrayVariable getTrueValue() { return trueVar; }

	@Override
	public ArrayVariable getFalseValue() { return falseVar; }

	@Override
	public void compact() {
		conjuncts.stream()
				.map(c -> c instanceof Compactable ? (Compactable) c : null)
				.filter(Objects::nonNull)
				.forEach(Compactable::compact);
		super.compact();
	}
}
