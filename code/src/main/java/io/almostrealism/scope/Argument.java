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

import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Named;
import io.almostrealism.relation.Sortable;

import java.util.Objects;
import java.util.function.Supplier;

public class Argument<T> implements Named, Sortable, Delegated<Argument<T>> {
	public enum Expectation {
		EVALUATE_AHEAD, WILL_EVALUATE, WILL_ALTER, NOT_ALTERED
	}

	private final Variable<T, ?> variable;
	private final Expectation expect;

	public Argument(Variable<T, ?> variable, Expectation expectation) {
		if (variable == null || expectation == null) throw new IllegalArgumentException();

		this.variable = variable;
		this.expect = expectation;
	}

	@Override
	public String getName() { return getVariable().getName(); }

	@Override
	public int getSortHint() { return getVariable().getSortHint(); }

	public Variable<T, ?> getVariable() { return variable; }

	public Expectation getExpectation() { return expect; }

	public Supplier<Evaluable<? extends T>> getProducer() { return getVariable().getProducer(); }

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Argument)) return false;
		Argument<?> argument = (Argument<?>) o;
		return Objects.equals(variable, argument.variable) && expect == argument.expect;
	}

	@Override
	public int hashCode() {
		return Objects.hash(variable);
	}

	@Override
	public Argument<T> getDelegate() {
		if (getVariable() == null) return null;
		if (((Delegated) getVariable()).getDelegate() == null) return null;
		return new Argument((Variable) ((Delegated) getVariable()).getDelegate(), Expectation.EVALUATE_AHEAD);
	}
}
