/*
 * Copyright 2022 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph.computations;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Pair;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationComputationAdapter;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class TimeCellReset extends OperationComputationAdapter<PackedCollection<?>> implements ExpressionFeatures {
	protected HybridScope scope;
	private int len;

	public TimeCellReset(Producer<Pair<?>> time, PackedCollection<?> resets) {
		super((Supplier) time, () -> new Provider<>(resets));
		len = resets.getMemLength();
	}

	private TimeCellReset(int len, Supplier<Evaluable<? extends PackedCollection<?>>>... arguments) {
		super(arguments);
		this.len = len;
	}

	public ArrayVariable getTime() { return getArgument(0, 2); }
	public ArrayVariable getResets() { return getArgument(1); }

	@Override
	public ParallelProcess<Process<?, ?>, Runnable> generate(List<Process<?, ?>> children) {
		return new TimeCellReset(len, children.toArray(Supplier[]::new));
	}

	@Override
	public Scope getScope(KernelStructureContext context) { return scope; }

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		scope = new HybridScope(this);

		Consumer<String> exp = scope.code();

		for (int i = 0; i < len; i++) {
			if (i > 0) exp.accept(" else ");

			Expression<Boolean> condition = getResets().valueAt(1).greaterThan(e(0.0));
			condition = condition.and(getTime().ref(1).eq(getResets().valueAt(i)));

//			exp.accept("if (" + getTime().ref(1).getSimpleExpression() + " == " + getResets().valueAt(i).getSimpleExpression() + ") {\n");
			exp.accept("if (" + condition.getSimpleExpression(getLanguage()) + ") {\n");
			exp.accept("\t");
			exp.accept(getTime().valueAt(0).getSimpleExpression(getLanguage()));
			exp.accept(" = ");
			exp.accept(getLanguage().getPrecision().stringForDouble(0.0));
			exp.accept(";\n");
			exp.accept("}");
		}
	}
}
