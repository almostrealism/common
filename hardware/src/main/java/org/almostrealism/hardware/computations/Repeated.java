/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.Computation;
import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.relation.Compactable;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.OpenCLPrintWriter;
import org.almostrealism.hardware.DynamicOperationComputationAdapter;

import java.util.function.Supplier;

public abstract class Repeated extends DynamicOperationComputationAdapter<Void> {

	public Repeated(Supplier<Evaluable<? extends Void>>... inputArgs) {
		super(inputArgs);
	}

	@Override
	public Scope<Void> getScope() {
		HybridScope<Void> scope = new HybridScope<>(this);

		String i = getVariablePrefix() + "_i";
		Expression exp = new InstanceReference(new Variable(i, Double.class, (Double) null));

		String cond = getCondition(exp);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "Repeated (" + cond + ")"));

		scope.code().accept("for (int " + i + " = 0; " + cond +"; " + i + "++) {\n");
		scope.code().accept("    " + getInner(exp) + ";\n");
		scope.code().accept("}\n");
		return scope;
	}

	// TODO  Should return Variable
	public abstract String getInner(Expression<?> index);

	// TODO  Should return Expression
	public abstract String getCondition(Expression<?> index);
}
