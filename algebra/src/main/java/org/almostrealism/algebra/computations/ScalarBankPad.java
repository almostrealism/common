/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBankProducerBase;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.MemoryData;

import java.util.function.Supplier;

public class ScalarBankPad extends CollectionProducerComputationBase<PackedCollection<Scalar>, PackedCollection<Scalar>> implements ScalarBankProducerBase, DestinationSupport<PackedCollection<Scalar>>, ComputerFeatures {
	private final int count;
	private final int total;

	private Supplier<PackedCollection<Scalar>> destination;

	public ScalarBankPad(int count, int total, Producer<PackedCollection<Scalar>> input) {
		super(new TraversalPolicy(count, 2), (Supplier) input);
		this.count = count;
		this.total = total;
	}

	@Override
	public void setDestination(Supplier<PackedCollection<Scalar>> destination) { this.destination = destination; }

	@Override
	public Supplier<PackedCollection<Scalar>> getDestination() { return destination; }

	@Override
	public Scope<PackedCollection<Scalar>> getScope() {
		HybridScope<PackedCollection<Scalar>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "ScalarBankPad"));

		Expression i = new StaticReference(Integer.class, getVariablePrefix() + "_i");
		String resultX = getArgument(0, 2 * count).getRelative(i.multiply(2)).getSimpleExpression();
		String resultY = getArgument(0, 2 * count).getRelative(i.multiply(2).add(1)).getSimpleExpression();
		String valueX = getArgument(1, 2 * count).getRelative(i.multiply(2)).getSimpleExpression();
		String valueY = getArgument(1, 2 * count).getRelative(i.multiply(2).add(1)).getSimpleExpression();

		scope.code().accept("for (int " + i + " = 0; " + i + " < " + count +"; " + i + "++) {\n");
		scope.code().accept("    if (" + i + " < " + total + ") {\n");
		scope.code().accept("        " + resultX + " = " + valueX + ";\n");
		scope.code().accept("        " + resultY + " = " + valueY + ";\n");
		scope.code().accept("    } else {\n");
		scope.code().accept("        " + resultX + " = " + stringForDouble(0.0) + ";\n");
		scope.code().accept("        " + resultY + " = " + stringForDouble(1.0) + ";\n");
		scope.code().accept("    }\n");
		scope.code().accept("}\n");
		return scope;
	}

	@Override
	public PackedCollection<Scalar> postProcessOutput(MemoryData output, int offset) {
		return Scalar.scalarBank(output.getMemLength() / 2, output, offset);
	}
}
