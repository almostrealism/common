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

import org.almostrealism.algebra.computations.CrossProduct;
import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.algebra.computations.DotProduct;
import org.almostrealism.algebra.computations.ScalarFromVector;
import org.almostrealism.algebra.computations.VectorFromScalars;
import org.almostrealism.algebra.computations.VectorProduct;
import org.almostrealism.algebra.computations.VectorSum;
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
public interface VectorProducer extends VectorFeatures, Producer<Vector> {

    default ScalarProducer x() {
        return x(this);
    }

    default ScalarProducer y() {
        return y(this);
    }

    default ScalarProducer z() {
        return z(this);
    }

    default ScalarProducer dotProduct(Producer<Vector> operand) {
        return dotProduct(this, operand);
    }

    default VectorProducer crossProduct(Producer<Vector> operand) {
        return crossProduct(this, operand);
    }

    default VectorProducer add(Producer<Vector> operand) {
        return add(this, operand);
    }

    default VectorProducer subtract(Producer<Vector> operand) { return subtract(this, operand); }

    default VectorProducer multiply(Producer<Vector> operand) {
        return multiply(this, operand);
    }

    default VectorProducer scalarMultiply(Producer<Scalar> operand) { return scalarMultiply(this, operand); }

    default VectorProducer scalarMultiply(Scalar operand) {
        return scalarMultiply(StaticProducer.of(operand));
    }

    default VectorProducer scalarMultiply(double operand) {
        return scalarMultiply(new Scalar(operand));
    }

    default VectorProducer minus() {
        return minus(this);
    }

    default ScalarProducer length() {
        return length(this);
    }

    default ScalarProducer lengthSq() {
        return lengthSq(this);
    }

    default VectorProducer normalize() {
        return normalize(this);
    }
}
