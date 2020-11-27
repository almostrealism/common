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

package org.almostrealism.math.bool;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.Compactable;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class AcceleratedConjunctionAdapter<T extends MemWrapper> extends AcceleratedConditionalStatementAdapter<T> {
	private List<AcceleratedConditionalStatement<? extends T>> conjuncts;
	private Argument trueValue, falseValue;

	public AcceleratedConjunctionAdapter(int memLength,
											 Function<Integer, Supplier<Evaluable<? extends T>>> blankValue) {
		this(memLength, blankValue, null, null);
	}

	public AcceleratedConjunctionAdapter(int memLength,
										 Function<Integer, Supplier<Evaluable<? extends T>>> blankValue,
										 Supplier<Evaluable<? extends T>> trueValue, Supplier<Evaluable<? extends T>> falseValue,
										 AcceleratedConditionalStatement<? extends T>... conjuncts) {
		this(memLength, blankValue.apply(memLength), trueValue, falseValue, conjuncts);
	}

	public AcceleratedConjunctionAdapter(int memLength,
										 	Supplier<Evaluable<? extends T>> blankValue,
										 	Supplier<Evaluable<? extends T>> trueValue,
										 	Supplier<Evaluable<? extends T>> falseValue,
										 	AcceleratedConditionalStatement<? extends T>... conjuncts) {
		super(memLength, blankValue);
		this.conjuncts = Arrays.asList(conjuncts);
		initArguments(trueValue, falseValue);
	}

	protected void initArguments(Supplier<Evaluable<? extends T>> trueValue, Supplier<Evaluable<? extends T>> falseValue) {
		List<Argument<? extends MemWrapper>> args = new ArrayList<>();
		args.add(getArguments(false).get(0));
		args.addAll(getOperands());

		this.trueValue = new Argument(getArgumentName(1), trueValue);
		args.add(this.trueValue);

		this.falseValue = new Argument(getArgumentName(2), falseValue);
		args.add(this.falseValue);
		
		setArguments(args);
	}

	@Override
	protected void initArgumentNames() {
		initArgumentNames(getArguments(false));
	}

	@Override
	public List<Argument<? extends MemWrapper>> getArguments() { return getArguments(true); }

	public List<Argument<? extends MemWrapper>> getArguments(boolean includeConjuncts) {
		List<Argument<? extends MemWrapper>> all = new ArrayList<>();
		all.addAll(super.getArguments());

		if (includeConjuncts) {
			conjuncts.stream()
					.map(AcceleratedConditionalStatement::getArguments)
					.flatMap(List::stream)
					.filter(v -> !all.contains(v))
					.forEach(all::add);
		}

		return all;
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
	public String getCondition() {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < conjuncts.size(); i++) {
			buf.append("(");
			buf.append(conjuncts.get(i).getCondition());
			buf.append(")");

			if (i < (conjuncts.size() - 1)) buf.append(" & ");
		}

		return buf.toString();
	}

	@Override
	public List<Argument<Scalar>> getOperands() {
		return conjuncts.stream().flatMap(c -> c.getOperands().stream()).collect(Collectors.toList());
	}

	@Override
	public Argument getTrueValue() { return trueValue; }

	@Override
	public Argument getFalseValue() { return falseValue; }

	@Override
	public void compact() {
		conjuncts.stream()
				.map(c -> c instanceof Compactable ? (Compactable) c : null)
				.filter(Objects::nonNull)
				.forEach(Compactable::compact);
		super.compact();
	}
}
