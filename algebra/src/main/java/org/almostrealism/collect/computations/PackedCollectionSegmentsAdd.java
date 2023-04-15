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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.computations.Repeated;

import java.util.function.Supplier;

public class PackedCollectionSegmentsAdd extends Repeated {
	public PackedCollectionSegmentsAdd(Producer<PackedCollection> destination,
									   Producer<PackedCollection> data,
									   Producer<PackedCollection> sourceOffsets,
									   Producer<PackedCollection> sourceLengths,
									   Producer<PackedCollection> destinationOffsets,
									   Producer<PackedCollection> count) {
		super((Supplier) destination, (Supplier) data, (Supplier) sourceOffsets,
				(Supplier) sourceLengths, (Supplier) destinationOffsets, (Supplier) count);
	}

	public ArrayVariable getDestination() { return getArgument(0); }
	public ArrayVariable getData() { return getArgument(1); }
	public ArrayVariable getSourceOffsets() { return getArgument(2); }
	public ArrayVariable getSourceLengths() { return getArgument(3); }
	public ArrayVariable getDestinationOffsets() { return getArgument(4); }
	public ArrayVariable getCount() { return getArgument(5, 1); }

	@Override
	public Scope<Void> getScope() {
		HybridScope<Void> scope = new HybridScope<>(this);

		String i = getVariablePrefix() + "_i";
		Expression exp = new InstanceReference(new Variable(i, Double.class, (Double) null));

		String cond = getCondition(exp);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "PackedCollectionSegmentsAdd"));

		String oVar = getVariablePrefix() + "_o";

		// Iterate over all the segments
		// If the kernel position is with the destination region, add the data
		scope.code().accept("for (int " + i + " = 0; " + cond +"; " + i + "++) {\n");
		scope.code().accept("    " + getNumberTypeName() + " " + oVar + " = get_global_id(0) - " + getDestinationOffsets().get(i).getExpression() + ";\n");
		scope.code().accept("    if (" + oVar + " >= 0 && " + oVar + " < " + getSourceLengths().get(i).getExpression() + ") {\n");
		scope.code().accept("        " + getInner(exp) + ";\n");
		scope.code().accept("    }\n");
		scope.code().accept("}\n");
		return scope;
	}

	@Override
	public String getInner(Expression<?> index) {
		String sourcePosition = "get_global_id(0) + " + getSourceOffsets().valueAt(index).getExpression() + " - " + getDestinationOffsets().valueAt(index).getExpression();

		return getDestination().valueAt(0).getExpression() + " = " +
				new Sum(getDestination().valueAt(0),
						getData().get(sourcePosition)).getExpression();
	}

	@Override
	public String getCondition(Expression<?> index) {
		return index.getExpression() + " < " + getCount().valueAt(0).getExpression();
	}
}
