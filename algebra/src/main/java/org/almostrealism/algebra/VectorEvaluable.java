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

import io.almostrealism.uml.Function;
import io.almostrealism.relation.Evaluable;

/**
 * {@link VectorEvaluable} is implemented by any class that can produce an {@link Vector} object
 * given some array of input objects.
 * 
 * @author  Michael Murray
 */
@Deprecated
@Function
public interface VectorEvaluable extends VectorFeatures, Evaluable<Vector> {

    default ScalarEvaluable x() {
        return x(this);
    }

    default ScalarEvaluable y() {
        return y(this);
    }

    default ScalarEvaluable z() {
        return z(this);
    }

    default ScalarEvaluable dotProduct(Evaluable<Vector> operand) {
        return dotProduct(this, operand);
    }

    default VectorEvaluable crossProduct(Evaluable<Vector> operand) {
        return crossProduct(this, operand);
    }

    default ScalarEvaluable length() {
        return length(this);
    }

    default VectorEvaluable normalize() {
        return normalize(this);
    }
}
