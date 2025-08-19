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

		ArrayVariable<Double> output = getArgument(0);
		ArrayVariable<Double> input = getArgument(1);

		// Declare working arrays
		ArrayVariable<Double> cofactorMatrix = scope.declareArray("cofactorMatrix_" + varIdx++, e(16));
		ArrayVariable<Double> subMatrix = scope.declareArray("subMatrix_" + varIdx++, e(9));

		// Initialize cofactor matrix to zeros
		InstanceReference init = Variable.integer("init_" + varIdx++).ref();
		Repeated initLoop = new Repeated<>(init.getReferent(), init.lessThan(e(16)));
		Scope<?> initBody = new Scope<>();
		{
			initBody.assign(cofactorMatrix.valueAt(init), e(0.0));
			initLoop.add(initBody);
		}
		scope.add(initLoop);

		// Create nested loops for i and j (0 to 3)
		InstanceReference i = Variable.integer("i_" + varIdx++).ref();
		Repeated outerLoop = new Repeated<>(i.getReferent(), i.lessThan(e(4)));
		
		Scope<?> outerBody = new Scope<>();
		{
			InstanceReference j = Variable.integer("j_" + varIdx++).ref();
			Repeated innerLoop = new Repeated<>(j.getReferent(), j.lessThan(e(4)));
			
			Scope<?> innerBody = new Scope<>();
			{
				// Extract 3x3 submatrix excluding row i and column j
				extractSubMatrix(innerBody, subMatrix, input, i, j);
				
				// Calculate determinant of the 3x3 submatrix
				Expression<Double> determinant = calculate3x3Determinant(innerBody, subMatrix);
				
				// Calculate cofactor = (-1)^(i+j) * determinant
				Expression<Double> sign = innerBody.declareDouble("sign_" + varIdx++,
					e(-1.0).pow(i.add(j).toDouble()));
				Expression<Double> cofactor = innerBody.declareDouble("cofactor_" + varIdx++,
					sign.multiply(determinant));
				
				// Store cofactor in cofactor matrix at position (i,j)
				innerBody.assign(cofactorMatrix.valueAt(i.multiply(4).add(j)), cofactor);
				
				innerLoop.add(innerBody);
			}
			
			outerBody.add(innerLoop);
		}
		
		outerLoop.add(outerBody);
		scope.add(outerLoop);

		// Transpose the cofactor matrix to get the adjugate matrix
		transposeMatrix(scope, output, cofactorMatrix);

		return scope;
	}

	@Override
	public TransformMatrixAdjoint generate(List<Process<?, ?>> children) {
		return new TransformMatrixAdjoint((Producer) children.get(1));
	}

	private void extractSubMatrix(Scope<?> scope, ArrayVariable<Double> subMatrix,
									ArrayVariable<Double> input,
									InstanceReference excludeRow,
									InstanceReference excludeCol) {
		// Initialize submatrix to zeros first
		InstanceReference k = Variable.integer("k_init_" + varIdx++).ref();
		Repeated initLoop = new Repeated<>(k.getReferent(), k.lessThan(e(9)));
		Scope<?> initBody = new Scope<>();
		{
			initBody.assign(subMatrix.valueAt(k), e(0.0));
			initLoop.add(initBody);
		}
		scope.add(initLoop);
		
		// Build 3x3 submatrix by copying elements that are not in excluded row/col
		Expression subIdx = scope.declareInteger("subIdx_" + varIdx++, e(0));
		
		InstanceReference i = Variable.integer("i_extract_" + varIdx++).ref();
		Repeated outerLoop = new Repeated<>(i.getReferent(), i.lessThan(e(4)));
		
		Scope<?> outerBody = new Scope<>();
		{
			InstanceReference j = Variable.integer("j_extract_" + varIdx++).ref();
			Repeated innerLoop = new Repeated<>(j.getReferent(), j.lessThan(e(4)));
			
			Scope<?> innerBody = new Scope<>();
			{
				// Copy element if not in excluded row or column
				Scope<?> copyElement = new Scope<>();
				{
					copyElement.assign(subMatrix.valueAt(subIdx),
						input.valueAt(i.multiply(4).add(j)));
					copyElement.assign(subIdx, subIdx.add(1));
				}
				
				innerBody.addCase(i.neq(excludeRow).and(j.neq(excludeCol)), (Scope) copyElement);
				innerLoop.add(innerBody);
			}
			
			outerBody.add(innerLoop);
			outerLoop.add(outerBody);
		}
		
		scope.add(outerLoop);
	}

	private Expression<Double> calculate3x3Determinant(Scope<?> scope, ArrayVariable<Double> matrix) {
		// For a 3x3 matrix: det = a00(a11*a22 - a12*a21) - a01(a10*a22 - a12*a20) + a02(a10*a21 - a11*a20)
		Expression<Double> a00 = matrix.valueAt(0);
		Expression<Double> a01 = matrix.valueAt(1);
		Expression<Double> a02 = matrix.valueAt(2);
		Expression<Double> a10 = matrix.valueAt(3);
		Expression<Double> a11 = matrix.valueAt(4);
		Expression<Double> a12 = matrix.valueAt(5);
		Expression<Double> a20 = matrix.valueAt(6);
		Expression<Double> a21 = matrix.valueAt(7);
		Expression<Double> a22 = matrix.valueAt(8);
		
		Expression<Double> term1 = scope.declareDouble("term1_" + varIdx++,
			a00.multiply(a11.multiply(a22).subtract(a12.multiply(a21))));
		Expression<Double> term2 = scope.declareDouble("term2_" + varIdx++,
			a01.multiply(a10.multiply(a22).subtract(a12.multiply(a20))));
		Expression<Double> term3 = scope.declareDouble("term3_" + varIdx++,
			a02.multiply(a10.multiply(a21).subtract(a11.multiply(a20))));
		
		return scope.declareDouble("det_" + varIdx++, term1.subtract(term2).add(term3));
	}

	private void transposeMatrix(Scope<?> scope, ArrayVariable<Double> output, ArrayVariable<Double> input) {
		// Transpose 4x4 matrix
		InstanceReference i = Variable.integer("i_transpose_" + varIdx++).ref();
		Repeated iLoop = new Repeated<>(i.getReferent(), i.lessThan(e(4)));
		
		Scope<?> iBody = new Scope<>();
		{
			InstanceReference j = Variable.integer("j_transpose_" + varIdx++).ref();
			Repeated jLoop = new Repeated<>(j.getReferent(), j.lessThan(e(4)));
			
			Scope<?> jBody = new Scope<>();
			{
				// output[i][j] = input[j][i]
				jBody.assign(output.valueAt(i.multiply(4).add(j)),
					input.valueAt(j.multiply(4).add(i)));
				
				jLoop.add(jBody);
			}
			
			iBody.add(jLoop);
		}
		
		iLoop.add(iBody);
		scope.add(iLoop);
	}
}
