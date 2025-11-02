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
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.compute.Process;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.geometry.TransformMatrix;

import java.util.function.Supplier;
import java.util.List;

/**
 * Simplified determinant calculation using direct cofactor expansion.
 * For a 4x4 matrix, this computes the determinant directly without
 * Gaussian elimination, avoiding potential loop initialization issues.
 */
public class TransformMatrixDeterminantSimple extends CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> {

    public TransformMatrixDeterminantSimple(Producer<TransformMatrix> input) {
        super("transformMatrixDeterminantSimple", new TraversalPolicy(1), (Supplier) input);
    }

    @Override
    public Scope<PackedCollection<?>> getScope(KernelStructureContext context) {
        HybridScope<PackedCollection<?>> scope = new HybridScope<>(this);
        scope.setMetadata(new OperationMetadata(getFunctionName(), "TransformMatrixDeterminantSimple"));

        Scope<?> body = new Scope<>();

        ArrayVariable<Double> output = getArgument(0);
        ArrayVariable<Double> m = getArgument(1);  // Input matrix

        // For a 4x4 matrix, we can compute the determinant directly
        // using cofactor expansion along the first row

        // Helper to get matrix element at (row, col)
        // m[row][col] = m[row * 4 + col]

        // First, compute the 3x3 determinants for each cofactor

        // Cofactor 0,0: Minor is the 3x3 matrix excluding row 0 and col 0
        Expression<Double> minor00 = compute3x3Determinant(body, m,
            1, 1,  1, 2,  1, 3,
            2, 1,  2, 2,  2, 3,
            3, 1,  3, 2,  3, 3);

        // Cofactor 0,1: Minor is the 3x3 matrix excluding row 0 and col 1
        Expression<Double> minor01 = compute3x3Determinant(body, m,
            1, 0,  1, 2,  1, 3,
            2, 0,  2, 2,  2, 3,
            3, 0,  3, 2,  3, 3);

        // Cofactor 0,2: Minor is the 3x3 matrix excluding row 0 and col 2
        Expression<Double> minor02 = compute3x3Determinant(body, m,
            1, 0,  1, 1,  1, 3,
            2, 0,  2, 1,  2, 3,
            3, 0,  3, 1,  3, 3);

        // Cofactor 0,3: Minor is the 3x3 matrix excluding row 0 and col 3
        Expression<Double> minor03 = compute3x3Determinant(body, m,
            1, 0,  1, 1,  1, 2,
            2, 0,  2, 1,  2, 2,
            3, 0,  3, 1,  3, 2);

        // Compute determinant using cofactor expansion along first row
        // det = a00*minor00 - a01*minor01 + a02*minor02 - a03*minor03
        Expression<Double> a00 = m.valueAt(0);
        Expression<Double> a01 = m.valueAt(1);
        Expression<Double> a02 = m.valueAt(2);
        Expression<Double> a03 = m.valueAt(3);

        Expression<Double> det = body.declareDouble("det",
            a00.multiply(minor00)
                .subtract(a01.multiply(minor01))
                .add(a02.multiply(minor02))
                .subtract(a03.multiply(minor03)));

        body.assign(output.valueAt(0), det);
        scope.add((Scope) body);

        return scope;
    }

    private Expression<Double> compute3x3Determinant(Scope<?> scope, ArrayVariable<Double> m,
                                                     int r0, int c0,  int r1, int c1,  int r2, int c2,
                                                     int r3, int c3,  int r4, int c4,  int r5, int c5,
                                                     int r6, int c6,  int r7, int c7,  int r8, int c8) {
        // Get the 9 elements of the 3x3 matrix
        // First row
        Expression<Double> a00 = m.valueAt(r0 * 4 + c0);
        Expression<Double> a01 = m.valueAt(r1 * 4 + c1);
        Expression<Double> a02 = m.valueAt(r2 * 4 + c2);

        // Second row
        Expression<Double> a10 = m.valueAt(r3 * 4 + c3);
        Expression<Double> a11 = m.valueAt(r4 * 4 + c4);
        Expression<Double> a12 = m.valueAt(r5 * 4 + c5);

        // Third row
        Expression<Double> a20 = m.valueAt(r6 * 4 + c6);
        Expression<Double> a21 = m.valueAt(r7 * 4 + c7);
        Expression<Double> a22 = m.valueAt(r8 * 4 + c8);

        // Compute 3x3 determinant:
        // det = a00*(a11*a22 - a12*a21) - a01*(a10*a22 - a12*a20) + a02*(a10*a21 - a11*a20)
        return scope.declareDouble("minor3x3",
            a00.multiply(a11.multiply(a22).subtract(a12.multiply(a21)))
                .subtract(a01.multiply(a10.multiply(a22).subtract(a12.multiply(a20))))
                .add(a02.multiply(a10.multiply(a21).subtract(a11.multiply(a20)))));
    }

    @Override
    public TransformMatrixDeterminantSimple generate(List<Process<?, ?>> children) {
        return new TransformMatrixDeterminantSimple((Producer) children.get(1));
    }
}