/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.Scope;
import io.almostrealism.expression.Expression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;

import java.util.function.Function;
import java.util.function.Supplier;

public class PackedCollectionMax extends CollectionProducerComputationAdapter<PackedCollection<?>, PackedCollection<?>> {
	private Function<Expression, Expression> expression;

	public PackedCollectionMax(Producer<PackedCollection<?>> values) {
		this(values, v -> v);
	}

	public PackedCollectionMax(Supplier<Evaluable<? extends PackedCollection<?>>> values, Function<Expression, Expression> expression) {
		super(new TraversalPolicy(1), values);
		this.expression = expression;
	}

	@Override
	public Scope<PackedCollection<?>> getScope() {
		HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "PackedCollectionMax"));

		String i = getVariablePrefix() + "_i";
		String result = getArgument(0, 2).valueAt(0).getExpression();
		String value = expression.apply(getArgument(1).get(i)).getExpression();
		String count = getArgument(1).length().getExpression();

		scope.code().accept("for (int " + i + " = 0; " + i + " < " + count +"; " + i + "++) {\n");
		scope.code().accept("    if (" + value + " > " + result + ") {\n");
		scope.code().accept("        " + result + " = " + value + ";\n");
		scope.code().accept("    }\n");
		scope.code().accept("}\n");
		return scope;
	}
}
