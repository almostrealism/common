/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.collect.Shape;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBankProducerBase;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryData;

/**
 * @deprecated  Use {@link org.almostrealism.collect.computations.PackedCollectionPad} instead.
 */
@Deprecated
public class ScalarBankPad extends CollectionProducerComputationBase<PackedCollection<Scalar>, PackedCollection<Scalar>>
		implements ScalarBankProducerBase, HardwareFeatures {
	private final int count;
	private final int total;

	public ScalarBankPad(int count, int total, Producer<PackedCollection<Scalar>> input) {
		super("scalarBankPad", new TraversalPolicy(count, 2), adjustInput(input));
		this.count = count;
		this.total = total;
	}

	@Override
	public Scope<PackedCollection<Scalar>> getScope(KernelStructureContext context) {
		HybridScope<PackedCollection<Scalar>> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "ScalarBankPad"));

		Expression i = new StaticReference(Integer.class, getVariablePrefix() + "_i");
		Expression resultX = getArgument(0).referenceRelative(i.multiply(2));
		Expression resultY = getArgument(0).referenceRelative(i.multiply(2).add(1));
		Expression valueX = getArgument(1).referenceRelative(i.multiply(2));
		Expression valueY = getArgument(1).referenceRelative(i.multiply(2).add(1));

		scope.code().accept("for (int " + i + " = 0; " + i + " < " + count +"; " + i + "++) {\n");
		scope.code().accept("    if (" + i + " < " + total + ") {\n");
		scope.code().accept("        " + resultX.assign(valueX).getStatement(getLanguage()) + ";\n");
		scope.code().accept("        " + resultY.assign(valueY).getStatement(getLanguage()) + ";\n");
		scope.code().accept("    } else {\n");
		scope.code().accept("        " + resultX.assign(e(0.0)).getStatement(getLanguage()) + ";\n");
		scope.code().accept("        " + resultY.assign(e(1.0)).getStatement(getLanguage()) + ";\n");
		scope.code().accept("    }\n");
		scope.code().accept("}\n");
		return scope;
	}

	@Override
	public PackedCollection<Scalar> postProcessOutput(MemoryData output, int offset) {
		return Scalar.scalarBank(output.getMemLength() / 2, output, offset);
	}

	protected static Producer<PackedCollection<Scalar>>
			adjustInput(Producer<PackedCollection<Scalar>> input) {
		if (!(input instanceof Shape)) return input;

		TraversalPolicy shape = ((Shape) input).getShape();
		if (shape.getSize() == 2 && shape.getTraversalAxis() > 0) {
			return CollectionFeatures.getInstance().traverse(shape.getTraversalAxis() - 1, (Producer) input);
		}

		return input;
	}
}
