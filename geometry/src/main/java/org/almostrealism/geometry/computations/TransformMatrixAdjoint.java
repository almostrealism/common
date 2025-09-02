/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.geometry.computations;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Scope;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Repeated;
import io.almostrealism.scope.Variable;
import io.almostrealism.compute.Process;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.geometry.TransformMatrix;

import java.util.function.Supplier;
import java.util.List;

public class TransformMatrixAdjoint extends CollectionProducerComputationBase<PackedCollection<?>, TransformMatrix> {
	private int varIdx = 0;

	public TransformMatrixAdjoint(Producer<TransformMatrix> input) {
		super("transformMatrixAdjoint", new TraversalPolicy(4, 4), (Supplier) input);
		setPostprocessor(TransformMatrix.postprocessor());
	}

	@Override
	public Scope<TransformMatrix> getScope(KernelStructureContext context) {
		HybridScope<TransformMatrix> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "TransformMatrixAdjoint"));

		Scope<?> body = new Scope<>();

		ArrayVariable<Double> output = getArgument(0);
		ArrayVariable<Double> input = getArgument(1);

		// Calculate adjoint matrix directly using mathematical formulas
		// For a 4x4 matrix, calculate each element of the adjoint matrix
		
		// Element (0,0): C00 = +det(3x3 minor excluding row 0, col 0)
		body.assign(output.valueAt(0), calculateCofactor(body, input, 0, 0));
		body.assign(output.valueAt(1), calculateCofactor(body, input, 1, 0)); // transposed
		body.assign(output.valueAt(2), calculateCofactor(body, input, 2, 0));
		body.assign(output.valueAt(3), calculateCofactor(body, input, 3, 0));
		
		body.assign(output.valueAt(4), calculateCofactor(body, input, 0, 1));
		body.assign(output.valueAt(5), calculateCofactor(body, input, 1, 1));
		body.assign(output.valueAt(6), calculateCofactor(body, input, 2, 1));
		body.assign(output.valueAt(7), calculateCofactor(body, input, 3, 1));
		
		body.assign(output.valueAt(8), calculateCofactor(body, input, 0, 2));
		body.assign(output.valueAt(9), calculateCofactor(body, input, 1, 2));
		body.assign(output.valueAt(10), calculateCofactor(body, input, 2, 2));
		body.assign(output.valueAt(11), calculateCofactor(body, input, 3, 2));
		
		body.assign(output.valueAt(12), calculateCofactor(body, input, 0, 3));
		body.assign(output.valueAt(13), calculateCofactor(body, input, 1, 3));
		body.assign(output.valueAt(14), calculateCofactor(body, input, 2, 3));
		body.assign(output.valueAt(15), calculateCofactor(body, input, 3, 3));

		scope.add((Scope) body);

		return scope;
	}
	
	private Expression<Double> calculateCofactor(Scope<?> scope, ArrayVariable<Double> matrix, int row, int col) {
		// Calculate cofactor = (-1)^(row+col) * determinant of 3x3 minor
		double sign = ((row + col) % 2 == 0) ? 1.0 : -1.0;
		
		// Calculate the 3x3 determinant directly using the formula
		// For a 4x4 matrix, removing row 'row' and column 'col'
		Expression<Double> det = calculate3x3MinorDeterminant(scope, matrix, row, col);
		
		return scope.declareDouble("cofactor_" + row + "_" + col + "_" + varIdx++, 
			e(sign).multiply(det));
	}
	
	private Expression<Double> calculate3x3MinorDeterminant(Scope<?> scope, ArrayVariable<Double> matrix, int excludeRow, int excludeCol) {
		// Get the 9 elements of the 3x3 minor
		Expression<Double>[] elements = new Expression[9];
		int elemIdx = 0;
		
		for (int i = 0; i < 4; i++) {
			if (i == excludeRow) continue;
			for (int j = 0; j < 4; j++) {
				if (j == excludeCol) continue;
				elements[elemIdx++] = matrix.valueAt(i * 4 + j);
			}
		}
		
		// Calculate determinant using the standard 3x3 formula:
		// det = a00(a11*a22 - a12*a21) - a01(a10*a22 - a12*a20) + a02(a10*a21 - a11*a20)
		Expression<Double> term1 = scope.declareDouble("term1_" + varIdx++,
			elements[0].multiply(elements[4].multiply(elements[8]).subtract(elements[5].multiply(elements[7]))));
		Expression<Double> term2 = scope.declareDouble("term2_" + varIdx++,
			elements[1].multiply(elements[3].multiply(elements[8]).subtract(elements[5].multiply(elements[6]))));
		Expression<Double> term3 = scope.declareDouble("term3_" + varIdx++,
			elements[2].multiply(elements[3].multiply(elements[7]).subtract(elements[4].multiply(elements[6]))));
		
		return scope.declareDouble("det3x3_" + varIdx++, term1.subtract(term2).add(term3));
	}

	@Override
	public TransformMatrixAdjoint generate(List<Process<?, ?>> children) {
		return new TransformMatrixAdjoint((Producer) children.get(1));
	}
}
