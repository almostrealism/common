/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.algebra;

import org.almostrealism.algebra.computations.DotProduct;
import org.almostrealism.algebra.computations.ScalarFromVector;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.VectorFromScalars;
import org.almostrealism.algebra.computations.VectorProduct;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.relation.TripleFunction;
import org.almostrealism.uml.Function;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

/**
 * {@link VectorProducer} is implemented by any class that can produce an {@link Vector} object
 * given some array of input objects.
 * 
 * @author  Michael Murray
 */
@Function
public interface VectorProducer extends Producer<Vector> {
    default ScalarProducer x() {
        return new ScalarFromVector(this, ScalarFromVector.X);
    }

    default ScalarProducer y() {
        return new ScalarFromVector(this, ScalarFromVector.Y);
    }

    default ScalarProducer z() {
        return new ScalarFromVector(this, ScalarFromVector.Z);
    }

    default ScalarProducer dotProduct(Producer<Vector> operand) {
        return new DotProduct(this, operand);
    }

    default VectorSum add(Producer<Vector> operand) {
        return new VectorSum(this, operand);
    }

    default VectorProduct multiply(Producer<Vector> operand) {
        return new VectorProduct(this, operand);
    }

    default VectorProduct scalarMultiply(Producer<Scalar> operand) {
        return new VectorProduct(this, new VectorFromScalars(operand, operand, operand));
    }

    default VectorProduct scalarMultiply(Scalar operand) {
        return scalarMultiply(new StaticProducer<>(operand));
    }

    default VectorProduct scalarMultiply(double operand) {
        return scalarMultiply(new Scalar(operand));
    }

    default ScalarProducer length() {
        return x().pow(2.0).add(y().pow(2.0)).add(z().pow(2.0)).pow(0.5);
    }

    default VectorProducer normalize() {
        ScalarProducer oneOverLength = length().pow(-1.0);
        return new VectorFromScalars(x().multiply(oneOverLength),
                                    y().multiply(oneOverLength),
                                    z().multiply(oneOverLength));
    }
}
