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

package org.almostrealism.bool;

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.Argument;
import io.almostrealism.scope.Argument.Expectation;
import io.almostrealism.code.ArgumentMap;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.NAryExpression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Deprecated
public abstract class AcceleratedConjunctionAdapter<T extends PackedCollection<?>> extends AcceleratedConditionalStatementAdapter<T> {
	private List<AcceleratedConditionalStatement<? extends T>> conjuncts;
	private Supplier<Evaluable<?>> trueValue, falseValue;
	private ArrayVariable<Double> trueVar, falseVar;

	@SafeVarargs
	public AcceleratedConjunctionAdapter(int memLength,
										 IntFunction<MemoryBank<T>> kernelDestination,
										 Supplier<Evaluable<?>> trueValue,
										 Supplier<Evaluable<?>> falseValue,
										 AcceleratedConditionalStatement<? extends T>... conjuncts) {
		super(memLength, kernelDestination);
		this.trueValue = trueValue;
		this.falseValue = falseValue;
		this.conjuncts = Arrays.asList(conjuncts);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(conjuncts.stream(), map);
		ScopeLifecycle.prepareArguments(Stream.of(trueValue), map);
		ScopeLifecycle.prepareArguments(Stream.of(falseValue), map);
		map.add(trueValue);
		map.add(falseValue);
	}

	@Override
	public void prepareScope(ScopeInputManager manager, KernelStructureContext context) {
		super.prepareScope(manager, context);
		ScopeLifecycle.prepareScope(conjuncts.stream(), manager, context);
		ScopeLifecycle.prepareScope(Stream.of(trueValue), manager, context);
		ScopeLifecycle.prepareScope(Stream.of(falseValue), manager, context);

		List<ArrayVariable<Double>> args = new ArrayList<>();
		args.add((ArrayVariable<Double>) getOutputVariable());
		args.addAll(getOperands());

		this.trueVar = (ArrayVariable)  manager.argumentForInput(getNameProvider()).apply(trueValue);
		args.add(this.trueVar);

		this.falseVar = (ArrayVariable) manager.argumentForInput(getNameProvider()).apply(falseValue);
		args.add(this.falseVar);

		setArguments(args.stream()
				.map(var -> new Argument(var, Expectation.EVALUATE_AHEAD))
				.map(arg -> (Argument<? extends MemoryData>) arg)
				.collect(Collectors.toList()));
	}

	@Override
	public List<Argument<? extends MemoryData>> getArguments() { return getArguments(true); }

	@Override
	public Variable getOutputVariable() {
		return OperationAdapter.getArgumentForInput((List) getArgumentVariables(false), (Supplier) getInputs().get(0));
	}

	public synchronized List<ArrayVariable<Double>> getArgumentVariables(boolean includeConjuncts) {
		if (super.getArguments() == null) return null;

		return getArguments(includeConjuncts).stream()
				.map(arg -> Optional.ofNullable(arg).map(Argument::getVariable).orElse(null))
				.map(var -> (ArrayVariable) var)
				.map(var -> (ArrayVariable<Double>) var)
				.collect(Collectors.toList());
	}

	protected synchronized List<Argument<? extends MemoryData>> getArguments(boolean includeConjuncts) {
		if (super.getArguments() == null) return null;

		List<Argument<? extends MemoryData>> all = new ArrayList<>(super.getArguments());

		if (includeConjuncts) {
			conjuncts.stream()
					.map(AcceleratedConditionalStatement::getArguments)
					.filter(Objects::nonNull)
					.flatMap(List::stream)
					.filter(Objects::nonNull)
					.filter(v -> !all.contains(v))
					.forEach(all::add);

			// Remove the output variables
			conjuncts.stream().map(AcceleratedConditionalStatement::getOutputVariable).forEach(var -> {
				all.removeIf(argument -> argument.getVariable() == var);
			});
		}

		return Scope.removeDuplicateArguments(all);
	}

	@Override
	public List<ExpressionAssignment<?>> getVariables() {
		List<ExpressionAssignment<?>> all = new ArrayList<>();
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
	public List<ArrayVariable<Double>> getOperands() {
		return conjuncts.stream().flatMap(c -> c.getOperands().stream()).collect(Collectors.toList());
	}

	@Override
	public IntFunction<Expression<Double>> getTrueValueExpression() {
		return i -> (Expression) trueVar.getValueRelative(i);
	}

	@Override
	public IntFunction<Expression<Double>> getFalseValueExpression() {
		return i -> (Expression) falseVar.getValueRelative(i);
	}
}
