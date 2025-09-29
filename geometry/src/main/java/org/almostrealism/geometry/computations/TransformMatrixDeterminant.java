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

public class TransformMatrixDeterminant extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {
    private int varIdx = 0;

    public TransformMatrixDeterminant(Producer<TransformMatrix> input) {
        super("transformMatrixDeterminant", new TraversalPolicy(1), (Supplier) input);
    }

    @Override
    public Scope<PackedCollection<?>> getScope(KernelStructureContext context) {
        HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
        scope.setMetadata(new OperationMetadata(getFunctionName(), "TransformMatrixDeterminant"));

        Scope<?> body = new Scope<>();

        ArrayVariable<Double> output = getArgument(0);
        ArrayVariable<Double> input = getArgument(1);

        // Declare working arrays
        ArrayVariable<Double> upperTriangle = body.declareArray("upperTriangle_" + varIdx++, e(16));
        Expression<Double> determinantFactor = body.declareDouble("determinantFactor_" + varIdx++, e(1));

        // Convert matrix to upper triangular form
        matrixToUpperTriangle(body, upperTriangle, determinantFactor, input);

        // Calculate determinant as product of diagonal elements
        Expression<Double> det = body.declareDouble("det_" + varIdx++, e(1.0));
        
        InstanceReference i = Variable.integer("i_diag_" + varIdx++).ref();
        Repeated diagLoop = new Repeated<>(i.getReferent(), i.lessThan(e(4)));
        
        Scope<?> diagBody = new Scope<>();
        {
            diagBody.assign(det, det.multiply(upperTriangle.valueAt(i.multiply(4).add(i))));
            diagLoop.add(diagBody);
        }
        
        body.add(diagLoop);

        // Final result = det * determinantFactor
        body.assign(output.valueAt(0), det.multiply(determinantFactor));

        scope.add((Scope) body);

        return scope;
    }

    @Override
    public TransformMatrixDeterminant generate(List<Process<?, ?>> children) {
        return new TransformMatrixDeterminant((Producer) children.get(1));
    }

    private void matrixToUpperTriangle(Scope<?> scope, ArrayVariable<Double> result,
                                       Expression<Double> determinantFactor,
                                       ArrayVariable<Double> input) {
        // Copy input to result
        copyMatrix(scope, result, input);
        
        // Initialize determinant factor
        scope.assign(determinantFactor, e(1.0));
        
        // Gaussian elimination with partial pivoting
        InstanceReference col = Variable.integer("col_" + varIdx++).ref();
        Repeated colLoop = new Repeated<>(col.getReferent(), col.lessThan(e(3))); // 0 to 2
        
        Scope<?> colBody = new Scope<>();
        {
            InstanceReference row = Variable.integer("row_" + varIdx++).ref();
            Repeated rowLoop = new Repeated<>(row.getReferent(), row.greaterThan(col).and(row.lessThan(e(4))));
            
            Scope<?> rowBody = new Scope<>();
            {
                // Handle pivot if diagonal element is zero
                handlePivot(rowBody, result, determinantFactor, col);
                
                // Gaussian elimination step
                Expression<Double> pivot = result.valueAt(col.multiply(4).add(col));
                
                Scope<?> eliminationStep = new Scope<>();
                {
                    Expression<Double> factor = eliminationStep.declareDouble("factor_" + varIdx++,
                        result.valueAt(row.multiply(4).add(col)).divide(pivot).multiply(e(-1.0)));
                    
                    InstanceReference i = Variable.integer("i_elim_" + varIdx++).ref();
                    Repeated elimLoop = new Repeated<>(i.getReferent(), i.greaterThanOrEqual(col).and(i.lessThan(e(4))));
                    
                    Scope<?> elimBody = new Scope<>();
                    {
                        Expression<Double> currentValue = result.valueAt(row.multiply(4).add(i));
                        Expression<Double> pivotRowValue = result.valueAt(col.multiply(4).add(i));
                        elimBody.assign(currentValue, factor.multiply(pivotRowValue).add(currentValue));
                        elimLoop.add(elimBody);
                    }
                    
                    eliminationStep.add(elimLoop);
                }
                
                rowBody.addCase(pivot.neq(e(0.0)), (Scope) eliminationStep);
                rowLoop.add(rowBody);
            }
            
            colBody.add(rowLoop);
        }
        
        colLoop.add(colBody);
        scope.add(colLoop);
    }

    private void handlePivot(Scope<?> scope, ArrayVariable<Double> matrix,
                             Expression<Double> determinantFactor,
                            InstanceReference col) {
        // Handle the case where the diagonal element is zero by swapping rows
        Expression<Double> pivot = matrix.valueAt(col.multiply(4).add(col));
        
        // Find a non-zero element in the same column below the current row
        Expression v = scope.declareInteger("v_" + varIdx++, e(1));
        
        Expression done = scope.declareInteger("done_" + varIdx++, e(0));
        
        // While loop to find pivot
        Repeated whileLoop = new Repeated<>(Variable.integer("l_" + varIdx++), done.eq(e(0)));
        
        Scope<?> whileBody = new Scope<>();
        {
            Expression<Integer> colPlusV = col.add(v);
            
            // Check if we've gone past the matrix bounds
            Scope<?> noPivotFound = new Scope<>();
            {
                noPivotFound.assign(determinantFactor, e(0.0));
                noPivotFound.assign(done, e(1));
            }
            
            // Swap rows if valid pivot found
            Scope<?> swapRows = new Scope<>();
            {
                // Swap the rows
                InstanceReference c = Variable.integer("c_swap_" + varIdx++).ref();
                Repeated swapLoop = new Repeated<>(c.getReferent(), c.lessThan(e(4)));
                
                Scope<?> swapBody = new Scope<>();
                {
                    Expression<Double> temp = swapBody.declareDouble("temp_" + varIdx++, matrix.valueAt(col.multiply(4).add(c)));
                    swapBody.assign(matrix.valueAt(col.multiply(4).add(c)), matrix.valueAt(colPlusV.multiply(4).add(c)));
                    swapBody.assign(matrix.valueAt(colPlusV.multiply(4).add(c)), temp);
                    swapLoop.add(swapBody);
                }
                
                swapRows.add(swapLoop);
                swapRows.assign(v, v.add(1));
                swapRows.assign(determinantFactor, determinantFactor.multiply(e(-1.0)));
            }
            
            // Continue searching
            Scope<?> continueSearch = new Scope<>();
            {
                continueSearch.assign(v, v.add(1));
            }
            
            whileBody.addCase(colPlusV.greaterThanOrEqual(e(4)), (Scope) noPivotFound);
            whileBody.addCase(matrix.valueAt(colPlusV.multiply(4).add(col)).neq(e(0.0)),
								(Scope) swapRows, (Scope) continueSearch);
            whileLoop.add(whileBody);
        }
        
        scope.addCase(pivot.eq(e(0.0)), whileLoop);
    }

    private void copyMatrix(Scope<?> scope, ArrayVariable<Double> dest, ArrayVariable<Double> src) {
        InstanceReference i = Variable.integer("i_copy_" + varIdx++).ref();
        Repeated copyLoop = new Repeated<>(i.getReferent(), i.lessThan(e(16)));
        
        Scope<?> copyBody = new Scope<>();
        {
            copyBody.assign(dest.valueAt(i), src.valueAt(i));
            copyLoop.add(copyBody);
        }
        
        scope.add(copyLoop);
    }
}
