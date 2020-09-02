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
        return x(this);
    }

    static ScalarProducer x(Producer<Vector> v) {
        return new ScalarFromVector(v, ScalarFromVector.X);
    }

    default ScalarProducer y() {
        return y(this);
    }

    static ScalarProducer y(Producer<Vector> v) {
        return new ScalarFromVector(v, ScalarFromVector.Y);
    }

    default ScalarProducer z() {
        return z(this);
    }

    static ScalarProducer z(Producer<Vector> v) {
        return new ScalarFromVector(v, ScalarFromVector.Z);
    }

    default DotProduct dotProduct(Producer<Vector> operand) {
        return dotProduct(this, operand);
    }

    static DotProduct dotProduct(Producer<Vector> a, Producer<Vector> b) { return new DotProduct(a, b); }

    default CrossProduct crossProduct(Producer<Vector> operand) {
        return crossProduct(this, operand);
    }

    static CrossProduct crossProduct(Producer<Vector> a, Producer<Vector> b) { return new CrossProduct(a, b); }

    default VectorSum add(Producer<Vector> operand) {
        return new VectorSum(this, operand);
    }

    default VectorSum subtract(Producer<Vector> operand) { return subtract(this, operand); }

    static VectorSum subtract(Producer<Vector> value, Producer<Vector> operand) {
        return new VectorSum(value, minus(operand));
    }

    default VectorProduct multiply(Producer<Vector> operand) {
        return new VectorProduct(this, operand);
    }

    default VectorProduct scalarMultiply(Producer<Scalar> operand) {
        return new VectorProduct(this, new VectorFromScalars(operand, operand, operand));
    }

    default VectorProduct scalarMultiply(Scalar operand) {
        return scalarMultiply(StaticProducer.of(operand));
    }

    default VectorProduct scalarMultiply(double operand) {
        return scalarMultiply(new Scalar(operand));
    }

    default VectorProduct minus() {
        return minus(this);
    }

    static VectorProduct minus(Producer<Vector> p) {
        return new VectorProduct(p,
                new VectorFromScalars(ScalarProducer.minusOne,
                        ScalarProducer.minusOne,
                        ScalarProducer.minusOne));
    }

    default ScalarProducer length() {
        return length(this);
    }

    static ScalarProducer length(Producer<Vector> v) {
        return x(v).pow(2.0).add(y(v).pow(2.0)).add(z(v).pow(2.0)).pow(0.5);
    }

    default ScalarProducer lengthSq() {
        return x().pow(2.0).add(y().pow(2.0)).add(z().pow(2.0));
    }

    default VectorProducer normalize() {
        return normalize(this);
    }

    static VectorProducer normalize(Producer<Vector> p) {
        ScalarProducer oneOverLength = length(p).pow(-1.0);
        return new VectorFromScalars(x(p).multiply(oneOverLength),
                y(p).multiply(oneOverLength),
                z(p).multiply(oneOverLength));
    }
}
